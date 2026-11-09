package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.reopen.ReplayReopenWorkflowDtos.*;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class ReplayReopenControllerTest {
    private static final String BASE="/api/ai/parallel-replay/issues/reopens";
    private JdbcTemplate jdbc;
    private SysUserDao users;
    private ReplayReopenProperties properties;
    private UserPrincipalResolver resolver;
    private MockMvc mvc;
    private final ObjectMapper json=new ObjectMapper();

    @BeforeEach
    void setup() {
        jdbc=ReplayReopenTestSupport.database();users=new SysUserDao(jdbc);
        properties=new ReplayReopenProperties();properties.setEnabled(true);properties.setAdminEmpNos(List.of("900"));
        ReplayReopenDao events=new ReplayReopenDao(jdbc);ReplayReopenWorkflowDao store=new ReplayReopenWorkflowDao(events);
        ReplayTransactionPersonDao persons=new ReplayTransactionPersonDao(jdbc);
        ReplayReopenService service=new ReplayReopenService(events,store,new ReplayReopenPermissionService(properties,users,store,persons),
                new ReplayReopenRecorder(new ReplayIssueDao(jdbc),persons,users,events,properties),properties);
        service.savePolicy("公共组",new PolicyRequest(0L,List.of("leader"),"leader","设定审批领导"),new ReplayIssueOperator("admin","权限管理员"));
        ReplayReopenTestSupport.event(jdbc,1,"ISS-A","RPT20261022-01","deva","开发甲","公共组");
        resolver=mock(UserPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS",null,null));
        mvc=MockMvcBuilders.standaloneSetup(new ReplayReopenController(service,resolver)).build();
    }

    @AfterEach
    void shutdown() {if(jdbc!=null)jdbc.execute("SHUTDOWN");}

    private void login(String username,String authMethod,String principal) {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved(authMethod,principal,users.findByUsername(username)));
    }

    private String application() throws Exception {
        return json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"eventIds",List.of(1),
                "expectedVersions",Map.of("1",1),"kind","APPEAL","suggestedOwnerUsername","devb","reason","共用组件修复遗漏","evidence","回放日志和代码提交",
                "applicantUsername","admin","leaderUsername","admin"));
    }

    @Test
    void anonymousMutationCannotSelfReportAnApplicantOrLeader() throws Exception {
        mvc.perform(post(BASE+"/appeals").contentType(MediaType.APPLICATION_JSON).content(application()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    void ldapAndUiasUseResolvedActiveUsernameAndIgnoreBodyIdentity() throws Exception {
        login("deva","UIAS","101");
        mvc.perform(post(BASE+"/appeals").contentType(MediaType.APPLICATION_JSON).content(application()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.applicantUsername").value("deva"))
                .andExpect(jsonPath("$.data.leaderUsername").value("leader"));
        login("deva","LDAP","deva");
        mvc.perform(get(BASE+"/events").param("onlyMine","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].event.issueKey").value("ISS-A"))
                .andExpect(jsonPath("$.data.items[0].event.pendingAppealId").isNumber());
    }

    @Test
    void inactiveResolvedUserIsStillRejected() throws Exception {
        login("inactive","LDAP","inactive");
        mvc.perform(post(BASE+"/appeals").contentType(MediaType.APPLICATION_JSON).content(application()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicReadContractHasLifetimeCountsAndStructuredFilterErrors() throws Exception {
        mvc.perform(post(BASE+"/issue-counts").contentType(MediaType.APPLICATION_JSON).content("{\"issueKeys\":[\"ISS-A\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ISS-A").value(1));
        mvc.perform(get(BASE+"/statistics").param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(1));
        mvc.perform(get(BASE+"/events").param("replayType","BAD"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
        mvc.perform(get(BASE+"/events").param("pageSize","bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
        mvc.perform(get(BASE+"/events/999/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sourceImportRoundSeparatesRepeatedReopensWithinOneBatchAcrossLedgerStatisticsAndAppeals() throws Exception {
        ReplayReopenTestSupport.event(jdbc,2,"ISS-A","RPT20261022-01","deva","开发甲","公共组");
        login("deva","LDAP","deva");
        mvc.perform(post(BASE+"/appeals").contentType(MediaType.APPLICATION_JSON).content(application()))
                .andExpect(status().isOk());
        String second = json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"eventIds",List.of(2),
                "expectedVersions",Map.of("2",1),"kind","APPEAL","suggestedOwnerUsername","devb",
                "reason","第二轮修复遗漏申请调整","evidence","第二轮回放日志和提交"));
        mvc.perform(post(BASE+"/appeals").contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk());

        mvc.perform(get(BASE+"/events").param("batchName","RPT20261022-01").param("sourceRoundId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].event.sourceRoundId").value(1));
        mvc.perform(get(BASE+"/statistics").param("sourceRoundId","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(1))
                .andExpect(jsonPath("$.data.people[0].currentCount").value(1))
                .andExpect(jsonPath("$.data.batches[0].totalEvents").value(1));
        mvc.perform(get(BASE+"/appeals").param("sourceRoundId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].items[0].eventId").value(1));
        mvc.perform(get(BASE+"/events").param("sourceRoundId","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get(BASE+"/statistics"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(2));
        mvc.perform(post(BASE+"/issue-counts").contentType(MediaType.APPLICATION_JSON).content("{\"issueKeys\":[\"ISS-A\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ISS-A").value(2));
    }

    @Test
    void sourceImportRoundMustBeAPositiveInteger() throws Exception {
        for (String path : List.of("/events","/statistics","/appeals")) {
            for (String invalid : List.of("0","-1","bad")) {
                mvc.perform(get(BASE+path).param("sourceRoundId",invalid))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
            }
        }
    }

    @Test
    void disabledFeatureReturnsReadablePermissionsAnd503ForData() throws Exception {
        properties.setEnabled(false);
        mvc.perform(get(BASE+"/permissions")).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(get(BASE+"/events")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.errorCode").value("REOPEN_DISABLED"));
    }

    @Test
    void permissionAdministrationRequiresConfiguredAdminIdentity() throws Exception {
        login("leader","LDAP","leader");
        mvc.perform(get(BASE+"/policies")).andExpect(status().isForbidden());
        login("admin","UIAS","900");
        mvc.perform(get(BASE+"/policies")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].issueDomain").value("公共组"));
    }
}
