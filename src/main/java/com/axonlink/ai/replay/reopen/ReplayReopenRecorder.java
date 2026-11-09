package com.axonlink.ai.replay.reopen;

import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;

import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.replay.persistence.*;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/** Captures a permanent fact inside the same result-database transaction as the import. */
@Service
public class ReplayReopenRecorder {
    private final ReplayIssueDao issues;
    private final ReplayTransactionPersonDao people;
    private final SysUserDao users;
    private final ReplayReopenDao dao;
    private final ReplayReopenProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public ReplayReopenRecorder(
            ReplayIssueDao issues,
            ReplayTransactionPersonDao people,
            SysUserDao users,
            ReplayReopenDao dao,
            ReplayReopenProperties properties) {
        this.issues = issues;
        this.people = people;
        this.users = users;
        this.dao = dao;
        this.properties = properties;
    }

    public void record(
            ReplayIssueRow before,
            ReplayIssueRow after,
            long sourceRoundId,
            String batchName,
            LocalDateTime occurredAt,
            LocalDateTime releaseAt,
            ReplayIssueOperator operator) {
        if (!properties.isEnabled()) return;
        if (before == null
                || after == null
                || before.issueStatus() != ReplayIssueStatus.PENDING_VERIFICATION
                || after.issueStatus() != ReplayIssueStatus.REOPENED) return;
        dao.inTransaction(
                () -> {
                    ReplayTransactionPersonRow mapping =
                            people.findByTransactionCode(after.transactionCode());
                    List<String> identities =
                            mapping == null || mapping.developerUsernames() == null
                                    ? List.of()
                                    : Arrays.stream(mapping.developerUsernames().split("[、,，;；]"))
                                            .map(String::trim)
                                            .filter(s -> !s.isEmpty())
                                            .distinct()
                                            .toList();
                    SysUser owner =
                            identities.size() == 1
                                    ? users.findActiveByUsername(identities.get(0))
                                    : null;
                    String developerSnapshot =
                            mapping == null
                                    ? null
                                    : mapping.developer()
                                            + " ["
                                            + mapping.developerUsernames()
                                            + "]";
                    String issueDomain = after.groupName();
                    if (after.id() != null) {
                        List<String> domains =
                                issues.jdbc()
                                        .query(
                                                "SELECT issue_domain FROM dii_replay_issue WHERE"
                                                        + " id=?",
                                                (r, n) -> r.getString(1),
                                                after.id());
                        if (!domains.isEmpty()
                                && domains.get(0) != null
                                && !domains.get(0).isBlank()) issueDomain = domains.get(0).trim();
                    }
                    insert(
                            after.issueKey(),
                            after.id(),
                            sourceRoundId,
                            issueDomain,
                            after.groupName(),
                            batchName,
                            after.transactionCode(),
                            after.issueDescription(),
                            occurredAt,
                            releaseAt,
                            owner == null ? null : owner.getUsername(),
                            owner == null ? null : owner.getRealName(),
                            developerSnapshot,
                            "REOPEN_RECORDED",
                            operator);
                    return null;
                });
    }

    public BackfillResult backfill(
            long afterRoundEntryId, int limit, ReplayIssueOperator operator) {
        if (!properties.isEnabled()) throw new IllegalStateException("重开台账尚未启用");
        int size = Math.min(1000, Math.max(1, limit));
        return dao.inTransaction(
                () -> {
                    List<Map<String, Object>> rounds =
                            dao.jdbc()
                                    .queryForList(
                                            "SELECT"
                                                + " id,round_id,replay_issue_id,issue_key,status_before,status_after,source_sheet,recorded_at,incoming_snapshot,batch_name"
                                                + " FROM dii_replay_issue_round WHERE id>? ORDER BY"
                                                + " id LIMIT ?",
                                            Math.max(0, afterRoundEntryId),
                                            size + 1);
                    boolean more = rounds.size() > size;
                    int scanned = Math.min(size, rounds.size()), inserted = 0;
                    long cursor = afterRoundEntryId;
                    for (Map<String, Object> round : rounds.subList(0, scanned)) {
                        cursor = ((Number) round.get("id")).longValue();
                        if (!ReplayIssueStatus.PENDING_VERIFICATION
                                        .displayValue()
                                        .equals(round.get("status_before"))
                                || !ReplayIssueStatus.REOPENED
                                        .displayValue()
                                        .equals(round.get("status_after"))) continue;
                        JsonNode snapshot = parseSnapshot((String) round.get("incoming_snapshot"));
                        String group = text(snapshot, "groupName");
                        if (group == null) {
                            group = (String) round.get("source_sheet");
                            if (group != null && group.startsWith("沙箱-"))
                                group = group.substring(3);
                        }
                        String domain = text(snapshot, "issueDomain");
                        if (domain == null) domain = group;
                        String batch = (String) round.get("batch_name");
                        if (batch == null) batch = text(snapshot, "batchNo");
                        Object issueId = round.get("replay_issue_id");
                        Object time = round.get("recorded_at");
                        LocalDateTime at =
                                time instanceof java.sql.Timestamp t
                                        ? t.toLocalDateTime()
                                        : (LocalDateTime) time;
                        if (insert(
                                (String) round.get("issue_key"),
                                issueId == null ? null : ((Number) issueId).longValue(),
                                ((Number) round.get("round_id")).longValue(),
                                domain,
                                group,
                                batch,
                                text(snapshot, "transactionCode"),
                                text(snapshot, "issueDescription"),
                                at,
                                null,
                                null,
                                null,
                                null,
                                "REOPEN_BACKFILLED",
                                operator)) inserted++;
                    }
                    return new BackfillResult(scanned, inserted, cursor, more);
                });
    }

