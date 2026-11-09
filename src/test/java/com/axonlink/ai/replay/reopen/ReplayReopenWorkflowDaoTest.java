package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;
import static com.axonlink.ai.replay.reopen.ReplayReopenWorkflowDtos.*;

class ReplayReopenWorkflowDaoTest {
    private JdbcTemplate jdbc;
    private ReplayReopenDao events;
    private ReplayReopenWorkflowDao store;
    private SysUserDao users;
    private final LocalDateTime now=LocalDateTime.of(2026,10,22,10,0);
    @BeforeEach void setup() {
        jdbc=ReplayReopenTestSupport.database(); events=new ReplayReopenDao(jdbc);
        store=new ReplayReopenWorkflowDao(events); users=new SysUserDao(jdbc);
        ReplayReopenTestSupport.event(jdbc,1,"ISS-A","RPT-A","deva","开发甲","公共组");
        ReplayReopenTestSupport.event(jdbc,2,"ISS-A","RPT-B","deva","开发甲","公共组");
        ReplayReopenTestSupport.event(jdbc,3,"ISS-B","DZ-A","devb","开发乙","结算组");
        jdbc.update("UPDATE dii_replay_reopen_event SET replay_type='DZ' WHERE id=3");
    }
    private void conflict(Runnable action) { assertEquals(409,assertThrows(ReplayReopenException.class,action::run).status()); }
    private long appeal(String requestId, long... ids) {
        CreateAppealRequest body=new CreateAppealRequest(requestId,List.of(1L),Map.of("1",1L),"APPEAL","devb","调整原因","证据");
        long id=store.insertAppeal(body,"f".repeat(64),"公共组",users.findByUsername("deva"),users.findByUsername("devb"),users.findByUsername("leader"),"e".repeat(64),now);
        for(long eventId:ids) store.insertItem(id,events.findEvent(eventId));
        return id;
    }
    private Filter filter(String key,String batch,String domain,String type,String owner,Long id,int page,int size) { return new Filter(key,batch,domain,type,owner,id,false,page,size); }

    @Test void policyVersionGuardsCreationAndConcurrentUpdates() {
        assertNull(store.findPolicy("公共组",false));
        conflict(() -> store.savePolicy("公共组",List.of("leader"),"leader",1,"admin",now));
        assertEquals(1,store.savePolicy("公共组",List.of("leader","backup"),"leader",0,"admin",now).version());
        assertEquals(List.of("leader","backup"),store.findPolicy("公共组",true).leaderUsernames());
        conflict(() -> store.savePolicy("公共组",List.of("backup"),"backup",0,"admin",now));
        assertEquals(2,store.savePolicy("公共组",List.of("backup"),"backup",1,"admin",now).version());
        assertEquals(1,store.policies().size());
    }

