package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayIssueHistoryEntry;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Result-database access for the active parallel replay issue snapshot. */
@Repository
public class ReplayIssueDao {

    private static final int BATCH_SIZE = 2_000;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 200;

    private static final String INSERT_SQL = """
            INSERT INTO dii_replay_issue (
                source_sheet, group_name, is_sandbox, row_order,
                domain, sequence_no, batch_no, transaction_code, transaction_name, issue_level,
                registered_date, field_name, issue_description, transaction_owner, issue_type,
                initial_analysis, final_solution, resolved_date, cooperation_group, resolver,
                serial_no, data_repair_date, remark, affected_transaction_count, issue_id, issue_key,
                historical_occurrence_count, first_occurrence_date, last_occurrence_date, imported_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReplayIssueDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.txTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    /** Atomically replaces the complete active snapshot. */
    public void replaceAll(List<ReplayIssueRow> rows, LocalDateTime importedAt) {
        txTemplate.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM dii_replay_issue");
            for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
                batchInsert(rows.subList(from, Math.min(from + BATCH_SIZE, rows.size())), importedAt);
            }
        });
    }

    /** Runs a merge operation in one result-database transaction. */
    public <T> T inTransaction(Function<ReplayIssueDao, T> callback) {
        return txTemplate.execute(status -> callback.apply(this));
    }

    public ReplayIssueRow findCurrentByIssueKeyForUpdate(String issueKey) {
        List<ReplayIssueRow> rows = jdbc.query("SELECT * FROM dii_replay_issue WHERE issue_key = ? FOR UPDATE",
                this::mapRow, issueKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReplayIssueRow findCurrentByIdForUpdate(long id) {
        List<ReplayIssueRow> rows = jdbc.query("SELECT * FROM dii_replay_issue WHERE id = ? FOR UPDATE",
                this::mapRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertCurrent(ReplayIssueRow row) {
        String sql = "INSERT INTO dii_replay_issue (source_sheet,group_name,is_sandbox,row_order,domain,sequence_no,"
                + "batch_no,transaction_code,transaction_name,issue_level,registered_date,field_name,issue_description,"
                + "transaction_owner,issue_type,initial_analysis,final_solution,resolved_date,cooperation_group,resolver,"
                + "serial_no,data_repair_date,remark,affected_transaction_count,issue_id,issue_key,historical_occurrence_count,"
                + "first_occurrence_date,last_occurrence_date,imported_at,issue_status,import_date,defect_repair_date,"
                + "cooperation_person_username,cooperation_person_real_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            bindCurrent(statement, row, false);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void updateCurrent(ReplayIssueRow row) {
        String sql = "UPDATE dii_replay_issue SET source_sheet=?,group_name=?,is_sandbox=?,row_order=?,domain=?,sequence_no=?,"
                + "batch_no=?,transaction_code=?,transaction_name=?,issue_level=?,registered_date=?,field_name=?,issue_description=?,"
                + "transaction_owner=?,issue_type=?,initial_analysis=?,final_solution=?,resolved_date=?,cooperation_group=?,resolver=?,"
                + "serial_no=?,data_repair_date=?,remark=?,affected_transaction_count=?,issue_id=?,issue_key=?,historical_occurrence_count=?,"
                + "first_occurrence_date=?,last_occurrence_date=?,imported_at=?,issue_status=?,import_date=?,defect_repair_date=?,"
                + "cooperation_person_username=?,cooperation_person_real_name=? WHERE id=?";
        jdbc.update(sql, currentArgs(row, true));
    }

    public List<ReplayIssueRow> findPendingVerificationMissing(Set<String> incomingKeys) {
        String sql = "SELECT * FROM dii_replay_issue WHERE issue_status = ?"
                + (incomingKeys.isEmpty() ? "" : " AND issue_key NOT IN (" + "?,".repeat(incomingKeys.size()).replaceAll(",$", "") + ")")
                + " FOR UPDATE";
        List<Object> args = new ArrayList<>();
        args.add(ReplayIssueStatus.PENDING_VERIFICATION.displayValue());
        args.addAll(incomingKeys);
        return jdbc.query(sql, this::mapRow, args.toArray());
    }

    public void insertHistory(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                              ReplayIssueOperator operator, LocalDate importDate, String sourceSheet, Integer sourceRow,
                              String beforeSnapshot, String afterSnapshot, String incomingSnapshot) {
        JsonNode snapshot = parseSnapshot(afterSnapshot);
        jdbc.update("INSERT INTO dii_replay_issue_history (replay_issue_id,issue_key,operation_type,operation_at,"
                        + "operator_username,operator_real_name,import_date,source_sheet,source_row,before_snapshot,"
                        + "issue_status,issue_type,initial_analysis,final_solution,cooperation_person_username,cooperation_person_real_name,remark,"
                        + "after_snapshot,incoming_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                replayIssueId, issueKey, operationType, Timestamp.valueOf(operationAt),
                operator == null ? null : operator.username(), operator == null ? null : operator.realName(),
                importDate, sourceSheet, sourceRow,
                beforeSnapshot,
                text(snapshot, "issueStatus"), text(snapshot, "issueType"), text(snapshot, "initialAnalysis"), text(snapshot, "finalSolution"),
                text(snapshot, "cooperationPersonUsername"), text(snapshot, "cooperationPersonRealName"), text(snapshot, "remark"),
                afterSnapshot, incomingSnapshot);
    }

    private JsonNode parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return null;
        try { return objectMapper.readTree(snapshot); }
        catch (Exception ignored) { return null; }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public long countHistory(String issueKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_history WHERE issue_key = ?", Long.class, issueKey);
        return count == null ? 0L : count;
    }

    private ReplayIssueRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp imported = rs.getTimestamp("imported_at");
        java.sql.Date importDate = rs.getDate("import_date");
        java.sql.Date defectDate = rs.getDate("defect_repair_date");
        return new ReplayIssueRow(rs.getLong("id"), rs.getString("source_sheet"), rs.getString("group_name"),
                rs.getBoolean("is_sandbox"), rs.getInt("row_order"), rs.getString("domain"), rs.getString("sequence_no"),
                rs.getString("batch_no"), rs.getString("transaction_code"), rs.getString("transaction_name"),
                rs.getString("issue_level"), rs.getString("registered_date"), rs.getString("field_name"),
                rs.getString("issue_description"), rs.getString("transaction_owner"), rs.getString("issue_type"),
                rs.getString("initial_analysis"), rs.getString("final_solution"), rs.getString("resolved_date"),
                rs.getString("cooperation_group"), rs.getString("resolver"), rs.getString("serial_no"),
                rs.getString("data_repair_date"), rs.getString("remark"), rs.getString("affected_transaction_count"),
                rs.getString("issue_id"), rs.getString("issue_key"), rs.getString("historical_occurrence_count"),
                rs.getString("first_occurrence_date"), rs.getString("last_occurrence_date"),
                imported == null ? null : imported.toLocalDateTime(),
                ReplayIssueStatus.fromDisplayValue(rs.getString("issue_status")),
                importDate == null ? null : importDate.toLocalDate(), defectDate == null ? null : defectDate.toLocalDate(),
                rs.getString("cooperation_person_username"), rs.getString("cooperation_person_real_name"));
    }

    private void bindCurrent(PreparedStatement s, ReplayIssueRow row, boolean withId) throws SQLException {
        Object[] args = currentArgs(row, false);
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof LocalDateTime dateTime) s.setTimestamp(i + 1, Timestamp.valueOf(dateTime));
            else if (value instanceof LocalDate date) s.setDate(i + 1, java.sql.Date.valueOf(date));
            else if (value instanceof ReplayIssueStatus status) s.setString(i + 1, status.displayValue());
            else if (value instanceof Boolean bool) s.setBoolean(i + 1, bool);
            else s.setObject(i + 1, value);
        }
        if (withId) s.setLong(args.length + 1, row.id());
    }

    private Object[] currentArgs(ReplayIssueRow row, boolean withId) {
        List<Object> args = new ArrayList<>();
        args.add(row.sourceSheet()); args.add(row.groupName()); args.add(row.sandbox()); args.add(row.rowOrder());
        args.add(row.domain()); args.add(row.sequenceNo()); args.add(row.batchNo()); args.add(row.transactionCode());
        args.add(row.transactionName()); args.add(row.issueLevel()); args.add(row.registeredDate()); args.add(row.fieldName());
        args.add(row.issueDescription()); args.add(row.transactionOwner()); args.add(row.issueType()); args.add(row.initialAnalysis());
        args.add(row.finalSolution()); args.add(row.resolvedDate()); args.add(row.cooperationGroup()); args.add(row.resolver());
        args.add(row.serialNo()); args.add(row.dataRepairDate()); args.add(row.remark()); args.add(row.affectedTransactionCount());
        args.add(row.issueId()); args.add(row.issueKey()); args.add(row.historicalOccurrenceCount()); args.add(row.firstOccurrenceDate());
        args.add(row.lastOccurrenceDate()); args.add(row.importedAt()); args.add(row.issueStatus() == null ? ReplayIssueStatus.OPEN : row.issueStatus());
        args.add(row.importDate()); args.add(row.defectRepairDate()); args.add(row.cooperationPersonUsername()); args.add(row.cooperationPersonRealName());
        if (withId) args.add(row.id());
        return args.stream().map(ReplayIssueDao::jdbcValue).toArray();
    }

    private static Object jdbcValue(Object value) {
        if (value instanceof ReplayIssueStatus status) return status.displayValue();
        if (value instanceof LocalDateTime dateTime) return Timestamp.valueOf(dateTime);
        if (value instanceof LocalDate date) return java.sql.Date.valueOf(date);
        return value;
    }

    public List<Map<String, Object>> list(ReplayIssueQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM dii_replay_issue WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, query);
        sql.append(" ORDER BY group_name, is_sandbox, row_order, id LIMIT ? OFFSET ?");
        args.add(clampLimit(query.limit()));
        args.add(Math.max(query.offset(), 0));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long count(ReplayIssueQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dii_replay_issue WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, query);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public ReplayIssueFilterOptions options() {
        return new ReplayIssueFilterOptions(
                jdbc.queryForList("SELECT DISTINCT group_name FROM dii_replay_issue "
                        + "WHERE TRIM(group_name) <> '' ORDER BY group_name", String.class),
                distinctNonBlank("issue_level"),
                List.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "其他问题"),
                List.of("打开", "分析中", "延后修复", "修复待验证", "重新打开", "已修复"));
    }

    public List<ReplayIssueHistoryEntry> findHistoryByIssueId(long issueId, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return jdbc.query("SELECT * FROM dii_replay_issue_history WHERE replay_issue_id = ?"
                        + " ORDER BY operation_at DESC, id DESC LIMIT ?", (rs, rowNum) ->
                        new ReplayIssueHistoryEntry(rs.getLong("id"), rs.getObject("replay_issue_id", Long.class),
                                rs.getString("issue_key"), rs.getString("operation_type"), rs.getTimestamp("operation_at").toLocalDateTime(),
                                rs.getString("operator_username"), rs.getString("operator_real_name"),
                                ReplayIssueStatus.fromDisplayValue(rs.getString("issue_status")), rs.getString("issue_type"),
                                rs.getString("initial_analysis"), rs.getString("final_solution"),
                                rs.getString("cooperation_person_username"), rs.getString("cooperation_person_real_name"),
                                rs.getDate("import_date") == null ? null : rs.getDate("import_date").toLocalDate(),
                                rs.getString("source_sheet"), (Integer) rs.getObject("source_row"),
                                rs.getString("before_snapshot"), rs.getString("after_snapshot"), rs.getString("incoming_snapshot"),
                                rs.getString("remark")), issueId, boundedLimit);
    }

    public Map<String, Object> stats() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(DISTINCT group_name) AS group_count,
                       COALESCE(SUM(CASE WHEN is_sandbox = 1 THEN 1 ELSE 0 END), 0) AS sandbox_count,
                       COALESCE(SUM(CASE WHEN issue_status IN ('打开', '重新打开') THEN 1 ELSE 0 END), 0) AS open_total,
                       COALESCE(SUM(CASE WHEN issue_status IN ('分析中', '延后修复') THEN 1 ELSE 0 END), 0) AS processing_total,
                       COALESCE(SUM(CASE WHEN issue_status = '修复待验证' THEN 1 ELSE 0 END), 0) AS pending_verification_total,
                       COALESCE(SUM(CASE WHEN issue_status = '已修复' THEN 1 ELSE 0 END), 0) AS fixed_total,
                       MAX(imported_at) AS imported_at
                  FROM dii_replay_issue
                """);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", number(row.get("total")));
        stats.put("groupCount", number(row.get("group_count")));
        stats.put("sandboxCount", number(row.get("sandbox_count")));
        stats.put("openTotal", number(row.get("open_total")));
        stats.put("processingTotal", number(row.get("processing_total")));
        stats.put("pendingVerificationTotal", number(row.get("pending_verification_total")));
        stats.put("fixedTotal", number(row.get("fixed_total")));
        Map<String, Map<String, Long>> groupCounts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT group_name,
                       COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN issue_status IN ('打开', '重新打开') THEN 1 ELSE 0 END), 0) AS open_total,
                       COALESCE(SUM(CASE WHEN issue_status IN ('分析中', '延后修复') THEN 1 ELSE 0 END), 0) AS processing_total,
                       COALESCE(SUM(CASE WHEN issue_status = '修复待验证' THEN 1 ELSE 0 END), 0) AS pending_verification_total,
                       COALESCE(SUM(CASE WHEN issue_status = '已修复' THEN 1 ELSE 0 END), 0) AS fixed_total
                  FROM dii_replay_issue
                 WHERE group_name IS NOT NULL AND TRIM(group_name) <> ''
                 GROUP BY group_name
                 ORDER BY group_name
                """, rs -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("total", rs.getLong("total"));
            counts.put("open", rs.getLong("open_total"));
            counts.put("processing", rs.getLong("processing_total"));
            counts.put("pendingVerification", rs.getLong("pending_verification_total"));
            counts.put("fixed", rs.getLong("fixed_total"));
            groupCounts.put(rs.getString("group_name"), counts);
        });
        stats.put("groupCounts", groupCounts);
        stats.put("importedAt", asLocalDateTime(row.get("imported_at")));
        return stats;
    }

    private void batchInsert(List<ReplayIssueRow> rows, LocalDateTime importedAt) {
        Timestamp timestamp = Timestamp.valueOf(importedAt);
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ReplayIssueRow row = rows.get(index);
                statement.setString(1, row.sourceSheet());
                statement.setString(2, row.groupName());
                statement.setBoolean(3, row.sandbox());
                statement.setInt(4, row.rowOrder());
                statement.setString(5, row.domain());
                statement.setString(6, row.sequenceNo());
                statement.setString(7, row.batchNo());
                statement.setString(8, row.transactionCode());
                statement.setString(9, row.transactionName());
                statement.setString(10, row.issueLevel());
                statement.setString(11, row.registeredDate());
                statement.setString(12, row.fieldName());
                statement.setString(13, row.issueDescription());
                statement.setString(14, row.transactionOwner());
                statement.setString(15, row.issueType());
                statement.setString(16, row.initialAnalysis());
                statement.setString(17, row.finalSolution());
                statement.setString(18, row.resolvedDate());
                statement.setString(19, row.cooperationGroup());
                statement.setString(20, row.resolver());
                statement.setString(21, row.serialNo());
                statement.setString(22, row.dataRepairDate());
                statement.setString(23, row.remark());
                statement.setString(24, row.affectedTransactionCount());
                statement.setString(25, row.issueId());
                statement.setString(26, row.issueKey());
                statement.setString(27, row.historicalOccurrenceCount());
                statement.setString(28, row.firstOccurrenceDate());
                statement.setString(29, row.lastOccurrenceDate());
                statement.setTimestamp(30, timestamp);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, ReplayIssueQuery query) {
        if (hasText(query.groupName())) {
            sql.append(" AND group_name = ?");
            args.add(query.groupName().trim());
        }
        if (query.sandbox() != null) {
            sql.append(" AND is_sandbox = ?");
            args.add(query.sandbox());
        }
        if (hasText(query.issueLevel())) {
            sql.append(" AND issue_level = ?");
            args.add(query.issueLevel().trim());
        }
        if (hasText(query.issueType())) {
            sql.append(" AND issue_type = ?");
            args.add(query.issueType().trim());
        }
        if (hasText(query.issueStatus())) {
            sql.append(" AND issue_status = ?");
            args.add(query.issueStatus().trim());
        }
        if (hasText(query.transactionOwner())) {
            sql.append(" AND transaction_owner LIKE ?");
            args.add("%" + query.transactionOwner().trim() + "%");
        }
        if (hasText(query.cooperationPerson())) {
            sql.append(" AND (cooperation_person_username LIKE ? OR cooperation_person_real_name LIKE ?)");
            String person = "%" + query.cooperationPerson().trim() + "%";
            args.add(person);
            args.add(person);
        }
        if (hasText(query.keyword())) {
            sql.append(" AND (transaction_code LIKE ? OR transaction_name LIKE ? OR field_name LIKE ?"
                    + " OR issue_description LIKE ? OR serial_no LIKE ? OR issue_id LIKE ? OR issue_key LIKE ?)");
            String keyword = "%" + query.keyword().trim() + "%";
            for (int i = 0; i < 7; i++) {
                args.add(keyword);
            }
        }
    }

    private List<String> distinctNonBlank(String column) {
        return jdbc.queryForList("SELECT DISTINCT " + column + " FROM dii_replay_issue "
                + "WHERE " + column + " IS NOT NULL AND TRIM(" + column + ") <> '' ORDER BY " + column,
                String.class);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int clampLimit(int limit) {
        if (limit == 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return value instanceof LocalDateTime dateTime ? dateTime : null;
    }
}
