package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptValidationError;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSampleLimit;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTableItem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

@Component
public class ReplayDatabaseComparisonConfigScriptGenerator {

    private static final int INSERT_BATCH_SIZE = 500;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private static final List<String> DOMAIN_ORDER =
            List.of("存款组", "贷款组", "公共组", "结算组", "平台组");
    private static final Map<String, String> DOMAIN_MODULES = Map.of(
            "存款组", "dept",
            "贷款组", "loan",
            "公共组", "comm",
            "结算组", "sett",
            "平台组", "platform");

    public GeneratedScript generate(
            String versionNo,
            List<ReplayDbCompareVersionTableItem> snapshotTables) {
        return generate(versionNo, snapshotTables, false);
    }

    public GeneratedScript generateNew(
            String versionNo,
            List<ReplayDbCompareVersionTableItem> snapshotTables) {
        return generate(versionNo, snapshotTables, true);
    }

    /** Independent named configurations use the new schema's explicit per-table maximum. */
    public GeneratedScript generateNamed(String artifactVersion,
                                         List<ReplayDbCompareVersionTableItem> snapshotTables,
                                         Map<String,Integer> maxPartitionNums) {
        if (maxPartitionNums == null) throw new IllegalArgumentException("命名脚本必须指定最大hash分区数");
        return generate(artifactVersion,snapshotTables,true,maxPartitionNums,false);
    }

    private GeneratedScript generate(
            String versionNo,
            List<ReplayDbCompareVersionTableItem> snapshotTables,
            boolean newTarget) {
        Map<String,Integer> maximums = null;
        if (newTarget) {
            maximums = new HashMap<>();
            for (var table : snapshotTables == null ? List.<ReplayDbCompareVersionTableItem>of() : snapshotTables) {
                maximums.put(table.tableName(), table.maxPartitionNum());
            }
        }
        return generate(versionNo,snapshotTables,newTarget,maximums,newTarget);
    }

    private GeneratedScript generate(
            String versionNo,
            List<ReplayDbCompareVersionTableItem> snapshotTables,
            boolean newTarget,
            Map<String,Integer> maxPartitionNums,
            boolean configurationMetadata) {
        List<ReplayDbCompareVersionTableItem> tables = new ArrayList<>(
                snapshotTables == null ? List.of() : snapshotTables);
        validate(tables, newTarget);
        if (maxPartitionNums != null) {
            List<ReplayDbCompareConfigScriptValidationError> errors = new ArrayList<>();
            for (var table : tables) {
                Integer maximum = maxPartitionNums.get(table.tableName());
                if (maximum == null || maximum < 1 || maximum > 256) {
                    errors.add(error(table.tableName(),null,"最大hash分区数必须为1到256的整数"));
                }
            }
            if (!errors.isEmpty()) throw new ReplayDatabaseComparisonGenerationException(
                    HttpStatus.UNPROCESSABLE_ENTITY,"SCRIPT_CONFIG_VALIDATION_FAILED",
                    "脚本配置校验失败",Map.of("errors",List.copyOf(errors)));
        }
        tables.sort(Comparator
                .comparingInt((ReplayDbCompareVersionTableItem table) ->
                        DOMAIN_ORDER.indexOf(table.domainName()))
                .thenComparing(ReplayDbCompareVersionTableItem::tableName,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReplayDbCompareVersionTableItem::tableName));
        int fieldCount = tables.stream().mapToInt(table -> table.fields().size()).sum();
        try (ScriptOutput output = new ScriptOutput()) {
            writePreamble(output, newTarget, configurationMetadata);
            writeConfigurationRows(output, tables, newTarget, maxPartitionNums, configurationMetadata);
            writeFieldRows(output, tables, newTarget, maxPartitionNums!=null);
            writeTableSqlRows(output, tables, newTarget, maxPartitionNums!=null);
            output.write("COMMIT;\n\n");
            output.write("-- Version: " + versionNo + "\n");
            output.write("-- Tables: " + tables.size() + "\n");
            output.write("-- Fields: " + fieldCount + "\n");
            output.finish();
            return new GeneratedScript(
                    output.rawBytes(), output.gzipBytes(), output.sha256(),
                    output.rawSize(), output.gzipSize(), tables.size(), fieldCount);
        } catch (IOException exception) {
            throw new IllegalStateException("生成生产配置脚本失败", exception);
        }
    }

