package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.user.entity.SysUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;
import static com.axonlink.ai.replay.reopen.ReplayReopenWorkflowDtos.*;

/** Parameterized persistence on the same connection/transaction as permanent events. */
@Repository
public class ReplayReopenWorkflowDao {
    private static final String APPEALS = "dii_replay_reopen_appeal";
    private static final String ITEMS = "dii_replay_reopen_appeal_item";
    private static final String POLICIES = "dii_replay_reopen_policy";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RowMapper<AppealRow> APPEAL_MAPPER = (r,n) -> new AppealRow(
            r.getLong("id"), r.getLong("version"), r.getString("request_id"), r.getString("request_fingerprint"),
            r.getString("kind"), r.getString("status"), r.getString("issue_domain"),
            r.getString("applicant_username"), r.getString("applicant_name"),
            r.getString("suggested_owner_username"), r.getString("suggested_owner_name"),
            r.getString("final_owner_username"), r.getString("final_owner_name"),
            r.getString("leader_username"), r.getString("leader_name"), r.getString("reason"),
            r.getString("evidence"), r.getString("evidence_hash"), time(r,"submitted_at"),
            time(r,"decided_at"), r.getString("decision_reason"), time(r,"closed_at"), r.getString("close_reason"));
    private static final RowMapper<Policy> POLICY_MAPPER = (r,n) -> new Policy(r.getString("issue_domain"),
            readLeaders(r.getString("leader_usernames")), r.getString("primary_leader_username"), r.getLong("version"));
    private final JdbcTemplate jdbc;

    public ReplayReopenWorkflowDao(ReplayReopenDao eventDao) { this.jdbc = eventDao.jdbc(); }

    public Policy findPolicy(String domain, boolean forUpdate) {
        return jdbc.query("SELECT * FROM " + POLICIES + " WHERE issue_domain=?" + (forUpdate ? " FOR UPDATE" : ""),
                POLICY_MAPPER, domain).stream().findFirst().orElse(null);
    }

    public List<Policy> policies() {
        return jdbc.query("SELECT * FROM " + POLICIES + " ORDER BY issue_domain", POLICY_MAPPER);
    }

    public Policy savePolicy(String domain, List<String> leaders, String primary, long expectedVersion,
                             String updatedBy, LocalDateTime at) {
        String encoded = writeLeaders(leaders);
        if (expectedVersion == 0) {
            try {
                jdbc.update("INSERT INTO " + POLICIES
                                + " (issue_domain,leader_usernames,primary_leader_username,version,updated_by,updated_at) VALUES (?,?,?,1,?,?)",
                        domain, encoded, primary, updatedBy, at);
            } catch (DataIntegrityViolationException failure) {
                throw ReplayReopenException.conflict("领域授权已变化，请刷新后重试");
            }
        } else {
            changed(jdbc.update("UPDATE " + POLICIES
                            + " SET leader_usernames=?,primary_leader_username=?,version=version+1,updated_by=?,updated_at=?"
                            + " WHERE issue_domain=? AND version=?",
                    encoded, primary, updatedBy, at, domain, expectedVersion));
        }
        return new Policy(domain, List.copyOf(leaders), primary, expectedVersion + 1);
    }

    public AppealRow findAppeal(long id, boolean forUpdate) {
        return jdbc.query("SELECT * FROM " + APPEALS + " WHERE id=?" + (forUpdate ? " FOR UPDATE" : ""),
                APPEAL_MAPPER, id).stream().findFirst().orElse(null);
    }

