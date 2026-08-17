package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayIssueHistoryEntry;
import com.axonlink.ai.replay.dto.ReplayIssueGroupSummary;
import com.axonlink.ai.replay.dto.ReplayIssuePersonRanking;
import com.axonlink.ai.replay.dto.ReplayImportRound;
import com.axonlink.ai.replay.dto.ReplayIssueRoundEntry;
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
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Result-database access for the active parallel replay issue snapshot. */
@Repository
public class ReplayIssueDao {
    private static final String EMPTY_FILTER_VALUE = "空";

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
                serial_no, global_serial_no, data_repair_date, remark, affected_transaction_count, issue_id, issue_key,
                historical_occurrence_count, first_occurrence_date, last_occurrence_date, imported_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            List<Map<String, Object>> persisted = jdbc.queryForList("SELECT id,issue_key,batch_no,issue_status FROM dii_replay_issue WHERE TRIM(COALESCE(batch_no,'')) <> ''");
            for (Map<String, Object> row : persisted) {
                upsertOccurrenceBatch(((Number) row.get("id")).longValue(), String.valueOf(row.get("issue_key")),
                        String.valueOf(row.get("batch_no")), importedAt, ReplayIssueStatus.fromDisplayValue((String) row.get("issue_status")));
            }
        });
    }

    /** Runs a merge operation in one result-database transaction. */
    public <T> T inTransaction(Function<ReplayIssueDao, T> callback) {
        return txTemplate.execute(status -> callback.apply(this));
    }

    public long insertImportRound(String roundCode, LocalDateTime importedAt, ReplayIssueOperator operator, int inputRows) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_import_round (round_code,imported_at,operator_username,operator_real_name,input_rows) VALUES (?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, roundCode);
            statement.setTimestamp(2, Timestamp.valueOf(importedAt));
            statement.setString(3, operator == null ? null : operator.username());
            statement.setString(4, operator == null ? null : operator.realName());
            statement.setInt(5, inputRows);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("创建导入轮次失败");
        return key.longValue();
    }

    public void updateImportRoundStats(long roundId, int created, int updated, int ignored, int autoRepaired) {
        jdbc.update("UPDATE dii_replay_import_round SET created_rows=?,updated_rows=?,ignored_rows=?,auto_repaired_rows=? WHERE id=?",
                created, updated, ignored, autoRepaired, roundId);
    }

    public void insertIssueRound(long roundId, long issueId, String issueKey, boolean appeared,
                                 ReplayIssueStatus statusBefore, ReplayIssueStatus statusAfter, String actionType,
                                 String sourceSheet, Integer sourceRow, LocalDateTime recordedAt) {
        jdbc.update("INSERT INTO dii_replay_issue_round (round_id,replay_issue_id,issue_key,appeared,status_before,status_after,"
                        + "action_type,source_sheet,source_row,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                roundId, issueId, issueKey, appeared,
                statusBefore == null ? null : statusBefore.displayValue(), statusAfter.displayValue(), actionType,
                sourceSheet, sourceRow, Timestamp.valueOf(recordedAt));
    }

    public void upsertOccurrenceBatch(long issueId, String issueKey, String batchName,
                                      LocalDateTime occurredAt, ReplayIssueStatus status) {
        if (batchName == null || batchName.isBlank()) return;
        String normalized = batchName.trim();
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch "
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,last_status,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE issue_key=VALUES(issue_key), "
                        + "last_occurred_at=VALUES(last_occurred_at),last_status=VALUES(last_status),updated_at=VALUES(updated_at)",
                issueId, issueKey, normalized, Timestamp.valueOf(occurredAt), Timestamp.valueOf(occurredAt),
                status == null ? null : status.displayValue(), Timestamp.valueOf(occurredAt), Timestamp.valueOf(occurredAt));
    }

    public void updateLatestHistoryOccurrenceBatch(long issueId, LocalDateTime operationAt, String batchName) {
        if (batchName == null || batchName.isBlank()) return;
        jdbc.update("UPDATE dii_replay_issue_history SET occurrence_batch_name=? WHERE replay_issue_id=? AND operation_at=? AND id=(SELECT id FROM (SELECT id FROM dii_replay_issue_history WHERE replay_issue_id=? AND operation_at=? ORDER BY id DESC LIMIT 1) latest)",
                batchName.trim(), issueId, Timestamp.valueOf(operationAt), issueId, Timestamp.valueOf(operationAt));
    }

    public boolean occurrenceBatchExists(long issueId, String batchName) {
        if (batchName == null || batchName.isBlank()) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_occurrence_batch WHERE replay_issue_id=? AND batch_name=?", Integer.class, issueId, batchName.trim());
        return count != null && count > 0;
    }

    public Long findLatestIssueRoundId(long replayIssueId) {
        List<Long> ids = jdbc.query("SELECT ir.round_id FROM dii_replay_issue_round ir "
                        + "JOIN dii_replay_import_round r ON r.id=ir.round_id WHERE ir.replay_issue_id=? "
                        + "ORDER BY r.imported_at DESC,r.id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), replayIssueId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public LocalDateTime findLatestManualSaveAt(long replayIssueId) {
        List<LocalDateTime> values = jdbc.query("SELECT operation_at FROM dii_replay_issue_history WHERE replay_issue_id=? AND operation_type='人工保存' ORDER BY operation_at DESC,id DESC LIMIT 1",
                (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime(), replayIssueId);
        return values.isEmpty() ? null : values.get(0);
    }

    public List<ReplayImportRound> listImportRounds() {
        return jdbc.query("SELECT * FROM dii_replay_import_round ORDER BY imported_at DESC,id DESC", (rs, rowNum) ->
                new ReplayImportRound(rs.getLong("id"), rs.getString("round_code"),
                        rs.getTimestamp("imported_at").toLocalDateTime(), rs.getString("operator_username"),
                        rs.getString("operator_real_name"), rs.getInt("input_rows"), rs.getInt("created_rows"),
                        rs.getInt("updated_rows"), rs.getInt("ignored_rows"), rs.getInt("auto_repaired_rows")));
    }

    public List<ReplayIssueRoundEntry> findIssueRounds(long issueId) {
        return jdbc.query("SELECT ir.*,r.round_code,r.imported_at FROM dii_replay_issue_round ir "
                        + "JOIN dii_replay_import_round r ON r.id=ir.round_id WHERE ir.replay_issue_id=? "
                        + "ORDER BY r.imported_at DESC,r.id DESC", (rs, rowNum) ->
                        new ReplayIssueRoundEntry(rs.getLong("id"), rs.getLong("round_id"), rs.getString("round_code"),
                                rs.getTimestamp("imported_at").toLocalDateTime(), rs.getLong("replay_issue_id"),
                                rs.getString("issue_key"), rs.getBoolean("appeared"), status(rs.getString("status_before")),
                                status(rs.getString("status_after")), rs.getString("action_type"), rs.getString("source_sheet"),
                                (Integer) rs.getObject("source_row"), rs.getTimestamp("recorded_at").toLocalDateTime()), issueId);
    }

    public void deleteAllHistory() {
        jdbc.update("DELETE FROM dii_replay_issue_history");
    }

    public void deleteAllCurrent() {
        jdbc.update("DELETE FROM dii_replay_issue");
    }

    public ReplayIssueRow findCurrentByIssueKeyForUpdate(String issueKey) {
        List<ReplayIssueRow> rows = jdbc.query("SELECT * FROM dii_replay_issue WHERE issue_key = ? FOR UPDATE",
                this::mapRow, issueKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public GeneratedIdentitySequences findGeneratedIdentitySequences() {
        long[] maxima = new long[2];
        jdbc.query("SELECT issue_id, issue_key FROM dii_replay_issue "
                        + "WHERE issue_id LIKE 'AUTO-%' OR issue_key LIKE 'AUTO-%'",
                resultSet -> {
                    maxima[0] = Math.max(maxima[0], trailingSequence(resultSet.getString("issue_id")));
                    maxima[1] = Math.max(maxima[1], trailingSequence(resultSet.getString("issue_key")));
                });
        return new GeneratedIdentitySequences(maxima[0], maxima[1]);
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
                + "serial_no,global_serial_no,data_repair_date,remark,affected_transaction_count,issue_id,issue_key,historical_occurrence_count,"
                + "first_occurrence_date,last_occurrence_date,imported_at,issue_status,import_date,defect_repair_date,"
                + "cooperation_person_username,cooperation_person_real_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
                + "serial_no=?,global_serial_no=?,data_repair_date=?,remark=?,affected_transaction_count=?,issue_id=?,issue_key=?,historical_occurrence_count=?,"
                + "first_occurrence_date=?,last_occurrence_date=?,imported_at=?,issue_status=?,import_date=?,defect_repair_date=?,"
                + "cooperation_person_username=?,cooperation_person_real_name=? WHERE id=?";
        jdbc.update(sql, currentArgs(row, true));
    }

    public void updateCoverageRound(long issueId, String coverageRound) {
        jdbc.update("UPDATE dii_replay_issue SET coverage_round = ? WHERE id = ?", coverageRound, issueId);
    }

    public void updateLatestHistoryCoverageRound(String issueKey, LocalDateTime operationAt, String coverageRound) {
        jdbc.update("UPDATE dii_replay_issue_history SET coverage_round = ? WHERE id = "
                        + "(SELECT id FROM (SELECT id FROM dii_replay_issue_history WHERE issue_key = ? AND operation_at = ? "
                        + "ORDER BY id DESC LIMIT 1) latest)", coverageRound, issueKey, Timestamp.valueOf(operationAt));
    }

    public List<ReplayIssueRow> findAutoRepairCandidatesMissing(Set<String> incomingKeys) {
        String sql = "SELECT * FROM dii_replay_issue WHERE issue_status IN (?, ?, ?, ?, ?)"
                + (incomingKeys.isEmpty() ? "" : " AND issue_key NOT IN (" + "?,".repeat(incomingKeys.size()).replaceAll(",$", "") + ")")
                + " FOR UPDATE";
        List<Object> args = new ArrayList<>();
        args.add(ReplayIssueStatus.NEW.displayValue());
        args.add(ReplayIssueStatus.OPEN.displayValue());
        args.add(ReplayIssueStatus.REOPENED.displayValue());
        args.add(ReplayIssueStatus.DEFERRED.displayValue());
        args.add(ReplayIssueStatus.PENDING_VERIFICATION.displayValue());
        args.addAll(incomingKeys);
        return jdbc.query(sql, this::mapRow, args.toArray());
    }

    public void insertHistory(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                              ReplayIssueOperator operator, LocalDate importDate, String sourceSheet, Integer sourceRow,
                              String beforeSnapshot, String afterSnapshot, String incomingSnapshot) {
        insertHistory(replayIssueId, issueKey, operationType, operationAt, operator, importDate, null, sourceSheet, sourceRow,
                beforeSnapshot, afterSnapshot, incomingSnapshot, null, null);
    }

    public void insertHistory(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                              ReplayIssueOperator operator, LocalDate importDate, String coverageRound,
                              String sourceSheet, Integer sourceRow,
                              String beforeSnapshot, String afterSnapshot, String incomingSnapshot) {
        insertHistory(replayIssueId, issueKey, operationType, operationAt, operator, importDate, coverageRound,
                sourceSheet, sourceRow, beforeSnapshot, afterSnapshot, incomingSnapshot, null, null);
    }

    public void insertHistoryForRound(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                                      ReplayIssueOperator operator, LocalDate importDate, String coverageRound,
                                      String sourceSheet, Integer sourceRow, String beforeSnapshot,
                                      String afterSnapshot, String incomingSnapshot, Long contextRoundId) {
        insertHistoryForRound(replayIssueId, issueKey, operationType, operationAt, operator, importDate, coverageRound,
                sourceSheet, sourceRow, beforeSnapshot, afterSnapshot, incomingSnapshot, contextRoundId, null);
    }

    public void insertHistoryForRound(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                                      ReplayIssueOperator operator, LocalDate importDate, String coverageRound,
                                      String sourceSheet, Integer sourceRow, String beforeSnapshot,
                                      String afterSnapshot, String incomingSnapshot, Long contextRoundId,
                                      String occurrenceBatchName) {
        insertHistory(replayIssueId, issueKey, operationType, operationAt, operator, importDate, coverageRound,
                sourceSheet, sourceRow, beforeSnapshot, afterSnapshot, incomingSnapshot, contextRoundId, occurrenceBatchName);
    }

    private void insertHistory(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                              ReplayIssueOperator operator, LocalDate importDate, String coverageRound,
                              String sourceSheet, Integer sourceRow, String beforeSnapshot, String afterSnapshot,
                              String incomingSnapshot, Long contextRoundId, String occurrenceBatchName) {
        JsonNode snapshot = parseSnapshot(afterSnapshot);
        jdbc.update("INSERT INTO dii_replay_issue_history (replay_issue_id,issue_key,operation_type,operation_at,"
                        + "operator_username,operator_real_name,import_date,coverage_round,context_round_id,occurrence_batch_name,source_sheet,source_row,before_snapshot,"
                        + "issue_status,issue_type,initial_analysis,final_solution,cooperation_person_username,cooperation_person_real_name,remark,"
                        + "after_snapshot,incoming_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                replayIssueId, issueKey, operationType, Timestamp.valueOf(operationAt),
                operator == null ? null : operator.username(), operator == null ? null : operator.realName(),
                importDate, coverageRound, contextRoundId, occurrenceBatchName, sourceSheet, sourceRow,
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
                rs.getBoolean("is_sandbox"), rs.getInt("row_order"), rs.getString("group_name"), rs.getString("sequence_no"),
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
                rs.getString("cooperation_person_username"), rs.getString("cooperation_person_real_name"),
                rs.getString("global_serial_no"));
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
        args.add(row.serialNo()); args.add(row.globalSerialNo()); args.add(row.dataRepairDate()); args.add(row.remark()); args.add(row.affectedTransactionCount());
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

    private static long trailingSequence(String value) {
        if (value == null || !value.startsWith("AUTO-")) {
            return 0L;
        }
        int separator = value.lastIndexOf('-');
        if (separator < 0 || separator == value.length() - 1) {
            return 0L;
        }
        try {
            return Long.parseLong(value.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public record GeneratedIdentitySequences(long issueIdSequence, long issueKeySequence) {
    }

    public List<Map<String, Object>> list(ReplayIssueQuery query) {
        return list(query, true);
    }

    public List<Map<String, Object>> listForExport(ReplayIssueQuery query) {
        return list(query, false);
    }

    private List<Map<String, Object>> list(ReplayIssueQuery query, boolean paged) {
        StringBuilder sql = new StringBuilder("SELECT i.*, tp.developer AS matched_developer, tp.bank_owner AS matched_bank_owner, "
                + "(SELECT GROUP_CONCAT(ob.batch_name ORDER BY ob.last_occurred_at DESC,ob.id DESC SEPARATOR '、') FROM dii_replay_issue_occurrence_batch ob "
                + "WHERE ob.replay_issue_id=i.id) AS occurrence_rounds "
                + "FROM dii_replay_issue i LEFT JOIN dii_replay_transaction_person tp "
                + "ON tp.old_transaction_code = i.transaction_code WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, query);
        sql.append(" ORDER BY i.group_name, i.is_sandbox, i.row_order, i.id");
        if (paged) {
            sql.append(" LIMIT ? OFFSET ?");
            args.add(clampLimit(query.limit()));
            args.add(Math.max(query.offset(), 0));
        }
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(ReplayIssueDao::normalizeDomain)
                .toList();
    }

    private static Map<String, Object> normalizeDomain(Map<String, Object> row) {
        Object groupName = row.get("group_name");
        if (groupName != null && !groupName.toString().isBlank()) {
            row.put("domain", groupName.toString());
        }
        return row;
    }

    public long count(ReplayIssueQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dii_replay_issue i "
                + "LEFT JOIN dii_replay_transaction_person tp ON tp.old_transaction_code = i.transaction_code WHERE 1=1");
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
                List.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "参数问题", "平台问题", "规则差异问题", "合理差异", "其他问题"),
                List.of("新建", "打开", "延后修复", "修复待验证", "重新打开", "已修复"), coverageRounds());
    }

    public List<String> coverageRounds() {
        return jdbc.queryForList("SELECT batch_name FROM (SELECT DISTINCT TRIM(batch_name) AS batch_name FROM dii_replay_issue_occurrence_batch WHERE TRIM(COALESCE(batch_name,''))<>'' UNION SELECT DISTINCT TRIM(batch_no) FROM dii_replay_issue WHERE TRIM(COALESCE(batch_no,''))<>'' ) batches ORDER BY batch_name ASC", String.class);
    }

    public List<String> headerFilterValues(String field, ReplayIssueQuery query, String keyword) {
        if ("developer".equals(field) || "bankOwner".equals(field)) {
            return splitHeaderFilterValues("developer".equals(field) ? "tp.developer" : "tp.bank_owner", query, keyword);
        }
        String expression = switch (field) {
            case "transactionCode" -> "i.transaction_code";
            case "issueLevel" -> "i.issue_level";
            case "issueStatus" -> "i.issue_status";
            case "issueType" -> "i.issue_type";
            case "cooperationPerson" -> "CONCAT(COALESCE(i.cooperation_person_real_name,''),'(',COALESCE(i.cooperation_person_username,''),')')";
            case "occurrenceBatch" -> "ob.batch_name";
            default -> throw new IllegalArgumentException("不支持的表头筛选字段");
        };
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(expression).append(" AS filter_value FROM dii_replay_issue i ")
                .append("LEFT JOIN dii_replay_transaction_person tp ON tp.old_transaction_code=i.transaction_code ")
                .append(field.equals("occurrenceBatch") ? "LEFT JOIN dii_replay_issue_occurrence_batch ob ON ob.replay_issue_id=i.id " : "")
                .append("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, query);
        sql.append(" AND 1=1");
        if (hasText(keyword)) {
            if (EMPTY_FILTER_VALUE.equals(keyword.trim())) {
                sql.append(" AND TRIM(COALESCE(").append(expression).append(",''))=''");
            } else {
                sql.append(" AND ").append(expression).append(" LIKE ?");
                args.add("%" + keyword.trim() + "%");
            }
        }
        sql.append(" ORDER BY filter_value ASC LIMIT 500");
        return jdbc.queryForList(sql.toString(), String.class, args.toArray()).stream()
                .map(value -> hasText(value) ? value.trim() : EMPTY_FILTER_VALUE)
                .distinct()
                .filter(value -> !hasText(keyword) || (EMPTY_FILTER_VALUE.equals(keyword.trim()) ? EMPTY_FILTER_VALUE.equals(value) : value.contains(keyword.trim())))
                .sorted((a, b) -> EMPTY_FILTER_VALUE.equals(a) ? (EMPTY_FILTER_VALUE.equals(b) ? 0 : -1) : (EMPTY_FILTER_VALUE.equals(b) ? 1 : a.compareTo(b)))
                .limit(500).collect(Collectors.toList());
    }

    public List<ReplayIssueHistoryEntry> findHistoryByIssueId(long issueId, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return jdbc.query("SELECT h.*, COALESCE(NULLIF(h.occurrence_batch_name,''), NULLIF(i.batch_no,'')) AS resolved_occurrence_batch_name "
                        + "FROM dii_replay_issue_history h LEFT JOIN dii_replay_issue i ON i.id=h.replay_issue_id WHERE h.replay_issue_id = ?"
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
                                rs.getString("remark"), (Long) rs.getObject("context_round_id"), rs.getString("resolved_occurrence_batch_name")), issueId, boundedLimit);
    }

    public List<String> occurrenceBatchNames(long issueId) {
        return jdbc.queryForList("SELECT batch_name FROM dii_replay_issue_occurrence_batch WHERE replay_issue_id=? "
                + "UNION SELECT TRIM(batch_no) FROM dii_replay_issue WHERE id=? AND TRIM(COALESCE(batch_no,''))<>'' "
                + "ORDER BY batch_name DESC", String.class, issueId, issueId);
    }


    public Map<String, Object> stats() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(DISTINCT group_name) AS group_count,
                       COALESCE(SUM(CASE WHEN is_sandbox = 1 THEN 1 ELSE 0 END), 0) AS sandbox_count,
                       COALESCE(SUM(CASE WHEN issue_status = '新建' THEN 1 ELSE 0 END), 0) AS new_total,
                       COALESCE(SUM(CASE WHEN issue_status = '打开' THEN 1 ELSE 0 END), 0) AS open_total,
                       COALESCE(SUM(CASE WHEN issue_status = '重新打开' THEN 1 ELSE 0 END), 0) AS reopened_total,
                       COALESCE(SUM(CASE WHEN issue_status = '延后修复' THEN 1 ELSE 0 END), 0) AS deferred_total,
                       COALESCE(SUM(CASE WHEN issue_status = '修复待验证' THEN 1 ELSE 0 END), 0) AS pending_verification_total,
                       COALESCE(SUM(CASE WHEN issue_status = '已修复' THEN 1 ELSE 0 END), 0) AS fixed_total,
                       MAX(imported_at) AS imported_at
                  FROM dii_replay_issue
                """);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", number(row.get("total")));
        stats.put("groupCount", number(row.get("group_count")));
        stats.put("sandboxCount", number(row.get("sandbox_count")));
        stats.put("newTotal", number(row.get("new_total")));
        stats.put("openTotal", number(row.get("open_total")));
        stats.put("reopenedTotal", number(row.get("reopened_total")));
        stats.put("deferredTotal", number(row.get("deferred_total")));
        stats.put("processingTotal", number(row.get("deferred_total")));
        stats.put("pendingVerificationTotal", number(row.get("pending_verification_total")));
        stats.put("fixedTotal", number(row.get("fixed_total")));
        Map<String, Map<String, Long>> groupCounts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT group_name,
                       COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN issue_status = '新建' THEN 1 ELSE 0 END), 0) AS new_count,
                       COALESCE(SUM(CASE WHEN issue_status = '打开' THEN 1 ELSE 0 END), 0) AS open_total,
                       COALESCE(SUM(CASE WHEN issue_status = '重新打开' THEN 1 ELSE 0 END), 0) AS reopened_total,
                       COALESCE(SUM(CASE WHEN issue_status = '延后修复' THEN 1 ELSE 0 END), 0) AS deferred_total,
                       COALESCE(SUM(CASE WHEN issue_status = '修复待验证' THEN 1 ELSE 0 END), 0) AS pending_verification_total,
                       COALESCE(SUM(CASE WHEN issue_status = '已修复' THEN 1 ELSE 0 END), 0) AS fixed_total
                  FROM dii_replay_issue
                 WHERE group_name IS NOT NULL AND TRIM(group_name) <> ''
                 GROUP BY group_name
                 ORDER BY group_name
                """, rs -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("total", rs.getLong("total"));
            counts.put("new", rs.getLong("new_count"));
            counts.put("open", rs.getLong("open_total"));
            counts.put("reopened", rs.getLong("reopened_total"));
            counts.put("deferred", rs.getLong("deferred_total"));
            counts.put("pendingVerification", rs.getLong("pending_verification_total"));
            counts.put("fixed", rs.getLong("fixed_total"));
            groupCounts.put(rs.getString("group_name"), counts);
        });
        stats.put("groupCounts", groupCounts);
        stats.put("importedAt", asLocalDateTime(row.get("imported_at")));
        return stats;
    }

    public List<ReplayIssueGroupSummary> groupIssueSummaries() {
        return jdbc.query("""
                SELECT i.group_name,
                       SUM(CASE WHEN i.issue_status = '新建' THEN 1 ELSE 0 END) AS new_count,
                       SUM(CASE WHEN i.issue_status = '打开' THEN 1 ELSE 0 END) AS open_count,
                       SUM(CASE WHEN i.issue_status = '延后修复' THEN 1 ELSE 0 END) AS deferred_count,
                       SUM(CASE WHEN i.issue_status = '重新打开' THEN 1 ELSE 0 END) AS reopened_count,
                       SUM(CASE WHEN i.issue_status = '修复待验证' THEN 1 ELSE 0 END) AS pending_verification_count,
                       COUNT(*) AS total_count
                  FROM dii_replay_issue i
                 WHERE i.issue_status <> '已修复'
                   AND i.group_name IS NOT NULL
                   AND TRIM(i.group_name) <> ''
                 GROUP BY i.group_name
                 ORDER BY i.group_name
                """, (rs, rowNum) -> new ReplayIssueGroupSummary(
                rs.getString("group_name"), rs.getLong("new_count"), rs.getLong("open_count"), rs.getLong("deferred_count"),
                rs.getLong("reopened_count"), rs.getLong("pending_verification_count"),
                rs.getLong("total_count")));
    }

    public List<ReplayIssuePersonRanking> personIssueRankings() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.group_name,
                       COALESCE(NULLIF(TRIM(p.developer), ''), '未匹配负责人') AS developer,
                       SUM(CASE WHEN i.issue_status = '新建' THEN 1 ELSE 0 END) AS new_count,
                       SUM(CASE WHEN i.issue_status = '打开' THEN 1 ELSE 0 END) AS open_count,
                       SUM(CASE WHEN i.issue_status = '延后修复' THEN 1 ELSE 0 END) AS deferred_count,
                       SUM(CASE WHEN i.issue_status = '重新打开' THEN 1 ELSE 0 END) AS reopened_count,
                       SUM(CASE WHEN i.issue_status = '修复待验证' THEN 1 ELSE 0 END) AS pending_verification_count,
                       COUNT(*) AS total_count
                  FROM dii_replay_issue i
                  LEFT JOIN dii_replay_transaction_person p ON i.transaction_code = p.old_transaction_code
                 WHERE i.issue_status <> '已修复'
                   AND i.group_name IS NOT NULL
                   AND TRIM(i.group_name) <> ''
                 GROUP BY i.group_name, COALESCE(NULLIF(TRIM(p.developer), ''), '未匹配负责人')
                 ORDER BY i.group_name, total_count DESC, developer
                """);
        List<ReplayIssuePersonRanking> result = new ArrayList<>(rows.size());
        String previousGroup = null;
        int rank = 0;
        for (Map<String, Object> row : rows) {
            String groupName = String.valueOf(row.get("group_name"));
            rank = groupName.equals(previousGroup) ? rank + 1 : 1;
            previousGroup = groupName;
            result.add(new ReplayIssuePersonRanking(rank, groupName, String.valueOf(row.get("developer")),
                    number(row.get("new_count")), number(row.get("open_count")), number(row.get("deferred_count")),
                    number(row.get("reopened_count")), number(row.get("pending_verification_count")),
                    number(row.get("total_count"))));
        }
        return result;
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
                statement.setString(22, row.globalSerialNo());
                statement.setString(23, row.dataRepairDate());
                statement.setString(24, row.remark());
                statement.setString(25, row.affectedTransactionCount());
                statement.setString(26, row.issueId());
                statement.setString(27, row.issueKey());
                statement.setString(28, row.historicalOccurrenceCount());
                statement.setString(29, row.firstOccurrenceDate());
                statement.setString(30, row.lastOccurrenceDate());
                statement.setTimestamp(31, timestamp);
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
        if (hasText(query.developer())) {
            sql.append(" AND tp.developer LIKE ?");
            args.add("%" + query.developer().trim() + "%");
        }
        if (hasText(query.bankOwner())) {
            sql.append(" AND tp.bank_owner LIKE ?");
            args.add("%" + query.bankOwner().trim() + "%");
        }
        if (hasText(query.cooperationPerson())) {
            sql.append(" AND (cooperation_person_username LIKE ? OR cooperation_person_real_name LIKE ?)");
            String person = "%" + query.cooperationPerson().trim() + "%";
            args.add(person);
            args.add(person);
        }
        appendIn(sql, args, "i.transaction_code", query.transactionCodes());
        appendIn(sql, args, "i.issue_level", query.issueLevels());
        appendSplitAny(sql, args, "tp.developer", query.developers());
        appendSplitAny(sql, args, "tp.bank_owner", query.bankOwners());
        appendIn(sql, args, "i.issue_status", query.issueStatuses());
        appendIn(sql, args, "i.issue_type", query.issueTypes());
        if (query.cooperationPersons() != null && !query.cooperationPersons().isEmpty()) {
            sql.append(" AND CONCAT(COALESCE(i.cooperation_person_real_name,''),'(',COALESCE(i.cooperation_person_username,''),')') IN (")
                    .append("?,".repeat(query.cooperationPersons().size()).replaceAll(",$", "")).append(")");
            args.addAll(query.cooperationPersons());
        }
        appendOccurrenceBatches(sql, args, query.occurrenceBatches());
        if (hasText(query.serialNo())) {
            sql.append(" AND serial_no LIKE ?");
            args.add("%" + query.serialNo().trim() + "%");
        }
        if (hasText(query.globalSerialNo())) {
            sql.append(" AND global_serial_no LIKE ?");
            args.add("%" + query.globalSerialNo().trim() + "%");
        }
        if (hasText(query.defectRepairDate())) {
            sql.append(" AND defect_repair_date = ?");
            args.add(query.defectRepairDate().trim());
        }
        if (hasText(query.coverageRound())) {
            sql.append(" AND EXISTS (SELECT 1 FROM dii_replay_issue_occurrence_batch filter_ob "
                    + "WHERE filter_ob.replay_issue_id=i.id AND filter_ob.batch_name=?)");
            args.add(query.coverageRound().trim());
        }
        if (hasText(query.keyword())) {
            sql.append(" AND (i.transaction_code LIKE ? OR i.transaction_name LIKE ? OR i.field_name LIKE ?"
                    + " OR i.issue_description LIKE ? OR i.serial_no LIKE ? OR i.issue_id LIKE ? OR i.issue_key LIKE ?"
                    + " OR tp.developer LIKE ? OR tp.bank_owner LIKE ?)");
            String keyword = "%" + query.keyword().trim() + "%";
            for (int i = 0; i < 9; i++) {
                args.add(keyword);
            }
        }
    }

    private static void appendIn(StringBuilder sql, List<Object> args, String column, List<String> values) {
        if (values == null || values.isEmpty()) return;
        List<String> normalized = values.stream().filter(ReplayIssueDao::hasText).map(String::trim).distinct().collect(Collectors.toCollection(ArrayList::new));
        if (normalized.isEmpty()) return;
        boolean empty = normalized.removeIf(ReplayIssueDao::isEmptyFilterValue);
        sql.append(" AND (");
        boolean wrote = false;
        if (!normalized.isEmpty()) { sql.append(column).append(" IN (").append("?,".repeat(normalized.size()).replaceAll(",$", "")).append(")"); args.addAll(normalized); wrote = true; }
        if (empty) { if (wrote) sql.append(" OR "); sql.append(column).append(" IS NULL OR TRIM(").append(column).append(")=''"); }
        sql.append(")");
    }

    private static void appendSplitAny(StringBuilder sql, List<Object> args, String column, List<String> values) {
        if (values == null || values.isEmpty()) return;
        List<String> normalized = values.stream().filter(ReplayIssueDao::hasText).map(String::trim).distinct().collect(Collectors.toCollection(ArrayList::new));
        if (normalized.isEmpty()) return;
        boolean empty = normalized.removeIf(ReplayIssueDao::isEmptyFilterValue);
        sql.append(" AND (");
        boolean wrote = false;
        if (empty) { sql.append(column).append(" IS NULL OR TRIM(").append(column).append(")=''"); wrote = true; }
        for (int i = 0; i < normalized.size(); i++) {
            if (wrote || i > 0) sql.append(" OR ");
            sql.append("CONCAT('、',COALESCE(").append(column).append(",''),'、') LIKE ?");
            args.add("%、" + normalized.get(i) + "、%");
        }
        sql.append(")");
    }

    private static void appendOccurrenceBatches(StringBuilder sql, List<Object> args, List<String> values) {
        if (values == null || values.isEmpty()) return;
        List<String> normalized = values.stream().filter(ReplayIssueDao::hasText).map(String::trim).distinct().collect(Collectors.toCollection(ArrayList::new));
        boolean empty = normalized.removeIf(ReplayIssueDao::isEmptyFilterValue);
        if (normalized.isEmpty() && !empty) return;
        sql.append(" AND (");
        boolean wrote = false;
        if (!normalized.isEmpty()) { sql.append("EXISTS (SELECT 1 FROM dii_replay_issue_occurrence_batch ob_filter WHERE ob_filter.replay_issue_id=i.id AND ob_filter.batch_name IN (").append("?,".repeat(normalized.size()).replaceAll(",$", "")).append("))"); args.addAll(normalized); wrote = true; }
        if (empty) { if (wrote) sql.append(" OR "); sql.append("NOT EXISTS (SELECT 1 FROM dii_replay_issue_occurrence_batch ob_empty WHERE ob_empty.replay_issue_id=i.id AND TRIM(COALESCE(ob_empty.batch_name,''))<>'' )"); }
        sql.append(")");
    }

    private List<String> splitHeaderFilterValues(String column, ReplayIssueQuery query, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(column).append(" AS filter_value FROM dii_replay_issue i ")
                .append("LEFT JOIN dii_replay_transaction_person tp ON tp.old_transaction_code=i.transaction_code WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, query);
        sql.append(" AND 1=1");
        String normalizedKeyword = hasText(keyword) ? keyword.trim() : null;
        return jdbc.queryForList(sql.toString(), String.class, args.toArray()).stream()
                .flatMap(value -> value == null || value.isBlank() ? java.util.stream.Stream.of(EMPTY_FILTER_VALUE) : List.of(value.split("、")).stream())
                .map(String::trim)
                .map(value -> hasText(value) ? value : EMPTY_FILTER_VALUE)
                .filter(value -> normalizedKeyword == null || (EMPTY_FILTER_VALUE.equals(normalizedKeyword) ? EMPTY_FILTER_VALUE.equals(value) : value.contains(normalizedKeyword)))
                .distinct()
                .sorted((a, b) -> EMPTY_FILTER_VALUE.equals(a) ? (EMPTY_FILTER_VALUE.equals(b) ? 0 : -1) : (EMPTY_FILTER_VALUE.equals(b) ? 1 : a.compareTo(b)))
                .limit(500)
                .collect(Collectors.toList());
    }

    private List<String> distinctNonBlank(String column) {
        return jdbc.queryForList("SELECT DISTINCT " + column + " FROM dii_replay_issue "
                + "WHERE " + column + " IS NOT NULL AND TRIM(" + column + ") <> '' ORDER BY " + column,
                String.class);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isEmptyFilterValue(String value) { return EMPTY_FILTER_VALUE.equals(value); }

    private static ReplayIssueStatus status(String value) {
        return value == null || value.isBlank() ? null : ReplayIssueStatus.fromDisplayValue(value);
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
