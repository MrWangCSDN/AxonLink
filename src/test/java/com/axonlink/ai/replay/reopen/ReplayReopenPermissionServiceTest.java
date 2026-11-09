package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReplayReopenPermissionServiceTest {
    private JdbcTemplate jdbc;
    private ReplayReopenProperties properties;
    private ReplayReopenWorkflowDao store;
    private ReplayReopenPermissionService permissions;
    private final LocalDateTime now = LocalDateTime.of(2026,10,22,10,0);

    @BeforeEach void setup() {
        jdbc = ReplayReopenTestSupport.database();
        properties = new ReplayReopenProperties();
        store = new ReplayReopenWorkflowDao(new ReplayReopenDao(jdbc));
        permissions = new ReplayReopenPermissionService(properties, new SysUserDao(jdbc), store, new ReplayTransactionPersonDao(jdbc));
    }
    private ReplayIssueOperator op(String name) { return new ReplayIssueOperator(name,"untrusted display name"); }
    private void failure(int status, Runnable action) { assertEquals(status, assertThrows(ReplayReopenException.class, action::run).status()); }

    @Test void adminRequiresConfiguredActualEmployeeNumberAndActiveIdentity() {
        assertFalse(permissions.canManage(op("admin")));
        properties.setAdminEmpNos(List.of("admin", "inactive", "901"));
        assertFalse(permissions.canManage(op("admin")));
        assertFalse(permissions.canManage(op("inactive")));
        properties.setAdminEmpNos(List.of("900"));
        assertTrue(permissions.canManage(op("admin")));
        assertEquals("admin", permissions.requireAdmin(op("admin")).getUsername());
        failure(403, () -> permissions.requireAdmin(op("deva")));
        failure(401, () -> permissions.requireActive(op("inactive")));
        failure(401, () -> permissions.requireActive(op("token-only")));
        assertNull(permissions.active(null));
        assertNull(permissions.option(null));
        assertEquals("开发甲(deva)", permissions.option(permissions.requireActive(op("deva"))).displayName());
    }

    @Test void adminDoesNotGainLeaderPermissionAndPolicyChangesAreEffectiveImmediately() {
        properties.setAdminEmpNos(List.of("900"));
        store.savePolicy("公共组", List.of("leader", "backup"), "leader", 0, "admin", now);
        assertFalse(permissions.isLeader("公共组", op("admin")));
        failure(403, () -> permissions.requireLeader("公共组",op("admin")));
        assertEquals("leader", permissions.requireLeader("公共组",op("leader")).getUsername());
        assertEquals("leader", permissions.primaryLeader("公共组").getUsername());
        store.savePolicy("公共组",List.of("backup"),"backup",1,"admin",now);
        assertFalse(permissions.isLeader("公共组",op("leader")));
        failure(403, () -> permissions.requireLeader("公共组",op("leader")));
        jdbc.update("UPDATE ccbs_ai_sys_user SET status=0 WHERE username='backup'");
        failure(409, () -> permissions.primaryLeader("公共组"));
        failure(409, () -> permissions.primaryLeader("未配置"));
    }

    @Test void developerMembershipUsesExactUsernamesAndSearchesTheWholeDirectory() {
        ReplayReopenTestSupport.user(jdbc,"dev","开发甲","deva",1);
        ReplayReopenTestSupport.user(jdbc,"collision","deva","devb",1);
        for (int i=0;i<60;i++) ReplayReopenTestSupport.user(jdbc,"ordinary"+i,"AAA"+i,"x"+i,1);
        jdbc.update("UPDATE dii_replay_transaction_person SET developer_usernames=?", "deva, devb、devc;inactive；missing，deva");
        assertEquals("deva", permissions.requireDeveloper("deva").getUsername());
        failure(400, () -> permissions.requireDeveloper("dev"));
        failure(400, () -> permissions.requireDeveloper("collision"));
        failure(400, () -> permissions.requireDeveloper("inactive"));
        failure(400, () -> permissions.requireDeveloper(null));
        assertEquals(java.util.Set.of("deva","devb","devc"), new java.util.HashSet<>(permissions.developers(null,50).stream().map(ReplayReopenDtos.UserOption::username).toList()));
        assertEquals(List.of("devb"),permissions.developers("开发乙",20).stream().map(ReplayReopenDtos.UserOption::username).toList());
        assertEquals(1, permissions.developers("dev",1).size());
        jdbc.update("UPDATE dii_replay_transaction_person SET developer_usernames='devb'");
        failure(400, () -> permissions.requireDeveloper("deva"));
    }
}