    private void validate(List<ReplayDbCompareVersionTableItem> tables, boolean newTarget) {
        List<ReplayDbCompareConfigScriptValidationError> errors = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (ReplayDbCompareVersionTableItem table : tables) {
            String tableName = table.tableName();
            if (tableName != null && !codes.add(tableName.toLowerCase(java.util.Locale.ROOT))) {
                errors.add(error(tableName, null, "固定配置标识重复"));
            }
            if (tableName != null && tableName.length() > 100) {
                errors.add(error(tableName, null, "固定配置标识不能超过100个字符"));
            }
            if (!validIdentifier(table.sourceTableName()) || table.sourceTableName().length() > 256) {
                errors.add(error(tableName, null, "真实来源表名不是合法数据库标识符"));
            }
            if (!newTarget && isAdditionalConfiguration(table)) {
                errors.add(error(tableName, null, "额外配置必须使用新版17列脚本"));
            }
            if (!ReplayDbCompareSampleLimit.isValid(table.sampleLimit())) {
                errors.add(error(tableName, null, ReplayDbCompareSampleLimit.RANGE_ERROR));
            }
            if (newTarget && table.reviserName() != null && table.reviserName().trim().length() > 100) {
                errors.add(error(tableName, null, "修订人姓名不能超过100个字符"));
            }
            if (newTarget && table.reviserUsername() != null && table.reviserUsername().trim().length() > 100) {
                errors.add(error(tableName, null, "修订人账号不能超过100个字符"));
            }
            String domainError = domainMappingError(table.domainName());
            if (domainError != null) {
                errors.add(error(tableName, null, domainError));
            }
            if (!validIdentifier(tableName)) {
                errors.add(error(tableName, null, "表英文名不是合法数据库标识符"));
            }
            if (table.fields().isEmpty()) {
                errors.add(error(tableName, null, "表没有比对字段"));
            }
            Set<Integer> orders = new HashSet<>();
            for (ReplayDbCompareVersionField field : table.fields()) {
                if (!validIdentifier(field.columnName())) {
                    errors.add(error(tableName, field.columnName(), "字段英文名不是合法数据库标识符"));
                }
                if (field.comparisonOrder() <= 0 || !orders.add(field.comparisonOrder())) {
                    errors.add(error(tableName, field.columnName(), "比对顺序必须为不重复的正整数"));
                }
            }
            if (table.comparisonKey() != null) {
                var names = table.fields().stream().map(ReplayDbCompareVersionField::columnName).toList();
                var key = table.comparisonKey();
                if (key.columnNames().isEmpty() || key.columnNames().stream().distinct().count() != key.columnNames().size()
                        || !names.containsAll(key.columnNames()) || key.columnNames().stream().anyMatch(name -> !validIdentifier(name))) {
                    errors.add(error(tableName, null, "版本比对键快照不完整或包含无效列"));
                }
            }
            if (table.compareLimit() != null) {
                validateLimitedTable(table, errors);
            }
            if (table.customSql() != null) {
                validateCustomSql(table, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new ReplayDatabaseComparisonGenerationException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "CONFIG_SCRIPT_VALIDATION_FAILED",
                    errors.size() + " 项配置无法生成生产脚本",
                    Map.of("errors", List.copyOf(errors)));
        }
    }

    static boolean isAdditionalConfiguration(ReplayDbCompareVersionTableItem table) {
        return table.configurationIdentity() != null
                && !table.configurationIdentity().configurationName().isBlank()
                && !table.tableName().equalsIgnoreCase(table.sourceTableName());
    }

    private void validateCustomSql(ReplayDbCompareVersionTableItem table,
                                   List<ReplayDbCompareConfigScriptValidationError> errors) {
        try {
            var definition = table.customSql();
            var parsed = ReplayDatabaseComparisonCustomSqlAnalyzer.parseSql(definition.sql());
            if (!parsed.sourceTableName().equals(definition.sourceTableName())
                    || !parsed.sourceTableName().equalsIgnoreCase(table.sourceTableName())) {
                throw new IllegalArgumentException("SQL 来源表与版本快照关联表不一致");
            }
            if (hasText(table.whereSql()) || table.whereCondition() != null) {
                throw new IllegalArgumentException("自定义 SQL 快照不能包含普通表查询条件");
            }
            if (table.comparisonKey() == null || !"RESULT_KEY".equals(table.comparisonKey().indexType())) {
                throw new IllegalArgumentException("自定义 SQL 必须包含结果比对键快照");
            }
            Map<String, ReplayDbCompareSqlColumn> columns = customColumns(table);
            Set<Integer> positions = new HashSet<>();
            for (ReplayDbCompareSqlColumn column : definition.resultColumns()) {
                String name = ReplayDatabaseComparisonCustomSqlAnalyzer.canonicalColumnName(column.columnLabel());
                if (!name.equals(column.columnName()) || column.ordinalPosition() < 1
                        || !positions.add(column.ordinalPosition())) {
                    throw new IllegalArgumentException("SQL 结果字段元数据不完整或存在重复列");
                }
            }
            if (columns.size() != definition.resultColumns().size()
                    || table.fields().stream().anyMatch(field -> !columns.containsKey(field.columnName()))) {
                throw new IllegalArgumentException("SQL 所选字段缺少结果元数据快照");
            }
        } catch (IllegalArgumentException exception) {
            errors.add(error(table.tableName(), null, exception.getMessage()));
        }
    }

    /** Shared by version generation and both SQL targets to keep domain eligibility consistent. */
    static String domainMappingError(String domainName) {
        if (domainName == null || domainName.isBlank()) {
            return "领域为空，缺少生产模块映射，请先维护表级人员清单中的领域";
        }
        return DOMAIN_MODULES.containsKey(domainName) ? null : "领域无法映射：" + domainName;
    }

    private void validateLimitedTable(
            ReplayDbCompareVersionTableItem table,
            List<ReplayDbCompareConfigScriptValidationError> errors) {
        if (table.compareLimit() < 1 || table.compareLimit() > 10_000_000L) {
            errors.add(error(table.tableName(), null, "比对条数必须在 1 到 10000000 之间"));
        }
        if (table.comparisonKey() != null) return;
        List<ReplayDbCompareVersionField> primaryKeys = table.fields().stream()
                .filter(ReplayDbCompareVersionField::primaryKey)
                .toList();
        Set<Integer> primaryKeyOrders = new HashSet<>();
        boolean validOrders = !primaryKeys.isEmpty();
        for (ReplayDbCompareVersionField field : primaryKeys) {
            Integer order = field.primaryKeyOrder();
            if (order == null || order <= 0 || !primaryKeyOrders.add(order)) {
                validOrders = false;
            }
        }
        for (int order = 1; order <= primaryKeys.size(); order++) {
            validOrders &= primaryKeyOrders.contains(order);
        }
        if (!validOrders) {
            errors.add(error(table.tableName(), null, "限制比对条数时必须包含完整且连续的主键顺序"));
        }
    }

    private ReplayDbCompareConfigScriptValidationError error(
            String tableName,
            String fieldName,
            String reason) {
        return new ReplayDbCompareConfigScriptValidationError(tableName, fieldName, reason);
    }

    private boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private void writePreamble(ScriptOutput output, boolean newTarget, boolean configurationMetadata) throws IOException {
        String suffix = newTarget ? "_new" : "";
        if (configurationMetadata) {
            output.write("-- 请通过 BD 配置导入功能导入此 NEW17 脚本，由导入器维护固定配置标识映射；不要直接在元数据库执行。\n");
        }
        output.write("START TRANSACTION;\n\n");
        output.write("TRUNCATE TABLE tss_bcomp_field" + suffix + ";\n");
        output.write("TRUNCATE TABLE tss_bcomp_table_sql" + suffix + ";\n");
        output.write("TRUNCATE TABLE tss_bcomp_conf" + suffix + ";\n\n");
    }

    private void writeConfigurationRows(
            ScriptOutput output,
            List<ReplayDbCompareVersionTableItem> tables, boolean newTarget,
            Map<String,Integer> maxPartitionNums, boolean configurationMetadata) throws IOException {
        writeBatches(output, tables.size(),
                "INSERT INTO tss_bcomp_conf" + (newTarget ? "_new" : "") + "\n"
                        + "  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,"
                        + "bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit"
                        + (newTarget ? ",reviser_name,reviser_username" : "")
                        + (maxPartitionNums != null ? ",bcomp_max_partition_num" : "")
                        + (configurationMetadata ? ",source_table_name,configuration_name,configuration_scope" : "") + ")\nVALUES\n",
                index -> {
                    ReplayDbCompareVersionTableItem table = tables.get(index);
                    String memoBase = hasText(table.tableComment())
                            ? table.tableComment().trim() : table.tableName();
                    String memo = memoBase.endsWith("比对") ? memoBase : memoBase + "比对";
                    boolean named=maxPartitionNums!=null;
                    return "(" + (index + 1)
                            + "," + quote(DOMAIN_MODULES.get(table.domainName()))
                            + "," + quote(table.tableName())
                            + "," + (named?quoteNamed(memo):quote(memo))
                            + ",'2','1','3','1',1,'HASH'," + table.sampleLimit()
                            + (newTarget ? "," + quoteNullable(table.reviserName(),named) + "," + quoteNullable(table.reviserUsername(),named) : "")
                            + (maxPartitionNums != null ? "," + maxPartitionNums.get(table.tableName()) : "")
                            + (configurationMetadata ? "," + quoteNamed(table.sourceTableName())
                                    + "," + quoteNamed(table.configurationName())
                                    + "," + quoteNamed(table.customSql() == null
                                            ? (table.whereSql() == null ? "" : table.whereSql()) : table.customSql().sql()) : "")
                            + ")";
                });
    }

    private void writeFieldRows(
            ScriptOutput output,
            List<ReplayDbCompareVersionTableItem> tables, boolean newTarget, boolean named) throws IOException {
        String header = "INSERT INTO tss_bcomp_field" + (newTarget ? "_new" : "") + "\n"
                + "  (bcomp_index,field_name,field_type,field_memo,field_old_index,"
                + "field_new_index,field_index_flag,field_enum_flag)\nVALUES\n";
        int batchPosition = 0;
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            List<ReplayDbCompareVersionField> fields = orderedFields(tables.get(tableIndex));
            for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                if (batchPosition == 0) {
                    output.write(header);
                } else {
                    output.write(",\n");
                }
                ReplayDbCompareVersionField field = fields.get(fieldIndex);
                String memo = hasText(field.columnComment())
                        ? field.columnComment().trim() : field.columnName();
                int order = fieldIndex + 1;
                output.write("(" + (tableIndex + 1)
                        + "," + quote(field.columnName())
                        + ",'1'," + (named?quoteNamed(memo):quote(memo))
                        + "," + quote(String.valueOf(order))
                        + "," + quote(String.valueOf(order))
                        + "," + quote(isComparisonKey(tables.get(tableIndex), field) ? "1" : "0")
                        + ",'0')");
                batchPosition++;
                if (batchPosition == INSERT_BATCH_SIZE) {
                    output.write(";\n\n");
                    batchPosition = 0;
                }
            }
        }
        if (batchPosition > 0) {
            output.write(";\n\n");
        }
    }

    private void writeTableSqlRows(
            ScriptOutput output,
            List<ReplayDbCompareVersionTableItem> tables, boolean newTarget, boolean named) throws IOException {
        writeBatches(output, tables.size(),
                "INSERT INTO tss_bcomp_table_sql" + (newTarget ? "_new" : "") + "\n"
                        + "  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)\nVALUES\n",
                index -> {
                    ReplayDbCompareVersionTableItem table = tables.get(index);
                    String select = table.customSql() == null ? tableSelect(table) : customSelect(table);
                    return "(" + (index + 1) + "," + quoteQuery(table, select + "orig",named) + ",1,"
                            + quoteQuery(table, select + "dest",named) + ",2)";
                });
    }

    private String tableSelect(ReplayDbCompareVersionTableItem table) {
        String fields = String.join(",", orderedFields(table).stream()
                .map(ReplayDbCompareVersionField::columnName).toList());
        StringBuilder select = new StringBuilder("(select ")
                .append(fields).append(" from ").append(table.sourceTableName());
        if (hasText(table.whereSql())) {
            select.append(" where ").append(table.whereSql());
        }
        if (table.compareLimit() != null) {
            select.append(" order by ")
                    .append(String.join(",", orderedPrimaryKeyFields(table).stream()
                            .map(ReplayDbCompareVersionField::columnName).toList()))
                    .append(" limit ").append(table.compareLimit());
        }
        return select.append(") ").toString();
    }

    private String customSelect(ReplayDbCompareVersionTableItem table) {
        Map<String, ReplayDbCompareSqlColumn> columns = customColumns(table);
        String fields = String.join(",", orderedFields(table).stream().map(field ->
                "result_q." + quoteIdentifier(columns.get(field.columnName()).columnLabel())
                        + " as " + quoteIdentifier(field.columnName())).toList());
        String sql = ReplayDatabaseComparisonCustomSqlAnalyzer.parseSql(table.customSql().sql()).sql();
        StringBuilder select = new StringBuilder("(select ").append(fields)
                .append(" from (\n").append(sql).append("\n) result_q");
        if (table.compareLimit() != null) {
            select.append(" order by ")
                    .append(String.join(",", table.comparisonKey().columnNames().stream()
                            .map(this::quoteIdentifier).toList()))
                    .append(" limit ").append(table.compareLimit());
        }
        return select.append(") ").toString();
    }

    private Map<String, ReplayDbCompareSqlColumn> customColumns(ReplayDbCompareVersionTableItem table) {
        Map<String, ReplayDbCompareSqlColumn> columns = new HashMap<>();
        table.customSql().resultColumns().forEach(column -> columns.put(column.columnName(), column));
        return columns;
    }

    private String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private String quoteQuery(ReplayDbCompareVersionTableItem table, String query,boolean named) {
        if (named) return quoteNamed(query);
        if (table.customSql() != null && query.indexOf('\\') >= 0) {
            return "CONVERT(0x" + HexFormat.of().formatHex(query.getBytes(StandardCharsets.UTF_8)) + " USING utf8mb4)";
        }
        return quote(query);
    }

    private void writeBatches(
            ScriptOutput output,
            int rowCount,
            String header,
            Function<Integer, String> row) throws IOException {
        for (int index = 0; index < rowCount; index++) {
            int batchPosition = index % INSERT_BATCH_SIZE;
            output.write(batchPosition == 0 ? header : ",\n");
            output.write(row.apply(index));
            if (batchPosition == INSERT_BATCH_SIZE - 1 || index == rowCount - 1) {
                output.write(";\n\n");
            }
        }
    }

    private List<ReplayDbCompareVersionField> orderedFields(ReplayDbCompareVersionTableItem table) {
        return table.fields().stream()
                .sorted(Comparator.comparingInt(ReplayDbCompareVersionField::comparisonOrder))
                .toList();
    }

    private List<ReplayDbCompareVersionField> orderedPrimaryKeyFields(
            ReplayDbCompareVersionTableItem table) {
        if (table.comparisonKey() != null) {
            return table.comparisonKey().columnNames().stream().map(name -> table.fields().stream()
                    .filter(field -> field.columnName().equals(name)).findFirst().orElseThrow()).toList();
        }
        return table.fields().stream()
                .filter(ReplayDbCompareVersionField::primaryKey)
                .sorted(Comparator.comparingInt(ReplayDbCompareVersionField::primaryKeyOrder))
                .toList();
    }

    private boolean isComparisonKey(ReplayDbCompareVersionTableItem table, ReplayDbCompareVersionField field) {
        return table.comparisonKey() == null ? field.primaryKey() : table.comparisonKey().columnNames().contains(field.columnName());
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String quoteNullable(String value,boolean named) {
        return hasText(value) ? (named?quoteNamed(value.trim()):quote(value.trim())) : "NULL";
    }

    private String quoteNamed(String value) {
        if (value.indexOf('\0')>=0) throw new IllegalArgumentException("命名脚本文本不能包含NUL字符");
        return value.indexOf('\\')>=0
                ? "CONVERT(0x"+HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8))+" USING utf8mb4)"
                : quote(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record GeneratedScript(
            byte[] sqlContent,
            byte[] gzipContent,
            String sha256,
            long scriptSize,
            long compressedSize,
            int tableCount,
            int fieldCount) {
    }

    private static final class ScriptOutput implements AutoCloseable {

        private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        private final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        private final MessageDigest digest;
        private final GZIPOutputStream gzip;
        private boolean finished;

        private ScriptOutput() throws IOException {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("JVM 不支持 SHA-256", exception);
            }
            gzip = new GZIPOutputStream(compressed);
        }

        private void write(String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            raw.write(bytes);
            digest.update(bytes);
            gzip.write(bytes);
        }

        private void finish() throws IOException {
            if (!finished) {
                gzip.finish();
                finished = true;
            }
        }

        private byte[] rawBytes() {
            return raw.toByteArray();
        }

        private byte[] gzipBytes() {
            return compressed.toByteArray();
        }

        private String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }

        private long rawSize() {
            return raw.size();
        }

        private long gzipSize() {
            return compressed.size();
        }

        @Override
        public void close() throws IOException {
            finish();
            gzip.close();
        }
    }
}
