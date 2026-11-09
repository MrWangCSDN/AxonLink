package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayBatchOriginalIssueQuantity;
import com.axonlink.ai.replay.dto.ReplayBatchOriginalIssueQuantity.Presence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads batch facts without reconstructing historical quantities from another batch's current row. */
final class ReplayBatchOriginalIssueQuantityReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    ReplayBatchOriginalIssueQuantityReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ReplayBatchOriginalIssueQuantity> find(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) throw new IllegalArgumentException("批次号不能为空");
        String batch = batchNo.trim();
        Map<String, ReplayBatchOriginalIssueQuantity> quantities = new LinkedHashMap<>();
        // Select the latest observation before checking appeared; an absence supersedes an older appearance.
        jdbc.query("""
                SELECT ir.replay_issue_id,ir.issue_key,ir.appeared,ir.incoming_snapshot
                  FROM dii_replay_issue_round ir
                  LEFT JOIN dii_replay_import_round r ON r.id=ir.round_id
                 WHERE TRIM(ir.batch_name)=?
                 ORDER BY r.imported_at DESC,ir.round_id DESC,ir.id DESC
                """, rs -> {
            String key = rs.getString("issue_key");
            if (quantities.containsKey(key)) return;
            long id = rs.getLong("replay_issue_id");
            quantities.put(key, rs.getBoolean("appeared")
                    ? incoming(rs.getString("incoming_snapshot"), id, key, batch)
                    : new ReplayBatchOriginalIssueQuantity(id, key, Presence.ABSENT, null, null, null, 0L));
        }, batch);

        // A V88 snapshot may preserve quantities even when older import rounds have no raw record.
        jdbc.query("""
                SELECT replay_issue_id,issue_key,statistics_snapshot,statistics_recorded_at
                  FROM dii_replay_issue_occurrence_batch
                 WHERE TRIM(batch_name)=?
                 ORDER BY statistics_recorded_at DESC,id DESC
                """, rs -> {
            String key = rs.getString("issue_key");
            if (quantities.containsKey(key)) return;
            String snapshot = rs.getString("statistics_snapshot");
            if (snapshot == null || snapshot.isBlank()) return;
            long id = rs.getLong("replay_issue_id");
            quantities.put(key, rs.getTimestamp("statistics_recorded_at") == null
                    ? unknown(id, key) : statistics(snapshot, id, key));
        }, batch);
        return List.copyOf(quantities.values());
    }

    private static ReplayBatchOriginalIssueQuantity incoming(String snapshot, long id, String key, String batch) {
        try {
            JsonNode node = document(snapshot);
            if (!key.equals(text(node, "issueKey")) || !batch.equals(trim(text(node, "batchNo")))) {
                return unknown(id, key);
            }
            JsonNode snapshotId = node.get("id");
            if (snapshotId != null && !snapshotId.isNull()
                    && (!snapshotId.isIntegralNumber() || !snapshotId.canConvertToLong()
                    || snapshotId.longValue() != id)) return unknown(id, key);
            return quantity(node, id, key, true);
        } catch (RuntimeException exception) {
            return unknown(id, key);
        }
    }

    private static ReplayBatchOriginalIssueQuantity statistics(String snapshot, long id, String key) {
        try {
            JsonNode node = document(snapshot);
            JsonNode snapshotId = node.get("issueId");
            if (snapshotId == null || !snapshotId.isIntegralNumber() || !snapshotId.canConvertToLong()
                    || snapshotId.longValue() != id) return unknown(id, key);
            return quantity(node, id, key, false);
        } catch (RuntimeException exception) {
            return unknown(id, key);
        }
    }

    private static ReplayBatchOriginalIssueQuantity quantity(JsonNode node, long id, String key, boolean allowTextCount) {
        String code = trim(text(node, "transactionCode"));
        String level = trim(text(node, "issueLevel"));
        String field = text(node, "fieldName");
        Long count = count(node.get("affectedTransactionCount"), allowTextCount);
        if (code.isEmpty() || !("交易级".equals(level) || "字段级".equals(level)) || count == null) {
            return unknown(id, key);
        }
        return new ReplayBatchOriginalIssueQuantity(id, key, Presence.PRESENT, code, level, field, count);
    }

    private static JsonNode document(String snapshot) {
        try {
            if (snapshot == null || snapshot.isBlank()) throw new IllegalArgumentException("缺少原始数量快照");
            JsonNode node = JSON.readTree(snapshot);
            if (node != null && node.isTextual()) node = JSON.readTree(node.textValue());
            if (node == null || !node.isObject()) throw new IllegalArgumentException("原始数量快照格式不正确");
            return node;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("原始数量快照格式不正确", exception);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) throw new IllegalArgumentException("原始数量快照缺少身份字段");
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("原始数量快照身份字段格式不正确");
        return value.textValue();
    }

    private static Long count(JsonNode value, boolean allowTextCount) {
        if (value == null || value.isNull()) return null;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue() >= 0 ? value.longValue() : null;
        }
        if (!allowTextCount || !value.isTextual()) return null;
        String raw = value.textValue().trim();
        if (!raw.matches("[0-9]+")) return null;
        try { return Long.parseLong(raw); }
        catch (NumberFormatException exception) { return null; }
    }

    private static ReplayBatchOriginalIssueQuantity unknown(long id, String key) {
        return new ReplayBatchOriginalIssueQuantity(id, key, Presence.UNKNOWN, null, null, null, null);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