    private boolean insert(
            String rawKey,
            Long issueId,
            long round,
            String domain,
            String group,
            String batch,
            String code,
            String description,
            LocalDateTime at,
            LocalDateTime release,
            String owner,
            String name,
            String mapping,
            String action,
            ReplayIssueOperator operator) {
        if (rawKey == null || rawKey.isBlank())
            throw new IllegalArgumentException("issue_key 不能为空");
        String key = rawKey.trim(), hash = sha256(key);
        dao.jdbc()
                .update(
                        "INSERT INTO"
                            + " dii_replay_reopen_counter(issue_key_hash,issue_key,next_sequence)"
                            + " VALUES(?,?,1) ON DUPLICATE KEY UPDATE"
                            + " issue_key_hash=VALUES(issue_key_hash)",
                        hash,
                        key);
        Map<String, Object> counter =
                dao.jdbc()
                        .queryForMap(
                                "SELECT issue_key,next_sequence FROM dii_replay_reopen_counter"
                                        + " WHERE issue_key_hash=? FOR UPDATE",
                                hash);
        if (!key.equals(counter.get("issue_key")))
            throw new IllegalStateException("issue_key 哈希冲突");
        // A backfill may already hold an older REPEATABLE READ source-page snapshot.
        // After the counter lock, use a current read so a concurrent committed fact is visible.
        List<Long> existingEvents =
                dao.jdbc()
                        .queryForList(
                                "SELECT id FROM dii_replay_reopen_event WHERE"
                                        + " source_round_id=? AND issue_key_hash=? FOR UPDATE",
                                Long.class,
                                round,
                                hash);
        if (!existingEvents.isEmpty()) return false;
        long sequence = ((Number) counter.get("next_sequence")).longValue();
        dao.jdbc()
                .update(
                        "INSERT INTO dii_replay_reopen_event"
                            + " (issue_key_hash,issue_key,occurrence_no,replay_issue_id,source_round_id,replay_type,issue_domain,group_name,batch_name,transaction_code,issue_description,occurred_at,release_at,default_owner_username,default_owner_name,current_owner_username,current_owner_name,owner_resolution,developer_snapshot,version)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)",
                        hash,
                        key,
                        sequence,
                        issueId,
                        round,
                        replayType(batch),
                        domain,
                        group,
                        batch,
                        code,
                        description,
                        at,
                        release,
                        owner,
                        name,
                        owner,
                        name,
                        owner == null ? "UNASSIGNED" : "RESOLVED",
                        mapping);
        dao.jdbc()
                .update(
                        "UPDATE dii_replay_reopen_counter SET next_sequence=? WHERE"
                                + " issue_key_hash=?",
                        sequence + 1,
                        hash);
        long eventId =
                dao.jdbc()
                        .queryForObject(
                                "SELECT id FROM dii_replay_reopen_event WHERE source_round_id=? AND"
                                    + " issue_key_hash=?",
                                Long.class,
                                round,
                                hash);
        ReplayIssueOperator effectiveOperator =
                operator == null ? ReplayIssueOperator.system() : operator;
        dao.appendAudit(
                new AuditEntry(
                        0,
                        eventId,
                        null,
                        action,
                        effectiveOperator.username(),
                        effectiveOperator.realName(),
                        at,
                        "REOPEN_BACKFILLED".equals(action) ? "历史轮次重开事实回填" : "正常导入问题重新打开",
                        null,
                        null,
                        null,
                        owner,
                        name),
                domain);
        return true;
    }

    private static String replayType(String batch) {
        return batch != null && batch.toUpperCase(Locale.ROOT).startsWith("DZ")
                ? "DZ"
                : batch != null && batch.toUpperCase(Locale.ROOT).startsWith("RPT")
                        ? "QUERY"
                        : null;
    }

    private JsonNode parseSnapshot(String raw) {
        try {
            return raw == null ? json.createObjectNode() : json.readTree(raw);
        } catch (Exception ignored) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
