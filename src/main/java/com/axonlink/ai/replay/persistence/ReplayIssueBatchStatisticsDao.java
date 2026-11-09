package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Captures batch-owned issue statistics without reading today's values for historical reports. */
public final class ReplayIssueBatchStatisticsDao {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REQUIRED_KEYS = List.of("issueId", "groupName", "sandbox", "issueType",
            "issueLevel", "fieldName", "issueStatus", "affectedTransactionCount", "reopenedAfterFixed",
            "reviewStatus", "transactionCode", "defectRepairDate");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ReplayIssueBatchStatisticsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public void beginBatch(String batchNo, LocalDateTime at) {
        String batch = batch(batchNo);
        String family = family(batch);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE dii_replay_issue_statistics_batch SET status='FROZEN',active_family=NULL,updated_at=? "
                    + "WHERE family=? AND status='OPEN' AND batch_no<>?", at, family, batch);
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_statistics_batch WHERE batch_no=?",
                    Integer.class, batch);
            if (existing != null && existing > 0) {
                jdbc.update("UPDATE dii_replay_issue_statistics_batch SET family=?,status='OPEN',active_family=?,updated_at=? "
                        + "WHERE batch_no=?", family, family, at, batch);
            } else {
                jdbc.update("INSERT INTO dii_replay_issue_statistics_batch"
                        + "(batch_no,family,status,active_family,created_at,updated_at) VALUES (?,?,'OPEN',?,?,?)",
                        batch, family, family, at, at);
            }
        });
    }

    /** Used by import and manual-history hooks; frozen batches are never refreshed here. */
    public void captureCurrent(long issueId, String batchNo, LocalDateTime at) {
        if (batchNo == null || batchNo.isBlank()) return;
        String batch = batchNo.trim();
        transaction.executeWithoutResult(status -> {
            List<String> states = jdbc.query("SELECT status FROM dii_replay_issue_statistics_batch WHERE batch_no=? FOR UPDATE",
                    (rs, rowNum) -> rs.getString("status"), batch);
            if (states.size() != 1 || !"OPEN".equals(states.get(0))) return;
            List<String> current = jdbc.query("""
                    SELECT i.id,i.group_name,i.is_sandbox,i.issue_type,i.issue_level,i.field_name,i.issue_status,
                           i.affected_transaction_count,i.review_status,i.transaction_code,i.defect_repair_date,
                           CASE WHEN EXISTS (
                               SELECT 1 FROM dii_replay_issue_history h
                                WHERE h.replay_issue_id=i.id AND h.operation_type='已修复问题重新新建'
                           ) THEN 1 ELSE 0 END AS reopened_after_fixed
                      FROM dii_replay_issue i
                     WHERE i.id=? AND EXISTS (
                         SELECT 1 FROM dii_replay_issue_statistics_batch b
                          WHERE b.batch_no=? AND b.status='OPEN'
                     )
                    """, (rs, rowNum) -> encodeCurrent(rs), issueId, batch);
            if (current.isEmpty()) return;
            jdbc.update("UPDATE dii_replay_issue_occurrence_batch SET statistics_snapshot=?,statistics_recorded_at=? "
                            + "WHERE replay_issue_id=? AND batch_name=? AND EXISTS ("
                            + "SELECT 1 FROM dii_replay_issue_statistics_batch b WHERE b.batch_no=? AND b.status='OPEN')",
                    current.get(0), at, issueId, batch, batch);
        });
    }

    public List<ReplayDailyIssueStatisticRow> find(String batchNo) {
        String batch = batch(batchNo);
        List<BatchState> metadata = jdbc.query("SELECT family,status,active_family FROM dii_replay_issue_statistics_batch "
                        + "WHERE batch_no=?", (rs, rowNum) -> new BatchState(rs.getString("family"),
                        rs.getString("status"), rs.getString("active_family")), batch);
        if (metadata.size() != 1 || !validState(batch, metadata.get(0))) throw incomplete(batch);
        return jdbc.query("SELECT replay_issue_id,statistics_snapshot,statistics_recorded_at "
                        + "FROM dii_replay_issue_occurrence_batch WHERE batch_name=? ORDER BY replay_issue_id",
                (rs, rowNum) -> {
                    if (rs.getTimestamp("statistics_recorded_at") == null) throw incomplete(batch);
                    return decode(rs.getString("statistics_snapshot"), rs.getLong("replay_issue_id"), batch);
                }, batch);
    }

    private static boolean validState(String batch, BatchState state) {
        String family;
        try { family = family(batch); }
        catch (IllegalArgumentException exception) { return false; }
        if (!family.equals(state.family())) return false;
        return ("OPEN".equals(state.status()) && family.equals(state.activeFamily()))
                || ("FROZEN".equals(state.status()) && state.activeFamily() == null);
    }

    private static String encodeCurrent(ResultSet row) throws SQLException {
        ObjectNode object = JSON.createObjectNode();
        object.put("issueId", row.getLong("id"));
        put(object, "groupName", row.getString("group_name"));
        object.put("sandbox", row.getBoolean("is_sandbox"));
        put(object, "issueType", row.getString("issue_type"));
        put(object, "issueLevel", row.getString("issue_level"));
        put(object, "fieldName", row.getString("field_name"));
        put(object, "issueStatus", row.getString("issue_status"));
        putAffectedCount(object, row.getString("affected_transaction_count"));
        object.put("reopenedAfterFixed", row.getBoolean("reopened_after_fixed"));
        ReplayIssueReviewStatus review = ReplayIssueReviewStatus.fromValue(row.getString("review_status"));
        put(object, "reviewStatus", review == null ? null : review.name());
        put(object, "transactionCode", row.getString("transaction_code"));
        Date repaired = row.getDate("defect_repair_date");
        put(object, "defectRepairDate", repaired == null ? null : repaired.toLocalDate().toString());
        return object.toString();
    }

    private static void put(ObjectNode object, String key, String value) {
        if (value == null) object.putNull(key);
        else object.put(key, value);
    }

    private static ReplayDailyIssueStatisticRow decode(String snapshot, long expectedId, String batch) {
        try {
            if (snapshot == null || snapshot.isBlank()) throw incomplete(batch);
            JsonNode node = JSON.readTree(snapshot);
            // H2's JSON JDBC setter returns the bound JSON document as a quoted JSON string.
            if (node != null && node.isTextual()) node = JSON.readTree(node.textValue());
            if (node == null || !node.isObject()) throw incomplete(batch);
            for (String key : REQUIRED_KEYS) if (!node.has(key)) throw incomplete(batch);
            long issueId = integer(node, "issueId", batch);
            if (issueId != expectedId || issueId <= 0) throw incomplete(batch);
            long affected = integer(node, "affectedTransactionCount", batch);
            if (affected < 0) throw incomplete(batch);
            String code = text(node, "transactionCode", batch);
            String level = text(node, "issueLevel", batch);
            if (code == null || code.isBlank() || level == null
                    || !("交易级".equals(level.trim()) || "字段级".equals(level.trim()))) {
                throw incomplete(batch);
            }
            String review = text(node, "reviewStatus", batch);
            ReplayIssueReviewStatus reviewStatus = ReplayIssueReviewStatus.fromValue(review);
            if (review != null && reviewStatus == null) throw incomplete(batch);
            String repaired = text(node, "defectRepairDate", batch);
            LocalDate repairDate = repaired == null ? null : LocalDate.parse(repaired);
            return new ReplayDailyIssueStatisticRow(issueId, text(node, "groupName", batch),
                    bool(node, "sandbox", batch), text(node, "issueType", batch), level.trim(),
                    text(node, "fieldName", batch), text(node, "issueStatus", batch), affected,
                    bool(node, "reopenedAfterFixed", batch), reviewStatus, code.trim(), repairDate);
        } catch (ReplayIssueBatchStatisticsIncompleteException exception) {
            throw exception;
        } catch (Exception exception) {
            throw incomplete(batch);
        }
    }

    private static long integer(JsonNode node, String key, String batch) {
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw incomplete(batch);
        return value.longValue();
    }

    private static boolean bool(JsonNode node, String key, String batch) {
        JsonNode value = node.get(key);
        if (!value.isBoolean()) throw incomplete(batch);
        return value.booleanValue();
    }

    private static String text(JsonNode node, String key, String batch) {
        JsonNode value = node.get(key);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw incomplete(batch);
        return value.textValue();
    }

    private static void putAffectedCount(ObjectNode object, String raw) {
        if (raw == null || raw.isBlank()) {
            object.put("affectedTransactionCount", 0L);
            return;
        }
        try {
            long count = Long.parseLong(raw.trim());
            if (count >= 0) object.put("affectedTransactionCount", count);
            else object.put("affectedTransactionCount", raw);
        } catch (NumberFormatException exception) {
            object.put("affectedTransactionCount", raw);
        }
    }

    private static String batch(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) throw new IllegalArgumentException("批次号不能为空");
        return batchNo.trim();
    }

    private static String family(String batch) {
        if (batch.startsWith("RPT")) return "RPT";
        if (batch.startsWith("DZ")) return "DZ";
        throw new IllegalArgumentException("批次号必须以RPT或DZ开头");
    }

    private static ReplayIssueBatchStatisticsIncompleteException incomplete(String batch) {
        return new ReplayIssueBatchStatisticsIncompleteException(batch);
    }

    private record BatchState(String family, String status, String activeFamily) {}
}
