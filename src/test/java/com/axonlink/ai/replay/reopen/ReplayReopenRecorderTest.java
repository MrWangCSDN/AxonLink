package com.axonlink.ai.replay.reopen;

import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;

import static org.junit.jupiter.api.Assertions.*;

import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.replay.persistence.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.*;

class ReplayReopenRecorderTest extends ReplayReopenTestSupport {
    @Test
    void originalAndBackfilledFactsEachAppendOneHistoryEntryWithoutInflatingMovements() {
        record(1);
        record(1);
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit", Long.class));
        var audit =
                jdbc.queryForMap(
                        "SELECT action,operator_username,to_owner_username FROM"
                                + " dii_replay_reopen_audit");
        assertEquals("REOPEN_RECORDED", audit.get("action"));
        assertEquals("SYSTEM", audit.get("operator_username"));
        assertEquals("alice", audit.get("to_owner_username"));
        assertTrue(
                dao.statistics(filter()).people().stream()
                        .allMatch(p -> p.adjustedIn() == 0 && p.adjustedOut() == 0));
    }

    @Test
    void concurrentDuplicateSourcesAllocateExactlyOneStableSequencePerFact() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                long round = i % 3 + 1;
                futures.add(pool.submit(() -> record(round)));
            }
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3, dao.events(filter()).total());
        assertEquals(
                List.of(1L, 2L, 3L),
                dao.events(filter()).items().stream()
                        .map(EventRow::occurrenceNo)
                        .sorted()
                        .toList());
    }

    @Test
    void counterRejectsHashCollisionInsteadOfJoiningDifferentIssueIdentities() {
        jdbc.update(
                "INSERT INTO dii_replay_reopen_counter(issue_key_hash,issue_key,next_sequence)"
                        + " VALUES(?,?,1)",
                ReplayReopenRecorder.sha256("K"),
                "other-key");
        assertThrows(IllegalStateException.class, () -> record(1));
        assertEquals(0, dao.events(filter()).total());
    }

    @Test
    void manyBatchStatisticsUseBoundedDatabaseQueriesAndDoNotDoubleCountLifetimeIssues() {
        for (int i = 1; i <= 25; i++)
            recorder.record(
                    row(ReplayIssueStatus.PENDING_VERIFICATION),
                    row(ReplayIssueStatus.REOPENED),
                    i,
                    "RPT20261022-" + i,
                    at,
                    null,
                    ReplayIssueOperator.system());
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var countingDataSource =
                new org.springframework.jdbc.datasource.DelegatingDataSource(jdbc.getDataSource()) {
                    @Override
                    public java.sql.Connection getConnection() throws java.sql.SQLException {
                        var connection = super.getConnection();
                        return (java.sql.Connection)
                                java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[] {java.sql.Connection.class},
                                        (proxy, method, args) -> {
                                            if (method.getName().equals("prepareStatement")
                                                    && args[0] instanceof String sql
                                                    && sql.startsWith("SELECT"))
                                                count.incrementAndGet();
                                            try {
                                                return method.invoke(connection, args);
                                            } catch (
                                                    java.lang.reflect.InvocationTargetException
                                                            failure) {
                                                throw failure.getCause();
                                            }
                                        });
                    }
                };
        var statistics =
                new ReplayReopenDao(new JdbcTemplate(countingDataSource))
                        .statistics(new Filter(null, null, null, null, null, null, false, 1, 1));
        assertEquals(25, statistics.totalEvents());
        assertEquals(25, statistics.batches().size());
        assertEquals(1, statistics.distinctIssues());
        assertEquals(1, statistics.people().get(0).issueCount());
        assertTrue(
                count.get() <= 4,
                "Statistics must have fixed query cost as the batch count grows: " + count);
        var batch =
                dao.statistics(
                        new Filter(null, "RPT20261022-2", null, "QUERY", null, null, false, 1, 1));
        assertEquals(1, batch.totalEvents());
        assertEquals(1, batch.batches().size());
    }

    @Test
    void recordsTwoRealTransitionsInSameBatchAndDeduplicatesSource() {
        record(1);
        record(1);
        record(2);
        assertEquals(2L, dao.issueCounts(List.of("K")).get("K"));
        var events = dao.events(filter());
        assertEquals(2, events.total());
        assertEquals(2, events.items().get(0).occurrenceNo());
        assertEquals("alice", events.items().get(0).defaultOwnerUsername());
        assertEquals("公共组", events.items().get(0).issueDomain());
        recorder.record(
                row(ReplayIssueStatus.REOPENED),
                row(ReplayIssueStatus.REOPENED),
                3,
                "RPT20261022-1",
                at,
                null,
                ReplayIssueOperator.system());
        assertEquals(2, dao.events(filter()).total());
    }

    @Test
    void disabledRecorderNeverTouchesUnmigratedTables() {
        properties.setEnabled(false);
        jdbc.execute("DROP TABLE dii_replay_reopen_event");
        assertDoesNotThrow(() -> record(1));
    }

    @Test
    void factsRollbackTogetherWithOuterImportTransaction() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        issues.inTransaction(
                                d -> {
                                    record(1);
                                    throw new IllegalStateException("failed import");
                                }));
        assertEquals(0, dao.events(filter()).total());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_counter", Long.class));
    }

    @Test
    void mappingSnapshotsStayStableAndMultipleOrInactiveDevelopersRemainUnassigned() {
        record(1);
        jdbc.update(
                "UPDATE dii_replay_transaction_person SET"
                        + " developer_usernames='alice,bob',developer='Alice、Bob'");
        record(2);
        jdbc.update("UPDATE dii_replay_transaction_person SET developer_usernames='disabled'");
        record(3);
        jdbc.update("DELETE FROM dii_replay_issue");
        var rows = dao.events(filter()).items();
        assertNull(rows.get(0).currentOwnerUsername());
        assertNull(rows.get(1).currentOwnerUsername());
        assertEquals("alice", rows.get(2).currentOwnerUsername());
        assertTrue(rows.get(1).developerSnapshot().contains("Alice"));
        assertEquals(3, dao.issueCounts(List.of("K")).get("K"));
    }

    @Test
    void statisticsUseAllFilteredEventsAuditTransfersAndUnassignedBucket() {
        record(1);
        record(2);
        jdbc.update("UPDATE dii_replay_transaction_person SET developer_usernames='alice,bob'");
        record(3);
        var event = dao.events(filter()).items().get(2);
        dao.updateAttribution(event.id(), "bob", "Bob", 1);
        dao.appendAudit(
                new AuditEntry(
                        0,
                        event.id(),
                        1L,
                        "APPEAL_APPROVED",
                        "leader",
                        "Leader",
                        at,
                        "reason",
                        "proof",
                        "alice",
                        "Alice",
                        "bob",
                        "Bob"),
                "公共组");
        var unassigned = dao.events(filter()).items().get(0);
        dao.updateAttribution(unassigned.id(), "bob", "Bob", 1);
        dao.appendAudit(
                new AuditEntry(
                        0,
                        unassigned.id(),
                        null,
                        "OWNER_ASSIGNED",
                        "leader",
                        "Leader",
                        at,
                        "reason",
                        null,
                        null,
                        null,
                        "bob",
                        "Bob"),
                "公共组");
        dao.setPendingAppeal(dao.events(filter()).items().get(1).id(), 2L);
        Statistics stats =
                dao.statistics(new Filter(null, null, null, null, null, null, false, 1, 1));
        assertEquals(3, stats.totalEvents());
        assertEquals(1, stats.distinctIssues());
        assertEquals(1, stats.pendingEvents());
        assertEquals(3, stats.people().stream().mapToLong(PersonStat::currentCount).sum());
        PersonStat bob =
                stats.people().stream()
                        .filter(p -> "bob".equals(p.username()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, bob.adjustedIn());
        assertEquals(2, bob.currentCount());
        PersonStat none =
                stats.people().stream().filter(p -> p.username() == null).findFirst().orElseThrow();
        assertEquals(1, none.defaultCount());
        assertEquals(1, none.adjustedOut());
        assertEquals(3, stats.batches().get(0).totalEvents());
    }

    @Test
    void historicalBackfillRequiresProvenTransitionAndNeverUsesCurrentMapping() {
        long id = issues.insertCurrent(row(ReplayIssueStatus.PENDING_VERIFICATION));
        for (int i = 1; i <= 3; i++) {
            long round = issues.insertImportRound("r" + i, at, ReplayIssueOperator.system(), 1);
            issues.insertIssueRound(
                    round,
                    id,
                    "K",
                    true,
                    i == 2 ? ReplayIssueStatus.OPEN : ReplayIssueStatus.PENDING_VERIFICATION,
                    ReplayIssueStatus.REOPENED,
                    "导入",
                    "公共组",
                    1,
                    at,
                    "{\"groupName\":\"公共组\",\"transactionCode\":\"6208\",\"issueDescription\":\"historic\"}",
                    "RPT20261022-1");
        }
        var first = recorder.backfill(0, 2, ReplayIssueOperator.system());
        assertEquals(2, first.scanned());
        assertEquals(1, first.inserted());
        assertTrue(first.hasMore());
        var next = recorder.backfill(first.nextRoundEntryId(), 2, ReplayIssueOperator.system());
        assertEquals(1, next.inserted());
        assertFalse(next.hasMore());
        assertEquals(0, recorder.backfill(0, 10, ReplayIssueOperator.system()).inserted());
        assertEquals(2, dao.events(filter()).total());
        assertTrue(
                dao.events(filter()).items().stream()
                        .allMatch(e -> e.defaultOwnerUsername() == null));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE"
                                + " action='REOPEN_BACKFILLED'",
                        Long.class));
    }
}