    @Test void lockedPolicyAndVersionPreventAConcurrentOverwrite() throws Exception {
        store.savePolicy("公共组",List.of("leader"),"leader",0,"admin",now);
        var workers=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1), updating=new CountDownLatch(1);
        try {
            var first=workers.submit(() -> events.inTransaction(() -> {
                store.findPolicy("公共组",true);
                locked.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                return store.savePolicy("公共组",List.of("backup"),"backup",1,"admin",now);
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var second=workers.submit(() -> {
                updating.countDown();
                return events.inTransaction(() -> store.savePolicy("公共组",List.of("leader"),"leader",1,"admin",now));
            });
            assertTrue(updating.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100,TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(2,first.get(5,TimeUnit.SECONDS).version());
            var failure=assertThrows(java.util.concurrent.ExecutionException.class, () -> second.get(5,TimeUnit.SECONDS));
            assertEquals(409,((ReplayReopenException)failure.getCause()).status());
            assertEquals("backup",store.findPolicy("公共组",false).primaryLeaderUsername());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void appealTransitionsPreserveSnapshotsAndRejectStaleOrClosedWrites() {
        long id=appeal("request-1",1,2);
        AppealRow row=store.findAppeal(id,true);
        assertEquals("PENDING",row.status()); assertEquals("e".repeat(64),row.evidenceHash());
        assertEquals(id,store.findByRequestId("request-1").id());
        assertNull(store.findAppeal(987,false)); assertNull(store.findByRequestId("absent"));
        assertEquals(2,store.items(id).size()); assertEquals("RPT-A",store.items(id).get(0).batchName());
        assertEquals("deva",store.items(id).get(0).beforeOwnerUsername());
        assertEquals(id,store.latestAppeal(1).id()); assertFalse(store.hasDecision(1)); assertFalse(store.hasEvidenceHash(1,"e".repeat(64)));
        store.reassignAppeal(id,1,"backup","备选领导",now);
        conflict(() -> store.decideAppeal(id,1,"APPROVED","devb","开发乙","同意",now));
        store.decideAppeal(id,2,"APPROVED","devc","开发丙","最终责任确认",now);
        row=store.findAppeal(id,false);
        assertEquals(3,row.version()); assertEquals("backup",row.leaderUsername()); assertEquals("devc",row.finalOwnerUsername());
        assertEquals("最终责任确认",row.decisionReason()); assertEquals(now,row.decidedAt());
        assertTrue(store.hasDecision(1)); assertTrue(store.hasEvidenceHash(2,"e".repeat(64)));
        assertFalse(store.hasEvidenceHash(1,"other"));
        conflict(() -> store.reassignAppeal(id,3,"leader","领导",now));
        conflict(() -> store.closeAppeal(id,3,"WITHDRAWN","撤回",now));
        conflict(() -> store.decideAppeal(id,3,"REJECTED",null,null,"驳回",now));
        long newer=appeal("request-2",1);
        store.closeAppeal(newer,1,"WITHDRAWN","用户撤回",now);
        assertEquals(newer,store.latestAppeal(1).id()); assertEquals("用户撤回",store.findAppeal(newer,false).closeReason());
        assertTrue(store.hasEvidenceHash(1,"e".repeat(64)));
    }

    @Test void absentEvidenceHashDoesNotMatchAnUnrelatedHistoricalDecision() {
        long id=appeal("decided",1);
        store.decideAppeal(id,1,"REJECTED",null,null,"证据不足",now);
        assertFalse(store.hasEvidenceHash(1,null));
        assertFalse(store.hasEvidenceHash(1,""));
        assertTrue(store.hasDecision(1));
    }

    @Test void parentPagingUsesAllMatchingItemsWithoutDuplicatesAndExactFilters() {
        long first=appeal("first",1,2), second=appeal("second",2), third=appeal("third",3);
        assertEquals(List.of(third,second,first),store.appeals(null,null,null,null).items().stream().map(AppealRow::id).toList());
        Page<AppealRow> page=store.appeals(filter("ISS-A",null,null,"ALL",null,null,1,1),"PENDING","deva","leader");
        assertEquals(2,page.total()); assertEquals(List.of(second),page.items().stream().map(AppealRow::id).toList());
        assertEquals(List.of(first),store.appeals(filter("ISS-A",null,null,null,null,null,2,1),null,null,null).items().stream().map(AppealRow::id).toList());
        assertEquals(1,store.appeals(filter(null,"RPT-A","公共组","QUERY","deva",1L,1,20),null,null,null).total());
        assertEquals(0,store.appeals(filter(null,"RPT-A",null,null,"devb",null,1,20),null,null,null).total());
        assertEquals(1,store.appeals(filter(null,null,"结算组","DZ",null,null,1,20),null,null,null).total());
        assertEquals(0,store.appeals(null,null,"devb",null).total());
        assertEquals(200,store.appeals(filter(null,null,null,null,null,null,-1,999),null,null,null).pageSize());
        assertEquals(1,store.appeals(filter(null,null,null,null,null,null,-1,999),null,null,null).page());
    }

    @Test void historyReturnsEveryPageNewestFirstWithoutCrossEventLeakage() {
        for(int i=0;i<5;i++) events.appendAudit(new AuditEntry(0,1L,null,"TEST","deva","开发甲",now,"reason-"+i,null,null,null,null,null),"公共组");
        events.appendAudit(new AuditEntry(0,2L,null,"OTHER","deva","开发甲",now,"other",null,null,null,null,null),"公共组");
        Page<AuditEntry> page=store.history(1,1,2);
        assertEquals(5,page.total()); assertEquals(List.of("reason-4","reason-3"),page.items().stream().map(AuditEntry::reason).toList());
        assertEquals(List.of("reason-0"),store.history(1,3,2).items().stream().map(AuditEntry::reason).toList());
        assertEquals(20,store.history(1,0,0).pageSize());
    }
}
