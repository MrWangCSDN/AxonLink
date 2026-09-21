package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptValidationError;
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
        List<ReplayDbCompareVersionTableItem> tables = new ArrayList<>(
                snapshotTables == null ? List.of() : snapshotTables);
        validate(tables);
        tables.sort(Comparator
                .comparingInt((ReplayDbCompareVersionTableItem table) ->
                        DOMAIN_ORDER.indexOf(table.domainName()))
                .thenComparing(ReplayDbCompareVersionTableItem::tableName,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReplayDbCompareVersionTableItem::tableName));
        int fieldCount = tables.stream().mapToInt(table -> table.fields().size()).sum();
        try (ScriptOutput output = new ScriptOutput()) {
            writePreamble(output);
            writeConfigurationRows(output, tables);
            writeFieldRows(output, tables);
            writeTableSqlRows(output, tables);
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

    private void validate(List<ReplayDbCompareVersionTableItem> tables) {
        List<ReplayDbCompareConfigScriptValidationError> errors = new ArrayList<>();
        for (ReplayDbCompareVersionTableItem table : tables) {
            String tableName = table.tableName();
            if (table.partitionNum() < 1 || table.partitionNum() > 256) {
                errors.add(error(tableName, null, "读取分区数必须为 1 到 256 的整数"));
            }
            if (!DOMAIN_MODULES.containsKey(table.domainName())) {
                errors.add(error(tableName, null, "领域无法映射：" + table.domainName()));
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
            if (table.compareLimit() != null) {
                validateLimitedTable(table, errors);
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

    private void validateLimitedTable(
            ReplayDbCompareVersionTableItem table,
            List<ReplayDbCompareConfigScriptValidationError> errors) {
        if (table.compareLimit() < 1 || table.compareLimit() > 10_000_000L) {
            errors.add(error(table.tableName(), null, "比对条数必须在 1 到 10000000 之间"));
        }
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

    private void writePreamble(ScriptOutput output) throws IOException {
        output.write("START TRANSACTION;\n\n");
        output.write("TRUNCATE TABLE tss_bcomp_field;\n");
        output.write("TRUNCATE TABLE tss_bcomp_table_sql;\n");
        output.write("TRUNCATE TABLE tss_bcomp_conf;\n\n");
    }

    private void writeConfigurationRows(
            ScriptOutput output,
            List<ReplayDbCompareVersionTableItem> tables) throws IOException {
        writeBatches(output, tables.size(),
                "INSERT INTO tss_bcomp_conf\n"
                        + "  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,"
                        + "bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy)\nVALUES\n",
                index -> {
                    ReplayDbCompareVersionTableItem table = tables.get(index);
                    String memoBase = hasText(table.tableComment())
                            ? table.tableComment().trim() : table.tableName();
                    String memo = memoBase.endsWith("比对") ? memoBase : memoBase + "比对";
                    return "(" + (index + 1)
                            + "," + quote(DOMAIN_MODULES.get(table.domainName()))
                            + "," + quote(table.tableName())
                            + "," + quote(memo)
                            + ",'2','1','3','1'," + table.partitionNum() + ",'HASH')";
                });
    }

    private void writeFieldRows(
            ScriptOutput output,
            List<ReplayDbCompareVersionTableItem> tables) throws IOException {
        String header = "INSERT INTO tss_bcomp_field\n"
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
                        + ",'1'," + quote(memo)
                        + "," + quote(String.valueOf(order))
                        + "," + quote(String.valueOf(order))
                        + "," + quote(field.primaryKey() ? "1" : "0")
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
            List<ReplayDbCompareVersionTableItem> tables) throws IOException {
        writeBatches(output, tables.size(),
                "INSERT INTO tss_bcomp_table_sql\n"
                        + "  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)\nVALUES\n",
                index -> {
                    ReplayDbCompareVersionTableItem table = tables.get(index);
                    String fields = String.join(",", orderedFields(table).stream()
                            .map(ReplayDbCompareVersionField::columnName).toList());
                    StringBuilder select = new StringBuilder("(select ")
                            .append(fields).append(" from ").append(table.tableName());
                    if (hasText(table.whereSql())) {
                        select.append(" where ").append(table.whereSql());
                    }
                    if (table.compareLimit() != null) {
                        select.append(" order by ")
                                .append(String.join(",", orderedPrimaryKeyFields(table).stream()
                                        .map(ReplayDbCompareVersionField::columnName).toList()))
                                .append(" limit ").append(table.compareLimit());
                    }
                    select.append(") ");
                    return "(" + (index + 1) + "," + quote(select + "orig") + ",1,"
                            + quote(select + "dest") + ",2)";
                });
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
        return table.fields().stream()
                .filter(ReplayDbCompareVersionField::primaryKey)
                .sorted(Comparator.comparingInt(ReplayDbCompareVersionField::primaryKeyOrder))
                .toList();
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
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
