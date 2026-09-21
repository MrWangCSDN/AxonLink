package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionSummary;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTableItem;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTablePage;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonGenerationException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonConditionCodec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ReplayDatabaseComparisonVersionDao {

    private static final int FIELD_TABLE_ID_BATCH_SIZE = 500;
    private static final String EMPTY_FILTER_VALUE = ReplayDatabaseComparisonDao.EMPTY_FILTER_VALUE;
    private static final String FULL_TABLE_FILTER_VALUE = "__FULL_TABLE__";
    private static final String REVISER_FILTER_EXPRESSION =
            "COALESCE(NULLIF(TRIM(t.reviser_emp_no),''),NULLIF(TRIM(t.reviser_username),''),'"
                    + EMPTY_FILTER_VALUE + "')";

    private final JdbcTemplate jdbc;
    private final ReplayDatabaseComparisonConditionCodec conditionCodec;

    public ReplayDatabaseComparisonVersionDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.conditionCodec = new ReplayDatabaseComparisonConditionCodec();
    }

    public long insertVersion(
            String versionNo,
            String configurationHash,
            int tableCount,
            int fieldCount,
            String generatedBy,
            String generatedName,
            LocalDateTime generatedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_db_compare_version "
                            + "(version_no,configuration_hash,table_count,field_count,"
                            + "generated_by,generated_name,generated_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, versionNo);
            statement.setString(2, configurationHash);
            statement.setInt(3, tableCount);
            statement.setInt(4, fieldCount);
            statement.setString(5, generatedBy);
            statement.setString(6, generatedName);
            statement.setTimestamp(7, timestamp(generatedAt));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("创建数据库比对字段版本失败");
        }
        return key.longValue();
    }

    public long insertVersionTable(long versionId, ReplayDbCompareRegistration registration) {
        return insertVersionTable(versionId, registration, null);
    }

    public long insertVersionTable(
            long versionId,
            ReplayDbCompareRegistration registration,
            String groupOwnerUsername) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_db_compare_version_table "
                            + "(version_id,source_registration_id,source_registration_version,"
                            + "schema_name,table_name,table_comment,domain_name,reviser_emp_no,"
                            + "reviser_username,reviser_name,group_owner_emp_no,group_owner_username,"
                            + "group_owner_name,where_condition_json,where_sql,compare_limit,registered_date,partition_num) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, versionId);
            statement.setLong(2, registration.id());
            statement.setLong(3, registration.version());
            statement.setString(4, registration.schemaName());
            statement.setString(5, registration.tableName());
            statement.setString(6, registration.tableComment());
            statement.setString(7, registration.domainName());
            statement.setString(8, registration.reviserEmpNo());
            statement.setString(9, registration.reviserUsername());
            statement.setString(10, registration.reviserName());
            statement.setString(11, registration.groupOwnerEmpNo());
            statement.setString(12, groupOwnerUsername);
            statement.setString(13, registration.groupOwnerName());
            statement.setString(14, conditionCodec.encode(registration.whereCondition()));
            statement.setString(15, registration.compiledWhereSql());
            statement.setObject(16, registration.compareLimit());
            statement.setObject(17, registration.registeredDate());
            statement.setInt(18, registration.partitionNum());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("创建数据库比对字段版本表快照失败");
        }
        return key.longValue();
    }

    public void insertVersionFields(long versionTableId, List<ReplayDbCompareField> fields) {
        jdbc.batchUpdate(
                "INSERT INTO dii_replay_db_compare_version_field "
                        + "(version_table_id,column_name,column_comment,ordinal_position,primary_key,"
                        + "primary_key_order,comparison_order) VALUES (?,?,?,?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        ReplayDbCompareField field = fields.get(index);
                        statement.setLong(1, versionTableId);
                        statement.setString(2, field.columnName());
                        statement.setString(3, field.columnComment());
                        statement.setInt(4, field.ordinalPosition());
                        statement.setBoolean(5, field.primaryKey());
                        statement.setObject(6, field.primaryKeyOrder());
                        statement.setInt(7, field.comparisonOrder());
                    }

                    @Override
                    public int getBatchSize() {
                        return fields.size();
                    }
                });
    }

    public StoredVersion findLatestStoredVersion() {
        List<StoredVersion> rows = jdbc.query(
                "SELECT * FROM dii_replay_db_compare_version ORDER BY generated_at DESC,id DESC LIMIT 1",
                (row, index) -> mapStoredVersion(row));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StoredVersion findStoredVersion(String versionNo) {
        List<StoredVersion> rows = jdbc.query(
                "SELECT * FROM dii_replay_db_compare_version WHERE version_no=?",
                (row, index) -> mapStoredVersion(row), versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ReplayDbCompareVersionTableItem> findCompleteSnapshot(long versionId) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT t.*,(SELECT COUNT(*) FROM dii_replay_db_compare_version_field fc "
                        + "WHERE fc.version_table_id=t.id) AS field_count "
                        + "FROM dii_replay_db_compare_version_table t "
                        + "WHERE t.version_id=? ORDER BY LOWER(t.table_name),t.id",
                (row, index) -> new SnapshotRow(
                        row.getLong("id"),
                        new ReplayDbCompareVersionTableItem(
                                row.getLong("source_registration_id"),
                                row.getLong("source_registration_version"),
                                row.getString("schema_name"), row.getString("table_name"),
                                row.getString("table_comment"), row.getString("domain_name"),
                                row.getString("reviser_emp_no"), row.getString("reviser_username"),
                                row.getString("reviser_name"), row.getString("group_owner_emp_no"),
                                row.getString("group_owner_username"),
                                row.getString("group_owner_name"),
                                conditionCodec.decode(row.getString("where_condition_json")),
                                row.getString("where_sql"),
                                row.getObject("compare_limit", Long.class),
                                row.getDate("registered_date").toLocalDate(),
                                row.getInt("field_count"), List.of()).withPartitionNum(row.getInt("partition_num"))),
                versionId);
        Map<Long, List<ReplayDbCompareVersionField>> fields = findVersionFields(
                rows.stream().map(SnapshotRow::id).toList());
        return rows.stream()
                .map(snapshot -> withFields(snapshot.item(), fields.getOrDefault(snapshot.id(), List.of())))
                .toList();
    }

    public ReplayDbCompareVersionSummary findLatestVersion() {
        StoredVersion latest = findLatestStoredVersion();
        return latest == null ? null : summary(latest, true);
    }

    public ReplayDbCompareVersionPage findVersions(int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 100);
        Long totalValue = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Long.class);
        long total = totalValue == null ? 0 : totalValue;
        StoredVersion latest = findLatestStoredVersion();
        List<ReplayDbCompareVersionSummary> items = jdbc.query(
                "SELECT * FROM dii_replay_db_compare_version "
                        + "ORDER BY generated_at DESC,id DESC LIMIT ? OFFSET ?",
                (row, index) -> {
                    StoredVersion stored = mapStoredVersion(row);
                    return summary(stored, latest != null && stored.id() == latest.id());
                }, size, page * size);
        return new ReplayDbCompareVersionPage(items, page, size, total);
    }

    public ReplayDbCompareVersionTablePage searchVersion(
            String versionNo,
            ReplayDbCompareVersionQuery query) {
        long versionId = requireVersionId(versionNo);
        ReplayDbCompareVersionQuery effective = query == null
                ? ReplayDbCompareVersionQuery.empty(0, 50) : query;
        int page = Math.max(0, effective.page());
        int size = Math.min(Math.max(1, effective.size()), 200);
        VersionFilter filter = buildVersionFilter(versionId, effective, null);
        long total = countVersionTables(filter);
        List<Object> arguments = new ArrayList<>(filter.arguments());
        arguments.add(size);
        arguments.add(page * size);
        List<SnapshotRow> rows = jdbc.query(
                "SELECT t.*,(SELECT COUNT(*) FROM dii_replay_db_compare_version_field fc "
                        + "WHERE fc.version_table_id=t.id) AS field_count "
                        + "FROM dii_replay_db_compare_version_table t "
                        + filter.sql() + " ORDER BY t.table_name,t.id LIMIT ? OFFSET ?",
                (row, index) -> new SnapshotRow(
                        row.getLong("id"),
                        new ReplayDbCompareVersionTableItem(
                                row.getLong("source_registration_id"),
                                row.getLong("source_registration_version"),
                                row.getString("schema_name"), row.getString("table_name"),
                                row.getString("table_comment"), row.getString("domain_name"),
                                row.getString("reviser_emp_no"), row.getString("reviser_username"),
                                row.getString("reviser_name"), row.getString("group_owner_emp_no"),
                                row.getString("group_owner_username"),
                                row.getString("group_owner_name"),
                                conditionCodec.decode(row.getString("where_condition_json")),
                                row.getString("where_sql"),
                                row.getObject("compare_limit", Long.class),
                                row.getDate("registered_date").toLocalDate(),
                                row.getInt("field_count"), List.of()).withPartitionNum(row.getInt("partition_num"))),
                arguments.toArray());
        Map<Long, List<ReplayDbCompareVersionField>> fields = findVersionFields(
                rows.stream().map(SnapshotRow::id).toList());
        List<ReplayDbCompareVersionTableItem> items = rows.stream()
                .map(snapshot -> withFields(snapshot.item(), fields.getOrDefault(snapshot.id(), List.of())))
                .toList();
        return new ReplayDbCompareVersionTablePage(items, page, size, total);
    }

    public ReplayDbCompareHeaderFilterResult versionHeaderFilterOptions(
            String versionNo,
            ReplayDbCompareHeaderFilterRequest request) {
        long versionId = requireVersionId(versionNo);
        VersionHeaderColumn column = VersionHeaderColumn.from(request.targetColumn());
        ReplayDbCompareVersionQuery query = new ReplayDbCompareVersionQuery(
                0, 1, request.tableKeyword(), request.fieldKeyword(), request.domains(),
                request.reviserEmpNos(), request.groupOwnerEmpNos(),
                request.registeredDateFrom(), request.registeredDateTo(),
                request.tableNames(), request.fieldNames(), request.registeredDates(),
                request.whereConditionValues());
        VersionFilter filter = buildVersionFilter(versionId, query, column.queryKey());
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(column.valueExpression()).append(" AS option_value,")
                .append(column.labelExpression()).append(" AS option_label,")
                .append("COUNT(DISTINCT t.id) AS option_count ")
                .append("FROM dii_replay_db_compare_version_table t ");
        if (column.fieldColumn()) {
            sql.append("JOIN dii_replay_db_compare_version_field hf ON hf.version_table_id=t.id ");
        }
        sql.append(filter.sql());
        List<Object> arguments = new ArrayList<>(filter.arguments());
        if (hasText(request.keyword()) && !"whereCondition".equals(column.queryKey())) {
            sql.append(" AND LOWER(").append(column.searchExpression()).append(") LIKE ?");
            arguments.add(pattern(request.keyword()));
        }
        sql.append(" GROUP BY ").append(column.valueExpression()).append(',')
                .append(column.labelExpression()).append(" ORDER BY option_count DESC,option_value");
        List<ReplayDbCompareHeaderFilterOption> all = jdbc.query(
                sql.toString(),
                (row, index) -> new ReplayDbCompareHeaderFilterOption(
                        row.getString("option_value"), row.getString("option_label"),
                        row.getLong("option_count")),
                arguments.toArray());
        int limit = request.effectiveLimit();
        List<ReplayDbCompareHeaderFilterOption> options = all.stream().limit(limit).toList();
        return new ReplayDbCompareHeaderFilterResult(
                options, all.size(), countVersionTables(filter), all.size() > options.size());
    }

    private Map<Long, List<ReplayDbCompareVersionField>> findVersionFields(List<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ReplayDbCompareVersionField>> result = new LinkedHashMap<>();
        tableIds.forEach(id -> result.put(id, new ArrayList<>()));
        for (int start = 0; start < tableIds.size(); start += FIELD_TABLE_ID_BATCH_SIZE) {
            List<Long> batch = tableIds.subList(
                    start, Math.min(start + FIELD_TABLE_ID_BATCH_SIZE, tableIds.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            jdbc.query("SELECT * FROM dii_replay_db_compare_version_field "
                            + "WHERE version_table_id IN (" + placeholders + ") "
                            + "ORDER BY version_table_id,comparison_order,id",
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> result.get(
                            row.getLong("version_table_id")).add(
                            new ReplayDbCompareVersionField(
                                    row.getString("column_name"), row.getString("column_comment"),
                                    row.getInt("ordinal_position"), row.getBoolean("primary_key"),
                                    row.getObject("primary_key_order", Integer.class),
                                    row.getInt("comparison_order"))),
                    batch.toArray());
        }
        return result;
    }

    private VersionFilter buildVersionFilter(
            long versionId,
            ReplayDbCompareVersionQuery query,
            String excludedKey) {
        StringBuilder sql = new StringBuilder("WHERE t.version_id=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(versionId);
        if (!"tableName".equals(excludedKey) && hasText(query.tableKeyword())) {
            sql.append(" AND LOWER(CONCAT(t.table_name,' ',COALESCE(t.table_comment,''))) LIKE ?");
            arguments.add(pattern(query.tableKeyword()));
        }
        if (!"tableName".equals(excludedKey)) {
            appendValues(sql, arguments, "tableName", excludedKey, "t.table_name", query.tableNames());
        }
        if (!"fieldName".equals(excludedKey) && hasText(query.fieldKeyword())) {
            sql.append(" AND EXISTS (SELECT 1 FROM dii_replay_db_compare_version_field vf "
                    + "WHERE vf.version_table_id=t.id AND "
                    + "LOWER(CONCAT(vf.column_name,' ',COALESCE(vf.column_comment,''))) LIKE ?)");
            arguments.add(pattern(query.fieldKeyword()));
        }
        if (!"fieldName".equals(excludedKey) && !query.fieldNames().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM dii_replay_db_compare_version_field ef "
                    + "WHERE ef.version_table_id=t.id");
            appendValues(sql, arguments, "fieldName", null, "ef.column_name", query.fieldNames());
            sql.append(')');
        }
        if (!"whereCondition".equals(excludedKey) && !query.whereConditionValues().isEmpty()) {
            appendWhereConditionValues(sql, arguments, query.whereConditionValues());
        }
        appendValues(sql, arguments, "domainName", excludedKey, "t.domain_name", query.domains());
        appendValues(sql, arguments, "reviser", excludedKey,
                REVISER_FILTER_EXPRESSION, query.reviserEmpNos());
        appendNullableValues(sql, arguments, "groupOwner", excludedKey,
                "t.group_owner_emp_no", query.groupOwnerEmpNos());
        if (!"registeredDate".equals(excludedKey) && !query.registeredDates().isEmpty()) {
            sql.append(" AND t.registered_date IN (")
                    .append(String.join(",", Collections.nCopies(query.registeredDates().size(), "?")))
                    .append(')');
            query.registeredDates().forEach(date -> arguments.add(Date.valueOf(date)));
        }
        if (!"registeredDate".equals(excludedKey) && query.registeredDateFrom() != null) {
            sql.append(" AND t.registered_date>=?");
            arguments.add(query.registeredDateFrom());
        }
        if (!"registeredDate".equals(excludedKey) && query.registeredDateTo() != null) {
            sql.append(" AND t.registered_date<=?");
            arguments.add(query.registeredDateTo());
        }
        return new VersionFilter(sql.toString(), arguments);
    }

    private void appendValues(
            StringBuilder sql,
            List<Object> arguments,
            String key,
            String excludedKey,
            String column,
            List<String> values) {
        if (key.equals(excludedKey) || values == null || values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", Collections.nCopies(values.size(), "?"))).append(')');
        arguments.addAll(values);
    }

    private void appendNullableValues(
            StringBuilder sql,
            List<Object> arguments,
            String key,
            String excludedKey,
            String column,
            List<String> values) {
        if (key.equals(excludedKey) || values == null || values.isEmpty()) {
            return;
        }
        List<String> normalized = values.stream()
                .filter(this::hasText).map(String::trim).distinct().toList();
        if (normalized.isEmpty()) {
            return;
        }
        boolean includesEmpty = normalized.contains(EMPTY_FILTER_VALUE);
        List<String> actual = normalized.stream()
                .filter(value -> !EMPTY_FILTER_VALUE.equals(value)).toList();
        sql.append(" AND (");
        if (!actual.isEmpty()) {
            sql.append(column).append(" IN (")
                    .append(String.join(",", Collections.nCopies(actual.size(), "?"))).append(')');
            arguments.addAll(actual);
        }
        if (includesEmpty) {
            if (!actual.isEmpty()) {
                sql.append(" OR ");
            }
            sql.append("TRIM(COALESCE(").append(column).append(",''))=''");
        }
        sql.append(')');
    }

    private long countVersionTables(VersionFilter filter) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version_table t " + filter.sql(),
                Long.class, filter.arguments().toArray());
        return count == null ? 0 : count;
    }

    private long requireVersionId(String versionNo) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM dii_replay_db_compare_version WHERE version_no=?",
                (row, index) -> row.getLong(1), versionNo);
        if (ids.isEmpty()) {
            throw new ReplayDatabaseComparisonGenerationException(
                    HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "版本不存在", null);
        }
        return ids.get(0);
    }

    private StoredVersion mapStoredVersion(java.sql.ResultSet row) throws SQLException {
        return new StoredVersion(
                row.getLong("id"), row.getString("version_no"),
                row.getString("configuration_hash"), row.getInt("table_count"),
                row.getInt("field_count"), row.getString("generated_by"),
                row.getString("generated_name"),
                row.getTimestamp("generated_at").toLocalDateTime());
    }

    private ReplayDbCompareVersionSummary summary(StoredVersion stored, boolean latest) {
        return new ReplayDbCompareVersionSummary(
                stored.versionNo(), stored.generatedBy(), stored.generatedName(),
                stored.generatedAt(), stored.tableCount(), stored.fieldCount(), latest);
    }

    private ReplayDbCompareVersionTableItem withFields(
            ReplayDbCompareVersionTableItem item,
            List<ReplayDbCompareVersionField> fields) {
        return new ReplayDbCompareVersionTableItem(
                item.sourceRegistrationId(), item.sourceRegistrationVersion(),
                item.schemaName(), item.tableName(), item.tableComment(), item.domainName(),
                item.reviserEmpNo(), item.reviserUsername(), item.reviserName(),
                item.groupOwnerEmpNo(), item.groupOwnerUsername(), item.groupOwnerName(),
                item.whereCondition(), item.whereSql(), item.compareLimit(), item.registeredDate(),
                item.fieldCount(), fields).withPartitionNum(item.partitionNum());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String pattern(String value) {
        return "%" + value.trim().toLowerCase(java.util.Locale.ROOT) + "%";
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public record StoredVersion(
            long id,
            String versionNo,
            String configurationHash,
            int tableCount,
            int fieldCount,
            String generatedBy,
            String generatedName,
            LocalDateTime generatedAt) {
    }

    private record SnapshotRow(long id, ReplayDbCompareVersionTableItem item) {
    }

    private record VersionFilter(String sql, List<Object> arguments) {
    }

    private record VersionHeaderColumn(
            String queryKey,
            String valueExpression,
            String labelExpression,
            String searchExpression,
            boolean fieldColumn) {

        private static VersionHeaderColumn from(String key) {
            return switch (key == null ? "" : key) {
                case "domainName" -> new VersionHeaderColumn(
                        key,
                        "CASE WHEN TRIM(COALESCE(t.domain_name,''))='' THEN '" + EMPTY_FILTER_VALUE + "' ELSE t.domain_name END",
                        "CASE WHEN TRIM(COALESCE(t.domain_name,''))='' THEN '空' ELSE t.domain_name END",
                        "COALESCE(t.domain_name,'')", false);
                case "tableName" -> new VersionHeaderColumn(
                        key, "t.table_name",
                        "CASE WHEN COALESCE(t.table_comment,'')='' THEN t.table_name ELSE CONCAT(t.table_name,'(',t.table_comment,')') END",
                        "CONCAT(t.table_name,' ',COALESCE(t.table_comment,''))", false);
                case "fieldName" -> new VersionHeaderColumn(
                        key, "hf.column_name",
                        "CASE WHEN COALESCE(hf.column_comment,'')='' THEN hf.column_name ELSE CONCAT(hf.column_name,'(',hf.column_comment,')') END",
                        "CONCAT(hf.column_name,' ',COALESCE(hf.column_comment,''))", true);
                case "whereCondition" -> new VersionHeaderColumn(
                        key,
                        "COALESCE(t.where_condition_json,'" + FULL_TABLE_FILTER_VALUE + "')",
                        "COALESCE(t.where_condition_json,'" + FULL_TABLE_FILTER_VALUE + "')",
                        "COALESCE(t.where_condition_json,'')", false);
                case "reviser" -> new VersionHeaderColumn(
                        key,
                        REVISER_FILTER_EXPRESSION,
                        "CASE WHEN TRIM(COALESCE(t.reviser_emp_no,''))='' AND TRIM(COALESCE(t.reviser_username,''))='' AND TRIM(COALESCE(t.reviser_name,''))='' THEN '空' "
                                + "WHEN COALESCE(t.reviser_name,'')<>'' AND COALESCE(NULLIF(t.reviser_username,''),t.reviser_emp_no,'')<>'' THEN "
                                + "CONCAT(t.reviser_name,'(',COALESCE(NULLIF(t.reviser_username,''),t.reviser_emp_no),')') "
                                + "WHEN COALESCE(t.reviser_name,'')<>'' THEN t.reviser_name "
                                + "ELSE COALESCE(NULLIF(t.reviser_username,''),t.reviser_emp_no,'') END",
                        "CONCAT(COALESCE(t.reviser_emp_no,''),' ',"
                                + "COALESCE(t.reviser_username,''),' ',COALESCE(t.reviser_name,''))",
                        false);
                case "groupOwner" -> new VersionHeaderColumn(
                        key,
                        "CASE WHEN TRIM(COALESCE(t.group_owner_emp_no,''))='' THEN '" + EMPTY_FILTER_VALUE + "' ELSE t.group_owner_emp_no END",
                        "CASE WHEN TRIM(COALESCE(t.group_owner_emp_no,''))='' AND TRIM(COALESCE(t.group_owner_username,''))='' AND TRIM(COALESCE(t.group_owner_name,''))='' THEN '空' "
                                + "WHEN COALESCE(t.group_owner_name,'')<>'' THEN CONCAT(t.group_owner_name,'(',COALESCE(NULLIF(t.group_owner_username,''),t.group_owner_emp_no),')') "
                                + "ELSE COALESCE(NULLIF(t.group_owner_username,''),t.group_owner_emp_no,'') END",
                        "CONCAT(COALESCE(t.group_owner_emp_no,''),' ',"
                                + "COALESCE(t.group_owner_username,''),' ',COALESCE(t.group_owner_name,''))",
                        false);
                case "registeredDate" -> new VersionHeaderColumn(
                        key,
                        "CASE WHEN t.registered_date IS NULL THEN '" + EMPTY_FILTER_VALUE + "' ELSE CAST(t.registered_date AS CHAR) END",
                        "CASE WHEN t.registered_date IS NULL THEN '空' ELSE CAST(t.registered_date AS CHAR) END",
                        "COALESCE(CAST(t.registered_date AS CHAR),'')", false);
                default -> throw new IllegalArgumentException("不支持的筛选列");
            };
        }
    }

    private void appendWhereConditionValues(
            StringBuilder sql,
            List<Object> arguments,
            List<String> values) {
        boolean includesFullTable = values.contains(FULL_TABLE_FILTER_VALUE);
        List<String> configured = values.stream()
                .filter(value -> !FULL_TABLE_FILTER_VALUE.equals(value))
                .toList();
        sql.append(" AND (");
        if (includesFullTable) {
            sql.append("t.where_condition_json IS NULL");
        }
        if (!configured.isEmpty()) {
            if (includesFullTable) {
                sql.append(" OR ");
            }
            sql.append("t.where_condition_json IN (")
                    .append(String.join(",", Collections.nCopies(configured.size(), "?")))
                    .append(')');
            arguments.addAll(configured);
        }
        sql.append(')');
    }
}
