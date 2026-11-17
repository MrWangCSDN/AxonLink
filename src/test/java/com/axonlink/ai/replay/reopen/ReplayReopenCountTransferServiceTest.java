package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;
import com.axonlink.ai.replay.reopen.ReplayReopenWorkflowDtos.*;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReplayReopenCountTransferServiceTest {
    private JdbcTemplate jdbc;
    private ReplayReopenDao dao;
    private ReplayReopenService service;
    private ReplayReopenProperties properties;
    private final ReplayIssueOperator actor = new ReplayIssueOperator("deva", "开发甲");

    @BeforeEach
    void setup() {
        jdbc = ReplayReopenTestSupport.database();
        properties = new ReplayReopenProperties();
        properties.setEnabled(true);
        properties.setCountTransferEmpNos(List.of("101", "102"));
        dao = new ReplayReopenDao(jdbc);
        service = service(dao);
        ReplayReopenTestSupport.event(jdbc, 1, "ISS-A", "RPT20261021-01", "deva", "开发甲", "公共组");
        ReplayReopenTestSupport.event(jdbc, 2, "ISS-A", "RPT20261022-01", "deva", "开发甲", "公共组");
        ReplayReopenTestSupport.event(jdbc, 3, "ISS-B", "RPT20261023-01", "deva", "开发甲", "结算组");
    }

    @AfterEach
    void close() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    private ReplayReopenService service(ReplayReopenDao events) {
        ReplayReopenWorkflowDao store = new ReplayReopenWorkflowDao(events);
        ReplayReopenPermissionService permissions = new ReplayReopenPermissionService(
                properties, new SysUserDao(jdbc), store, new ReplayTransactionPersonDao(jdbc));
        return new ReplayReopenService(events, store, permissions, null, properties);
    }

    private CountTransferRequest request(String id, String source, String target, int count, List<String> batches) {
        return new CountTransferRequest(id, "QUERY", batches, source, target, BigDecimal.valueOf(count), "核实后调整次数");
    }

    private CountTransferRequest request(int count) {
        return request(UUID.randomUUID().toString(), "deva", "devb", count, List.of());
    }

    private Statistics statistics() {
        return service.statistics(new Filter(null, null, null, "ALL", null, null, false, 1, 20), actor);
    }

    private long current(String owner) {
        return statistics().people().stream().filter(p -> java.util.Objects.equals(owner, p.username()))
                .mapToLong(PersonStat::currentCount).sum();
    }

    private void error(int status, Runnable work) {
        assertEquals(status, assertThrows(ReplayReopenException.class, work::run).status());
    }

    @Test
    void selectedBatchMovesStableEventsAcrossDomainsAndPreservesEveryTotal() {
        Statistics before = statistics();
        service.transferCounts(request(UUID.randomUUID().toString(), "deva", "devb", 2,
                List.of("RPT20261023-01", "RPT20261021-01")), actor);

        assertEquals("devb", dao.findEvent(1).currentOwnerUsername());
        assertEquals("deva", dao.findEvent(2).currentOwnerUsername());
        assertEquals("devb", dao.findEvent(3).currentOwnerUsername());
        assertEquals(1, current("deva"));
        assertEquals(2, current("devb"));
        assertEquals(before.totalEvents(), statistics().totalEvents());
        assertEquals(before.distinctIssues(), statistics().distinctIssues());
        assertEquals(before.batches().stream().map(BatchStat::totalEvents).toList(),
                statistics().batches().stream().map(BatchStat::totalEvents).toList());
        assertEquals(Map.of("ISS-A", 2L, "ISS-B", 1L), service.issueCounts(List.of("ISS-A", "ISS-B")));
        assertEquals(2, statistics().people().stream().filter(p -> "deva".equals(p.username())).findFirst().orElseThrow().adjustedOut());
        assertEquals(2, statistics().people().stream().filter(p -> "devb".equals(p.username())).findFirst().orElseThrow().adjustedIn());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE action='COUNT_TRANSFERRED'", Integer.class));
        assertEquals("[1,3]", jdbc.queryForObject("SELECT event_ids FROM dii_replay_reopen_count_transfer", String.class));
    }

    @Test
    void normalizedEquivalentRetriesReturnFrozenResultAfterCountsChange() {
        String id = UUID.randomUUID().toString();
        CountTransferRequest body = request(id, "deva", "devb", 1,
                List.of(" RPT20261022-01 ", "RPT20261021-01", "RPT20261021-01"));
        CountTransferResult first = service.transferCounts(body, actor);
        service.transferCounts(request(UUID.randomUUID().toString(), "devb", "devc", 1, List.of()), actor);
        CountTransferResult retried = service.transferCounts(request(id.toUpperCase(), " deva ", " devb ", 1,
                List.of("RPT20261021-01", "RPT20261022-01")), actor);
        assertEquals(first, retried);
        assertEquals("devc", dao.findEvent(1).currentOwnerUsername());
        assertEquals(2, current("deva"));
        assertEquals(0, current("devb"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_count_transfer", Integer.class));
        error(409, () -> service.transferCounts(request(id, "deva", "devb", 2, body.batchNames()), actor));
        error(403, () -> service.transferCounts(body, new ReplayIssueOperator("devb", "开发乙")));
        properties.setCountTransferEmpNos(List.of());
        error(403, () -> service.transferCounts(body, actor));
    }

    @Test
    void unassignedCountsCanBeTransferredAndPendingAppealsAreExcluded() {
        jdbc.update("UPDATE dii_replay_reopen_event SET pending_appeal_id=99 WHERE id=1");
        service.transferCounts(request(2), actor);
        assertEquals("deva", dao.findEvent(1).currentOwnerUsername());
        assertEquals(99L, dao.findEvent(1).pendingAppealId());
        assertEquals(1, statistics().pendingEvents());
        error(409, () -> service.transferCounts(request(1), actor));
        ReplayReopenTestSupport.event(jdbc, 4, "ISS-B", "RPT20261023-01", null, null, "公共组");
        String id = UUID.randomUUID().toString();
        CountTransferResult first = service.transferCounts(request(id, "", "devc", 1, List.of()), actor);
        assertNull(first.sourceOwnerUsername());
        assertEquals(first, service.transferCounts(request(id, null, "devc", 1, List.of()), actor));
        assertEquals("devc", dao.findEvent(4).currentOwnerUsername());
        assertEquals(4, statistics().totalEvents());
    }

    @Test
    void invalidTargetsAmountsAndScopesLeaveNoClaimOrAudit() {
        error(400, () -> service.transferCounts(request(UUID.randomUUID().toString(), "deva", "deva", 1, List.of()), actor));
        error(400, () -> service.transferCounts(request(UUID.randomUUID().toString(), "deva", "inactive", 1, List.of()), actor));
        error(400, () -> service.transferCounts(request(UUID.randomUUID().toString(), "deva", "leader", 1, List.of()), actor));
        error(409, () -> service.transferCounts(request(4), actor));
        error(400, () -> service.transferCounts(new CountTransferRequest("1-1-1-1-1", "QUERY", List.of(), "deva", "devb", BigDecimal.ONE, "原因"), actor));
        error(400, () -> service.transferCounts(new CountTransferRequest(UUID.randomUUID().toString(), "QUERY", java.util.Collections.nCopies(201, "RPT1"), "deva", "devb", BigDecimal.ONE, "原因"), actor));
        error(400, () -> service.transferCounts(new CountTransferRequest(UUID.randomUUID().toString(), "QUERY", List.of(), "deva", "devb", BigDecimal.ONE, " "), actor));
        error(400, () -> service.transferCounts(new CountTransferRequest(UUID.randomUUID().toString(), "QUERY", List.of(), "deva", "devb", new BigDecimal("999999999999999999999999999"), "原因"), actor));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_count_transfer", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit", Integer.class));
        assertEquals(3, current("deva"));
    }

    @Test
    void missingIndependentPermissionAndDisabledFeatureRejectMutation() {
        error(401, () -> service.transferCounts(request(1), null));
        error(401, () -> service.transferCounts(request(1), new ReplayIssueOperator("inactive", "停用")));
        error(403, () -> service.transferCounts(request(1), new ReplayIssueOperator("admin", "管理员")));
        properties.setEnabled(false);
        assertFalse(service.permissions(actor).canTransferCounts());
        error(503, () -> service.transferCounts(request(1), actor));
    }

    @Test
    void auditFailureRollsBackEveryMovedEventAndRequestClaim() {
        AtomicInteger writes = new AtomicInteger();
        ReplayReopenDao failing = new ReplayReopenDao(jdbc) {
            @Override
            public void appendAudit(AuditEntry audit, String domain) {
                if ("COUNT_TRANSFERRED".equals(audit.action()) && writes.incrementAndGet() == 2) {
                    throw new IllegalStateException("模拟第二条审计失败");
                }
                super.appendAudit(audit, domain);
            }
        };
        CountTransferRequest body = request(2);
        assertThrows(IllegalStateException.class, () -> service(failing).transferCounts(body, actor));
        assertEquals(3, current("deva"));
        assertEquals(1, dao.findEvent(1).version());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_count_transfer", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit", Integer.class));
        assertEquals(2, service.transferCounts(body, actor).count());
    }

    @Test
    void snapshotChangedBeforeTheLocksRejectsTheWholeTransfer() {
        ReplayReopenDao changed = new ReplayReopenDao(jdbc) {
            @Override
            public List<EventRow> lockEvents(List<Long> ids) {
                jdbc.update("UPDATE dii_replay_reopen_event SET version=version+1 WHERE id=?", ids.get(1));
                return super.lockEvents(ids);
            }
        };
        error(409, () -> service(changed).transferCounts(request(2), actor));
        assertEquals(3, current("deva"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_count_transfer", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit", Integer.class));
    }

    @Test
    void concurrentTransfersForTheSameSnapshotCannotMoveItTwice() throws Exception {
        CountDownLatch snapshots = new CountDownLatch(2);
        ReplayReopenDao concurrent = new ReplayReopenDao(jdbc) {
            @Override
            public List<EventRow> lockEvents(List<Long> ids) {
                snapshots.countDown();
                try {
                    if (!snapshots.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("候选快照同步超时");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return super.lockEvents(ids);
            }
        };
        ReplayReopenService concurrentService = service(concurrent);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transferStatus(concurrentService, request(2)));
            var second = executor.submit(() -> transferStatus(concurrentService,
                    request(UUID.randomUUID().toString(), "deva", "devc", 2, List.of())));
            assertEquals(List.of(200, 409), List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(3, statistics().totalEvents());
        assertEquals(1, current("deva"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_count_transfer", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit", Integer.class));
    }

    @Test
    void concurrentIdenticalRequestIdsReturnTheSameResultAndMoveOnlyOnce() throws Exception {
        CountTransferRequest body = request(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<CountTransferResult> transfer = () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return service.transferCounts(body, actor);
        };
        try {
            var first = executor.submit(transfer);
            var second = executor.submit(transfer);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, current("deva"));
        assertEquals(2, current("devb"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_count_transfer", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit", Integer.class));
    }

    @Test
    void dzTransfersOnlyMatchingTypeAndAllCanIncludeBothFamilies() {
        ReplayReopenTestSupport.event(jdbc, 4, "ISS-B", "DZ20261023-01", "deva", "开发甲", "公共组");
        jdbc.update("UPDATE dii_replay_reopen_event SET replay_type='DZ' WHERE id=4");
        CountTransferRequest dz = new CountTransferRequest(UUID.randomUUID().toString(), "DZ",
                List.of("DZ20261023-01"), "deva", "devb", BigDecimal.ONE, "动账责任调整");
        service.transferCounts(dz, actor);
        assertEquals("devb", dao.findEvent(4).currentOwnerUsername());
        assertEquals("deva", dao.findEvent(1).currentOwnerUsername());
        CountTransferRequest all = new CountTransferRequest(UUID.randomUUID().toString(), "ALL",
                List.of("RPT20261021-01", "DZ20261023-01"), "deva", "devb", BigDecimal.ONE, "全部类型责任调整");
        service.transferCounts(all, actor);
        assertEquals("devb", dao.findEvent(1).currentOwnerUsername());
        assertEquals(2, current("deva"));
        assertEquals(2, current("devb"));
        assertEquals(4, statistics().totalEvents());
    }

    private int transferStatus(ReplayReopenService instance, CountTransferRequest request) {
        try {
            instance.transferCounts(request, actor);
            return 200;
        } catch (ReplayReopenException failure) {
            return failure.status();
        }
    }
}