    public AppealRow findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM " + APPEALS + " WHERE request_id=?", APPEAL_MAPPER, requestId)
                .stream().findFirst().orElse(null);
    }

    public long insertAppeal(CreateAppealRequest body, String fingerprint, String domain, SysUser applicant,
                             SysUser suggested, SysUser leader, String evidenceHash, LocalDateTime at) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO " + APPEALS
                    + " (request_id,request_fingerprint,kind,status,issue_domain,applicant_username,applicant_name,"
                    + "suggested_owner_username,suggested_owner_name,leader_username,leader_name,reason,evidence,evidence_hash,submitted_at,version)"
                    + " VALUES (?,?,?,'PENDING',?,?,?,?,?,?,?,?,?,?,?,1)", Statement.RETURN_GENERATED_KEYS);
            Object[] values = {body.requestId(), fingerprint, body.kind(), domain, applicant.getUsername(), applicant.getRealName(),
                    suggested == null ? null : suggested.getUsername(), suggested == null ? null : suggested.getRealName(),
                    leader.getUsername(), leader.getRealName(), body.reason(), body.evidence(), evidenceHash, at};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("申请未返回生成的 ID");
        return key.longValue();
    }

    public void insertItem(long appealId, EventRow event) {
        jdbc.update("INSERT INTO " + ITEMS + " (appeal_id,event_id,expected_version,before_owner_username,before_owner_name) VALUES (?,?,?,?,?)",
                appealId, event.id(), event.version(), event.currentOwnerUsername(), event.currentOwnerName());
    }

    public List<AppealItem> items(long appealId) {
        return jdbc.query("SELECT i.event_id,e.issue_key,e.occurrence_no,e.batch_name,i.expected_version,i.before_owner_username,i.before_owner_name"
                        + " FROM " + ITEMS + " i JOIN dii_replay_reopen_event e ON e.id=i.event_id WHERE i.appeal_id=? ORDER BY i.event_id",
                (r,n) -> new AppealItem(r.getLong("event_id"), r.getString("issue_key"), r.getLong("occurrence_no"),
                        r.getString("batch_name"), r.getLong("expected_version"), r.getString("before_owner_username"), r.getString("before_owner_name")), appealId);
    }

    public void decideAppeal(long id, long expectedVersion, String status, String finalUsername, String finalName,
                             String reason, LocalDateTime at) {
        if (!List.of("APPROVED", "REJECTED").contains(status)) throw ReplayReopenException.invalid("裁定状态无效");
        changed(jdbc.update("UPDATE " + APPEALS
                        + " SET status=?,final_owner_username=?,final_owner_name=?,decision_reason=?,decided_at=?,version=version+1"
                        + " WHERE id=? AND version=? AND status='PENDING'",
                status, finalUsername, finalName, reason, at, id, expectedVersion));
    }

    public void closeAppeal(long id, long expectedVersion, String status, String reason, LocalDateTime at) {
        if (!List.of("WITHDRAWN", "INVALID").contains(status)) throw ReplayReopenException.invalid("关闭状态无效");
        changed(jdbc.update("UPDATE " + APPEALS + " SET status=?,close_reason=?,closed_at=?,version=version+1"
                + " WHERE id=? AND version=? AND status='PENDING'", status, reason, at, id, expectedVersion));
    }

    public void reassignAppeal(long id, long expectedVersion, String username, String name, LocalDateTime at) {
        changed(jdbc.update("UPDATE " + APPEALS + " SET leader_username=?,leader_name=?,version=version+1"
                + " WHERE id=? AND version=? AND status='PENDING'", username, name, id, expectedVersion));
    }

    public AppealSummary latestAppeal(long eventId) {
        return jdbc.query("SELECT a.* FROM " + APPEALS + " a JOIN " + ITEMS
                        + " i ON i.appeal_id=a.id WHERE i.event_id=? ORDER BY a.id DESC LIMIT 1",
                (r,n) -> new AppealSummary(r.getLong("id"), r.getString("kind"), r.getString("status"),
                        r.getString("suggested_owner_username"), r.getString("final_owner_username"), r.getString("leader_username"), time(r,"submitted_at")),
                eventId).stream().findFirst().orElse(null);
    }

    public boolean hasDecision(long eventId) { return decidedCount(eventId, null) > 0; }
    public boolean hasEvidenceHash(long eventId, String hash) { return hash != null && !hash.isBlank() && decidedCount(eventId, hash) > 0; }

    private long decidedCount(long eventId, String hash) {
        List<Object> args = new ArrayList<>();
        args.add(eventId);
        String sql = "SELECT COUNT(*) FROM " + APPEALS + " a JOIN " + ITEMS
                + " i ON i.appeal_id=a.id WHERE i.event_id=? AND a.status IN ('APPROVED','REJECTED')";
        if (hash != null) { sql += " AND a.evidence_hash=?"; args.add(hash); }
        return jdbc.queryForObject(sql, Long.class, args.toArray());
    }

    public Page<AppealRow> appeals(Filter filter, String status, String applicantUsername, String leaderUsername) {
        Filter scope = filter == null ? new Filter(null,null,null,null,null,null,false,1,20) : filter;
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        add(where,args,"a.status",status);
        add(where,args,"a.applicant_username",applicantUsername);
        add(where,args,"a.leader_username",leaderUsername);
        if (scope.onlyMine() && (applicantUsername == null || applicantUsername.isBlank())) where.append(" AND 1=0");
        where.append(" AND EXISTS (SELECT 1 FROM ").append(ITEMS).append(" i JOIN dii_replay_reopen_event e ON e.id=i.event_id WHERE i.appeal_id=a.id");
        add(where,args,"e.issue_key",scope.issueKey());
        add(where,args,"e.batch_name",scope.batchName());
        add(where,args,"e.issue_domain",scope.issueDomain());
        add(where,args,"e.current_owner_username",scope.ownerUsername());
        if (!"ALL".equals(scope.replayType())) add(where,args,"e.replay_type",scope.replayType());
        if (scope.eventId() != null) { where.append(" AND e.id=?"); args.add(scope.eventId()); }
        if (scope.sourceRoundId() != null) { where.append(" AND e.source_round_id=?"); args.add(scope.sourceRoundId()); }
        where.append(')');
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + APPEALS + " a" + where, Long.class, args.toArray());
        int page = Math.max(1,scope.page()), pageSize = pageSize(scope.pageSize());
        args.add(pageSize); args.add((long)(page-1)*pageSize);
        return new Page<>(jdbc.query("SELECT a.* FROM " + APPEALS + " a" + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                APPEAL_MAPPER,args.toArray()),total,page,pageSize);
    }

    public Page<AuditEntry> history(long eventId, int requestedPage, int requestedSize) {
        int page = Math.max(1,requestedPage), size = pageSize(requestedSize);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE event_id=?",Long.class,eventId);
        List<AuditEntry> rows = jdbc.query("SELECT * FROM dii_replay_reopen_audit WHERE event_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                (r,n) -> new AuditEntry(r.getLong("id"), nullableLong(r,"event_id"), nullableLong(r,"appeal_id"),r.getString("action"),
                        r.getString("operator_username"),r.getString("operator_name"),time(r,"occurred_at"),r.getString("reason"),r.getString("evidence"),
                        r.getString("from_owner_username"),r.getString("from_owner_name"),r.getString("to_owner_username"),r.getString("to_owner_name")),
                eventId,size,(long)(page-1)*size);
        return new Page<>(rows,total,page,size);
    }

    private static void changed(int count) {
        if (count != 1) throw ReplayReopenException.conflict("记录状态或版本已变化，请刷新后重试");
    }
    private static int pageSize(int requested) { return requested <= 0 ? 20 : Math.min(200,requested); }
    private static void add(StringBuilder sql,List<Object> args,String column,String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(column).append("=?"); args.add(value.trim()); }
    }
    private static Long nullableLong(ResultSet r,String column) throws SQLException { return r.getObject(column)==null ? null : r.getLong(column); }
    private static LocalDateTime time(ResultSet r,String column) throws SQLException { return r.getTimestamp(column)==null ? null : r.getTimestamp(column).toLocalDateTime(); }
    private static String writeLeaders(List<String> leaders) {
        try { return JSON.writeValueAsString(leaders); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("无法保存领域领导配置",failure); }
    }
    private static List<String> readLeaders(String value) {
        try { return List.copyOf(JSON.readValue(value,new TypeReference<List<String>>() {})); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("领域领导配置损坏",failure); }
    }
}
