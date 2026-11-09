package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.reopen.ReplayReopenDtos.Filter;
import com.axonlink.ai.replay.reopen.ReplayReopenWorkflowDtos.*;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReplayReopenServiceTest {
    private JdbcTemplate jdbc;
    private ReplayReopenDao events;
    private ReplayReopenService service;
    private ReplayReopenProperties properties;
    private final ReplayIssueOperator owner = new ReplayIssueOperator("deva", "开发甲");
    private final ReplayIssueOperator leader = new ReplayIssueOperator("leader", "领导");
    private final ReplayIssueOperator admin = new ReplayIssueOperator("admin", "权限管理员");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        jdbc = ReplayReopenTestSupport.database();
        properties = new ReplayReopenProperties();
        properties.setEnabled(true);
        properties.setAdminEmpNos(List.of("900"));
        events = new ReplayReopenDao(jdbc);
        ReplayReopenWorkflowDao store = new ReplayReopenWorkflowDao(events);
        SysUserDao users = new SysUserDao(jdbc);
        ReplayTransactionPersonDao persons = new ReplayTransactionPersonDao(jdbc);
        ReplayReopenPermissionService permissions = new ReplayReopenPermissionService(properties, users, store, persons);
        service = new ReplayReopenService(events, store, permissions, null, properties, clock);
        service.savePolicy("公共组", new PolicyRequest(0L, List.of("leader", "backup"), "leader", "指定审批领导"), admin);
        ReplayReopenTestSupport.event(jdbc, 1, "ISS-A", "RPT20261017-01", "deva", "开发甲", "公共组");
        ReplayReopenTestSupport.event(jdbc, 2, "ISS-A", "RPT20261022-01", "deva", "开发甲", "公共组");
        ReplayReopenTestSupport.event(jdbc, 3, "ISS-B", "RPT20261021-01", "deva", "开发甲", "公共组");
    }

    @AfterEach
    void closeDatabase() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    private CreateAppealRequest request(List<Long> ids, String kind, String evidence) {
        Map<String,Long> versions = ids.stream().collect(java.util.stream.Collectors.toMap(String::valueOf, id -> events.findEvent(id).version()));
        return new CreateAppealRequest(UUID.randomUUID().toString(), ids, versions, kind, "devb", "共用组件遗漏，申请调整责任", evidence);
    }

    private Filter all() { return new Filter(null,null,null,"ALL",null,null,false,1,20); }

    private void error(int status, Runnable work) {
        assertEquals(status, assertThrows(ReplayReopenException.class, work::run).status());
    }

    @Test
    void crossBatchApprovalMovesEverySelectedEventAndKeepsIssueAndBatchTotals() {
        AppealView appeal = service.createAppeal(request(List.of(1L, 3L), "APPEAL", "日志 A 和日志 B"), owner);
        assertEquals("PENDING", appeal.status());
        assertEquals("deva", events.findEvent(1).currentOwnerUsername());
        assertEquals("deva", events.findEvent(3).currentOwnerUsername());
        assertEquals(2, service.statistics(all(), owner).pendingEvents());

        AppealView approved = service.decide(appeal.id(), new DecisionRequest(1L,"APPROVE","devc","核查后最终确认为开发丙负责"), leader);

        assertEquals("APPROVED", approved.status());
        assertEquals("devb", approved.suggestedOwnerUsername());
        assertEquals("devc", approved.finalOwnerUsername());
        assertEquals("devc", events.findEvent(1).currentOwnerUsername());
        assertEquals("devc", events.findEvent(3).currentOwnerUsername());
        assertEquals("RPT20261017-01", events.findEvent(1).batchName());
        assertEquals(3, service.statistics(all(), owner).totalEvents());
        assertEquals(Map.of("ISS-A",2L,"ISS-B",1L), service.issueCounts(List.of("ISS-A","ISS-B")));
        assertTrue(service.history(1,1,20).items().stream().anyMatch(a -> "APPEAL_APPROVED".equals(a.action()) && "devc".equals(a.toOwnerUsername()) && "核查后最终确认为开发丙负责".equals(a.reason())));
        assertTrue(service.statistics(all(), owner).people().stream().anyMatch(p -> "devc".equals(p.username()) && p.currentCount()==2 && p.adjustedIn()==2));
    }

    @Test
    void rejectsAnonymousInactiveUnknownNonOwnerAndUnassignedLeaders() {
        CreateAppealRequest body=request(List.of(1L),"APPEAL","有效日志");
        error(401,()->service.createAppeal(body,null));
        error(401,()->service.createAppeal(body,new ReplayIssueOperator("inactive","停用")));
        error(401,()->service.createAppeal(body,new ReplayIssueOperator("dii-token","口令")));
        error(403,()->service.createAppeal(body,new ReplayIssueOperator("devb","开发乙")));
        AppealView a=service.createAppeal(body,owner);
        error(403,()->service.decide(a.id(),new DecisionRequest(1L,"APPROVE","devb","同意"),admin));
        error(403,()->service.decide(a.id(),new DecisionRequest(1L,"APPROVE","devb","同意"),new ReplayIssueOperator("backup","备选领导")));
        assertEquals("deva",events.findEvent(1).currentOwnerUsername());
    }

    @Test
    void requiresReasonsEvidenceAndExplicitDeveloperSelections() {
        error(400,()->service.createAppeal(new CreateAppealRequest(UUID.randomUUID().toString(),List.of(1L),Map.of("1",1L),"APPEAL",null,"原因","证据"),owner));
        error(400,()->service.createAppeal(new CreateAppealRequest(UUID.randomUUID().toString(),List.of(1L),Map.of("1",1L),"APPEAL","devb"," ","证据"),owner));
        error(400,()->service.createAppeal(new CreateAppealRequest(UUID.randomUUID().toString(),List.of(1L),Map.of("1",1L),"APPEAL","devb","原因"," "),owner));
        error(400,()->service.createAppeal(new CreateAppealRequest(UUID.randomUUID().toString(),List.of(1L),Map.of("1",1L),"APPEAL","leader","原因","证据"),owner));
        AppealView a=service.createAppeal(request(List.of(1L),"APPEAL","日志"),owner);
        error(400,()->service.decide(a.id(),new DecisionRequest(1L,"APPROVE",null,"确认"),leader));
        error(400,()->service.decide(a.id(),new DecisionRequest(1L,"APPROVE","devb"," "),leader));
        error(400,()->service.decide(a.id(),new DecisionRequest(1L,"APPROVE","deva","确认"),leader));
    }

    @Test
    void sameRequestIdRetriesAreIdempotentButDifferentPayloadAndDuplicatePendingConflict() {
        CreateAppealRequest body=request(List.of(1L),"APPEAL","证据甲");
        AppealView first=service.createAppeal(body,owner);
        assertEquals(first.id(),service.createAppeal(body,owner).id());
        error(409,()->service.createAppeal(new CreateAppealRequest(body.requestId(),body.eventIds(),body.expectedVersions(),body.kind(),body.suggestedOwnerUsername(),"不同原因",body.evidence()),owner));
        error(409,()->service.createAppeal(request(List.of(1L),"APPEAL","另一份证据"),owner));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_appeal",Integer.class));
    }

    @Test
    void staleEventInvalidatesWholeBatchAndClearsPendingWithoutPartialMove() {
        AppealView a=service.createAppeal(request(List.of(1L,2L),"APPEAL","两条日志"),owner);
        jdbc.update("UPDATE dii_replay_reopen_event SET version=version+1 WHERE id=2");
        AppealView result=service.decide(a.id(),new DecisionRequest(1L,"APPROVE","devb","同意调整"),leader);
        assertEquals("INVALID",result.status());
        assertEquals("deva",events.findEvent(1).currentOwnerUsername());
        assertEquals("deva",events.findEvent(2).currentOwnerUsername());
        assertNull(events.findEvent(1).pendingAppealId());
        assertNull(events.findEvent(2).pendingAppealId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE action='APPEAL_APPROVED'",Integer.class));
    }

    @Test
    void withdrawAndRejectPreserveAttributionAndRequireNewEvidenceForAnyLaterDecisionHistory() {
        AppealView withdrawn=service.createAppeal(request(List.of(2L),"APPEAL","撤回日志"),owner);
        error(403,()->service.withdraw(withdrawn.id(),new WithdrawRequest(1L,"撤回"),leader));
        assertEquals("WITHDRAWN",service.withdraw(withdrawn.id(),new WithdrawRequest(1L,"补充材料后再提交"),owner).status());
        assertNull(events.findEvent(2).pendingAppealId());

        AppealView first=service.createAppeal(request(List.of(1L),"APPEAL","证据 A"),owner);
        service.decide(first.id(),new DecisionRequest(1L,"APPROVE","devb","同意 A"),leader);
        ReplayIssueOperator newOwner=new ReplayIssueOperator("devb","开发乙");
        CreateAppealRequest review=new CreateAppealRequest(UUID.randomUUID().toString(),List.of(1L),Map.of("1",2L),"RECHECK","deva","提出复核","证据 B");
        AppealView second=service.createAppeal(review,newOwner);
        service.decide(second.id(),new DecisionRequest(1L,"REJECT",null,"证据 B 不足"),leader);
        error(400,()->service.createAppeal(new CreateAppealRequest(UUID.randomUUID().toString(),List.of(1L),Map.of("1",2L),"RECHECK","deva","重复旧证据"," 证据　Ａ "),newOwner));
        assertEquals("devb",events.findEvent(1).currentOwnerUsername());
        assertEquals("REJECTED",service.event(1,newOwner).latestAppeal().status());
    }

    @Test
    void revokedLeaderCannotDecideAndAdminCanExplicitlyReassignToAnotherAuthorizedLeader() {
        AppealView a=service.createAppeal(request(List.of(1L),"APPEAL","日志"),owner);
        service.savePolicy("公共组",new PolicyRequest(1L,List.of("backup"),"backup","原领导离岗"),admin);
        error(403,()->service.decide(a.id(),new DecisionRequest(1L,"APPROVE","devb","同意"),leader));
        AppealView reassigned=service.reassign(a.id(),new ReassignRequest(1L,"backup","原领导离岗，显式转派"),admin);
        assertEquals("backup",reassigned.leaderUsername());
        assertEquals("APPROVED",service.decide(a.id(),new DecisionRequest(2L,"APPROVE","devb","核查后同意"),new ReplayIssueOperator("backup","备选领导")).status());
    }

    @Test
    void pendingUnassignedEventCanOnlyBeAssignedByAuthorizedLeaderWithAudit() {
        ReplayReopenTestSupport.event(jdbc,4,"ISS-B","RPT20261020-01",null,null,"公共组");
        error(403,()->service.assign(4,new AssignRequest(1L,"deva","确认主责"),admin));
        service.assign(4,new AssignRequest(1L,"deva","根据修复记录确认主责"),leader);
        assertNull(events.findEvent(4).defaultOwnerUsername());
        assertEquals("deva",events.findEvent(4).currentOwnerUsername());
        assertEquals(4,service.statistics(all(),owner).totalEvents());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE event_id=4 AND action='OWNER_ASSIGNED'",Integer.class));
        error(409,()->service.assign(4,new AssignRequest(2L,"devb","不得重写已有归属"),leader));
    }

    @Test
    void onlyMineWithoutIdentityDoesNotReturnEveryoneAndPageSizeDoesNotLimitStatistics() {
        Filter own=new Filter(null,null,null,"ALL",null,null,true,1,1);
        assertEquals(0,service.events(own,null).total());
        assertEquals(1,service.events(new Filter(null,null,null,"ALL",null,null,false,1,1),owner).items().size());
        assertEquals(3,service.statistics(new Filter(null,null,null,"ALL",null,null,false,1,1),owner).totalEvents());
    }

    @Test
    void concurrentAppealsOnSameEventOnlyCreateOnePendingRequest() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        var calls=List.of(request(List.of(1L),"APPEAL","并发日志甲"),request(List.of(1L),"APPEAL","并发日志乙"));
        try {
            var futures=calls.stream().map(body->pool.submit(()->{start.await();try{service.createAppeal(body,owner);return 200;}catch(ReplayReopenException ex){return ex.status();}})).toList();
            start.countDown();
            var results=List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(code->code==200).count());
            assertEquals(1,results.stream().filter(code->code==409).count());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_appeal WHERE status='PENDING'",Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void idempotentRetryRacingWithDecisionNeverDuplicatesOrPartiallyTransfers() throws Exception {
        CreateAppealRequest body=request(List.of(1L,2L),"APPEAL","两个批次的修复日志");
        AppealView first=service.createAppeal(body,owner);
        var pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            var retry=pool.submit(()->{start.await();return service.createAppeal(body,owner);});
            var decision=pool.submit(()->{start.await();return service.decide(first.id(),new DecisionRequest(1L,"APPROVE","devb","核实两个批次后同意调整"),leader);});
            start.countDown();
            assertEquals(first.id(),retry.get(15,TimeUnit.SECONDS).id());
            assertEquals("APPROVED",decision.get(15,TimeUnit.SECONDS).status());
            assertEquals(first.id(),service.createAppeal(body,owner).id());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_appeal",Integer.class));
            for(long id:List.of(1L,2L)) {
                assertEquals("devb",events.findEvent(id).currentOwnerUsername());
                assertNull(events.findEvent(id).pendingAppealId());
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE event_id=? AND action='APPEAL_APPROVED'",Integer.class,id));
            }
            assertEquals(3,service.statistics(all(),owner).totalEvents());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void myAppealsContinueToIncludeRequestsAfterTheirAttributionMovesAway() {
        AppealView a=service.createAppeal(request(List.of(1L),"APPEAL","日志"),owner);
        service.decide(a.id(),new DecisionRequest(1L,"APPROVE","devb","确认调整"),leader);
        Filter mine=new Filter(null,null,null,"ALL",null,null,true,1,20);
        assertEquals(1,service.appeals(mine,null,true,false,owner).total());
        assertEquals(0,service.appeals(mine,null,true,false,new ReplayIssueOperator("devb","开发乙")).total());
        assertEquals(2,service.events(mine,owner).total());
    }

    @Test
    void revokingAllLeadersBlocksNewAppealsAndExistingDecisions() {
        AppealView a=service.createAppeal(request(List.of(1L),"APPEAL","日志"),owner);
        service.savePolicy("公共组",new PolicyRequest(1L,List.of(),null,"撤销全部领域授权"),admin);
        assertFalse(service.appeal(a.id(),leader).canDecide());
        error(403,()->service.decide(a.id(),new DecisionRequest(1L,"APPROVE","devb","同意"),leader));
        error(409,()->service.createAppeal(request(List.of(2L),"APPEAL","新日志"),owner));
        assertEquals("deva",events.findEvent(1).currentOwnerUsername());
    }

    @Test
    void newAppealSeesDecisionCommittedAfterItsInitialReadEvenOnRepeatableReadDatabase() throws Exception {
        AppealView first=service.createAppeal(request(List.of(1L),"APPEAL","原证据"),owner);
        CountDownLatch initialRead=new CountDownLatch(1),decisionCommitted=new CountDownLatch(1);
        var repeatableReadSource=new DelegatingDataSource(jdbc.getDataSource()) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection=super.getConnection();
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
            }
        };
        var delayedEvents=new ReplayReopenDao(new JdbcTemplate(repeatableReadSource)) {
            @Override public List<ReplayReopenDtos.EventRow> lockEvents(List<Long> ids) {
                initialRead.countDown();
                try {assertTrue(decisionCommitted.await(10,TimeUnit.SECONDS));}
                catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                return super.lockEvents(ids);
            }
        };
        var delayedStore=new ReplayReopenWorkflowDao(delayedEvents);
        var delayedPermissions=new ReplayReopenPermissionService(properties,new SysUserDao(delayedEvents.jdbc()),delayedStore,new ReplayTransactionPersonDao(delayedEvents.jdbc()));
        var delayedService=new ReplayReopenService(delayedEvents,delayedStore,delayedPermissions,null,properties,clock);
        CreateAppealRequest duplicateEvidence=request(List.of(1L),"APPEAL","原证据");
        var pool=Executors.newSingleThreadExecutor();
        try {
            var submission=pool.submit(()->{
                try {delayedService.createAppeal(duplicateEvidence,owner);return 200;}
                catch(ReplayReopenException ex) {return ex.status();}
            });
            assertTrue(initialRead.await(10,TimeUnit.SECONDS));
            service.decide(first.id(),new DecisionRequest(1L,"REJECT",null,"证据不足，驳回"),leader);
            decisionCommitted.countDown();
            assertEquals(400,submission.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_appeal",Integer.class));
            assertNull(events.findEvent(1).pendingAppealId());
        } finally {decisionCommitted.countDown();pool.shutdownNow();}
    }

    @Test
    void disabledFeatureReturnsStatusWithoutQueryingReopenTablesAndBlocksWrites() {
        properties.setEnabled(false);
        assertFalse(service.permissions(owner).enabled());
        error(503,()->service.createAppeal(request(List.of(1L),"APPEAL","日志"),owner));
    }
}
