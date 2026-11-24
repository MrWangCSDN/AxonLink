package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReplayReopenCountTransferControllerTest {
    private static final String BASE = "/api/ai/parallel-replay/issues/reopens";
    private JdbcTemplate jdbc;
    private SysUserDao users;
    private UserPrincipalResolver resolver;
    private MockMvc mvc;
    private ReplayReopenProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() {
        jdbc = ReplayReopenTestSupport.database();
        users = new SysUserDao(jdbc);
        properties = new ReplayReopenProperties();
        properties.setEnabled(true);
        properties.setAdminEmpNos(List.of("900"));
        new Binder(new MapConfigurationPropertySource(Map.of(
                "dii.replay.issue-reopen.count-transfer-emp-nos[0]", "101")))
                .bind("dii.replay.issue-reopen", Bindable.ofInstance(properties));
        ReplayReopenDao events = new ReplayReopenDao(jdbc);
        ReplayReopenWorkflowDao store = new ReplayReopenWorkflowDao(events);
        ReplayReopenPermissionService permissions = new ReplayReopenPermissionService(
                properties, users, store, new ReplayTransactionPersonDao(jdbc));
        ReplayReopenService service = new ReplayReopenService(events, store, permissions, null, properties);
        ReplayReopenTestSupport.event(jdbc, 1, "ISS-A", "RPT20261021-01", "deva", "开发甲", "公共组");
        ReplayReopenTestSupport.event(jdbc, 2, "ISS-A", "RPT20261022-01", "deva", "开发甲", "公共组");
        resolver = mock(UserPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));
        mvc = MockMvcBuilders.standaloneSetup(new ReplayReopenController(service, resolver)).build();
    }

    @AfterEach
    void close() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    private void login(String username) {
        when(resolver.resolve(any())).thenReturn(
                new UserPrincipalResolver.Resolved("LDAP", username, users.findByUsername(username)));
    }

    private String request(String requestId, Object count, List<String> batches) throws Exception {
        return json.writeValueAsString(Map.of(
                "requestId", requestId,
                "replayType", "QUERY",
                "batchNames", batches,
                "sourceOwnerUsername", "deva",
                "targetOwnerUsername", "devb",
                "count", count,
                "reason", "核实后调整次数"));
    }

    @Test
    void transferPermissionUsesItsOwnConfiguredActiveEmployeeNumbers() throws Exception {
        mvc.perform(get(BASE + "/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canTransferCounts").value(false));
        login("admin");
        mvc.perform(get(BASE + "/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canManagePermissions").value(true))
                .andExpect(jsonPath("$.data.canTransferCounts").value(false));
        login("deva");
        mvc.perform(get(BASE + "/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canTransferCounts").value(true));
    }

    @Test
    void ldapUserWithoutEmployeeNumberCanTransferAndIsRecheckedAfterIdentityChanges() throws Exception {
        ReplayReopenTestSupport.user(jdbc,"c-wangsh8","无工号用户",null,1);
        properties.setCountTransferEmpNos(List.of("c-wangsh8"));
        login("c-wangsh8");
        mvc.perform(get(BASE + "/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentUser.username").value("c-wangsh8"))
                .andExpect(jsonPath("$.data.canTransferCounts").value(true))
                .andExpect(jsonPath("$.data.canManagePermissions").value(false));

        String id = UUID.randomUUID().toString();
        String body = request(id,1,List.of("RPT20261022-01"));
        mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
        mvc.perform(get(BASE + "/events/2"))
                .andExpect(jsonPath("$.data.event.currentOwnerUsername").value("devb"));
        mvc.perform(get(BASE + "/statistics"))
                .andExpect(jsonPath("$.data.totalEvents").value(2));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE action='COUNT_TRANSFERRED' "
                        + "AND operator_username='c-wangsh8'", Integer.class));

        // A newly assigned employee number takes precedence over the existing username entry,
        // including an otherwise idempotent retry of a previously successful transfer.
        jdbc.update("UPDATE ccbs_ai_sys_user SET emp_no='NEW-EMP' WHERE username='c-wangsh8'");
        mvc.perform(get(BASE + "/permissions"))
                .andExpect(jsonPath("$.data.canTransferCounts").value(false));
        mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_reopen_audit WHERE action='COUNT_TRANSFERRED'", Integer.class));
    }

    @Test
    void anonymousAndUnlistedUsersCannotTransferEvenWithSpoofedActorData() throws Exception {
        String body = request(UUID.randomUUID().toString(), 1, List.of());
        mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        login("admin");
        mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        login("inactive");
        mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizedCountTransferUsesSelectedBatchesAndRetriesDoNotMoveAnotherEvent() throws Exception {
        login("deva");
        String id = UUID.randomUUID().toString();
        String body = request(id, 1, List.of("RPT20261022-01"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.requestId").value(id))
                    .andExpect(jsonPath("$.data.count").value(1))
                    .andExpect(jsonPath("$.data.sourceOwnerUsername").value("deva"))
                    .andExpect(jsonPath("$.data.targetOwnerUsername").value("devb"))
                    .andExpect(jsonPath("$.data.occurredAt").exists())
                    .andExpect(jsonPath("$.data.eventIds").doesNotExist());
        }
        mvc.perform(get(BASE + "/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalEvents").value(2))
                .andExpect(jsonPath("$.data.people[0].currentCount").value(1))
                .andExpect(jsonPath("$.data.people[1].currentCount").value(1));
        mvc.perform(get(BASE + "/events/1"))
                .andExpect(jsonPath("$.data.event.currentOwnerUsername").value("deva"));
        mvc.perform(get(BASE + "/events/2"))
                .andExpect(jsonPath("$.data.event.currentOwnerUsername").value("devb"));
    }

    @Test
    void amountsAndCrossTypeBatchSelectionsAreValidated() throws Exception {
        login("deva");
        for (Object invalid : List.of(0, -1, 10001, 1.5, new java.math.BigInteger("99999999999999999999999"), Double.NaN)) {
            mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON)
                            .content(request(UUID.randomUUID().toString(), invalid, List.of())))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(BASE + "/count-transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID().toString(), 1, List.of("DZ20261022-01"))))
                .andExpect(status().isBadRequest());
    }
}
