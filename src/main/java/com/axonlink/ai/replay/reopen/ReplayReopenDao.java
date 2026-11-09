package com.axonlink.ai.replay.reopen;

import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class ReplayReopenDao {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate writeTransaction;
    private static final String EVENTS = "dii_replay_reopen_event";
    private static final RowMapper<EventRow> MAPPER =
            (r, n) ->
                    new EventRow(
                            r.getLong("id"),
                            r.getString("issue_key"),
                            r.getLong("occurrence_no"),
                            nullableLong(r, "replay_issue_id"),
                            r.getLong("source_round_id"),
                            r.getString("replay_type"),
                            r.getString("issue_domain"),
                            r.getString("group_name"),
                            r.getString("batch_name"),
                            r.getString("transaction_code"),
                            r.getString("issue_description"),
                            time(r, "occurred_at"),
                            time(r, "release_at"),
                            r.getString("default_owner_username"),
                            r.getString("default_owner_name"),
                            r.getString("current_owner_username"),
                            r.getString("current_owner_name"),
                            r.getString("owner_resolution"),
                            r.getString("developer_snapshot"),
                            r.getLong("version"),
                            nullableLong(r, "pending_appeal_id"));

    public ReplayReopenDao(JdbcTemplate diiResultJdbcTemplate) {
        jdbc = diiResultJdbcTemplate;
        transaction =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        writeTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        // A request can wait for an event lock after resolving its actor/request UUID.
        // Subsequent decision/evidence checks must observe commits made during that wait.
        writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    public <T> T inWriteTransaction(Supplier<T> work) {
        return writeTransaction.execute(status -> work.get());
    }

    public EventRow findEvent(long id) {
        return jdbc.query("SELECT * FROM " + EVENTS + " WHERE id=?", MAPPER, id).stream()
                .findFirst()
                .orElse(null);
    }

    public List<EventRow> lockEvents(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<EventRow> result = new ArrayList<>();
        ids.stream()
                .distinct()
                .sorted()
                .forEach(
                        id ->
                                result.addAll(
                                        jdbc.query(
                                                "SELECT * FROM "
                                                        + EVENTS
                                                        + " WHERE id=? FOR UPDATE",
                                                MAPPER,
                                                id)));
        return result;
    }

    public Page<EventRow> events(Filter filter) {
        Filter f = normalize(filter);
        Where w = where(f);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + EVENTS + " e" + w.sql(),
                        Long.class,
                        w.args().toArray());
        List<Object> args = new ArrayList<>(w.args());
        args.add(f.pageSize());
        args.add((long) (f.page() - 1) * f.pageSize());
        return new Page<>(
                jdbc.query(
                        "SELECT e.* FROM "
                                + EVENTS
                                + " e"
                                + w.sql()
                                + " ORDER BY e.occurred_at DESC,e.id DESC LIMIT ? OFFSET ?",
                        MAPPER,
                        args.toArray()),
                total,
                f.page(),
                f.pageSize());
    }

    public Map<String, Long> issueCounts(List<String> keys) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) return result;
        List<String> normalized =
                keys.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        if (normalized.isEmpty()) return result;
        normalized.forEach(k -> result.put(k, 0L));
        jdbc.query(
                "SELECT issue_key,COUNT(*) cnt FROM "
                        + EVENTS
                        + " WHERE issue_key IN ("
                        + String.join(",", Collections.nCopies(normalized.size(), "?"))
                        + ") GROUP BY issue_key",
                r -> {
                    result.put(r.getString(1), r.getLong(2));
                },
                normalized.toArray());
        return result;
    }

    public Statistics statistics(Filter filter) {
        return inTransaction(() -> statisticsWithinTransaction(filter));
    }

    private Statistics statisticsWithinTransaction(Filter filter) {
        Filter f = normalize(filter);
        Where w = where(f);
        long[] totals =
                jdbc.queryForObject(
                        "SELECT COUNT(*),COUNT(DISTINCT e.issue_key_hash),COALESCE(SUM(CASE WHEN"
                                + " e.pending_appeal_id IS NOT NULL THEN 1 ELSE 0 END),0) FROM "
                                + EVENTS
                                + " e"
                                + w.sql(),
                        (r, n) -> new long[] {r.getLong(1), r.getLong(2), r.getLong(3)},
                        w.args().toArray());
        List<BatchCount> batches =
                jdbc.query(
                        "SELECT e.batch_name,COUNT(*) FROM "
                                + EVENTS
                                + " e"
                                + w.sql()
                                + " GROUP BY e.batch_name ORDER BY e.batch_name",
                        (r, n) -> new BatchCount(r.getString(1), r.getLong(2)),
                        w.args().toArray());
        Map<String, List<PersonStat>> batchPeople = new LinkedHashMap<>();
        for (PersonAggregate row : people(w, true))
            batchPeople
                    .computeIfAbsent(row.batchName(), ignored -> new ArrayList<>())
                    .add(row.person());
        List<BatchStat> batchStats =
                batches.stream()
                        .map(
                                batch ->
                                        new BatchStat(
                                                batch.name(),
                                                batch.count(),
                                                batchPeople.getOrDefault(batch.name(), List.of())))
                        .toList();
        return new Statistics(
                totals[0],
                totals[1],
                totals[2],
                people(w, false).stream().map(PersonAggregate::person).toList(),
                batchStats);
    }

    private record BatchCount(String name, long count) {}

    private record PersonAggregate(String batchName, PersonStat person) {}

    /**
     * Aggregate global and per-batch attribution independently so distinct issue counts remain
     * exact.
     */
    private List<PersonAggregate> people(Where w, boolean byBatch) {
        String source = " FROM " + EVENTS + " e";
        String defaults =
                "SELECT e.batch_name,e.default_owner_username username,e.default_owner_name"
                        + " real_name,1 dc,0 ai,0 ao,0 cc,0 pc,NULL issue_hash"
                        + source
                        + w.sql();
        String current =
                "SELECT e.batch_name,e.current_owner_username,e.current_owner_name,0,0,0,1,CASE"
                        + " WHEN e.pending_appeal_id IS NULL THEN 0 ELSE 1 END,e.issue_key_hash"
                        + source
                        + w.sql();
        String auditSource =
                source
                        + " JOIN dii_replay_reopen_audit a ON a.event_id=e.id"
                        + w.sql()
                        + " AND a.action IN ('APPEAL_APPROVED','OWNER_ASSIGNED') AND"
                        + " (COALESCE(a.from_owner_username,'')<>COALESCE(a.to_owner_username,''))";
        String inbound =
                "SELECT e.batch_name,a.to_owner_username,a.to_owner_name,0,1,0,0,0,NULL"
                        + auditSource;
        String outbound =
                "SELECT e.batch_name,a.from_owner_username,a.from_owner_name,0,0,1,0,0,NULL"
                        + auditSource;
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 4; i++) args.addAll(w.args());
        String grouping = byBatch ? "batch_name,username" : "username";
        String batchColumn = byBatch ? "batch_name" : "NULL";
        return jdbc.query(
                "SELECT "
                        + batchColumn
                        + ",username,MAX(real_name),SUM(dc),SUM(ai),SUM(ao),SUM(cc),SUM(pc),COUNT(DISTINCT"
                        + " issue_hash) FROM ("
                        + defaults
                        + " UNION ALL "
                        + current
                        + " UNION ALL "
                        + inbound
                        + " UNION ALL "
                        + outbound
                        + ") contributions GROUP BY "
                        + grouping
                        + " ORDER BY "
                        + grouping,
                (r, n) ->
                        new PersonAggregate(
                                r.getString(1),
                                new PersonStat(
                                        r.getString(2),
                                        r.getString(3),
                                        r.getLong(4),
                                        r.getLong(5),
                                        r.getLong(6),
                                        r.getLong(7),
                                        r.getLong(8),
                                        r.getLong(9))),
                args.toArray());
    }

    public List<String> batchNames(String replayType) {
        Where w = where(new Filter(null, null, null, replayType, null, null, false, 1, 20));
        return jdbc.query(
                "SELECT DISTINCT e.batch_name FROM "
                        + EVENTS
                        + " e"
                        + w.sql()
                        + " AND e.batch_name IS NOT NULL ORDER BY e.batch_name DESC",
                (r, n) -> r.getString(1),
                w.args().toArray());
    }

    public void updateAttribution(long id, String username, String name, long expectedVersion) {
        if (jdbc.update(
                        "UPDATE "
                                + EVENTS
                                + " SET current_owner_username=?,current_owner_name=?,owner_resolution='RESOLVED',version=version+1"
                                + " WHERE id=? AND version=?",
                        username,
                        name,
                        id,
                        expectedVersion)
                != 1) throw new OptimisticLockingFailureException("重开事件版本已变化");
    }

    public void setPendingAppeal(long id, Long appealId) {
        jdbc.update("UPDATE " + EVENTS + " SET pending_appeal_id=? WHERE id=?", appealId, id);
    }

    public void appendAudit(AuditEntry e, String domain) {
        jdbc.update(
                "INSERT INTO dii_replay_reopen_audit"
                    + " (event_id,appeal_id,issue_domain,action,operator_username,operator_name,occurred_at,reason,evidence,from_owner_username,from_owner_name,to_owner_username,to_owner_name)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                e.eventId(),
                e.appealId(),
                domain,
                e.action(),
                e.operatorUsername(),
                e.operatorName(),
                e.occurredAt(),
                e.reason(),
                e.evidence(),
                e.fromOwnerUsername(),
                e.fromOwnerName(),
                e.toOwnerUsername(),
                e.toOwnerName());
    }

    private static Filter normalize(Filter f) {
        if (f == null) return new Filter(null, null, null, null, null, null, false, 1, 20);
        return new Filter(
                f.issueKey(),
                f.batchName(),
                f.issueDomain(),
                f.replayType(),
                f.ownerUsername(),
                f.eventId(),
                f.onlyMine(),
                Math.max(1, f.page()),
                f.pageSize() <= 0 ? 20 : Math.min(200, f.pageSize()),
                f.sourceRoundId());
    }

    private record Where(String sql, List<Object> args) {}

    private Where where(Filter f) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        add(sql, args, "e.issue_key", f.issueKey());
        add(sql, args, "e.batch_name", f.batchName());
        add(sql, args, "e.issue_domain", f.issueDomain());
        if (f.replayType() != null && !f.replayType().isBlank() && !"ALL".equals(f.replayType()))
            add(sql, args, "e.replay_type", f.replayType());
        add(sql, args, "e.current_owner_username", f.ownerUsername());
        if (f.onlyMine() && (f.ownerUsername() == null || f.ownerUsername().isBlank()))
            sql.append(" AND 1=0");
        if (f.eventId() != null) {
            sql.append(" AND e.id=?");
            args.add(f.eventId());
        }
        if (f.sourceRoundId() != null) {
            sql.append(" AND e.source_round_id=?");
            args.add(f.sourceRoundId());
        }
        return new Where(sql.toString(), args);
    }

    private static void add(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append("=?");
            args.add(value.trim());
        }
    }

    private static Long nullableLong(ResultSet r, String c) throws SQLException {
        return r.getObject(c) == null ? null : r.getLong(c);
    }

    private static LocalDateTime time(ResultSet r, String c) throws SQLException {
        return r.getTimestamp(c) == null ? null : r.getTimestamp(c).toLocalDateTime();
    }
}
