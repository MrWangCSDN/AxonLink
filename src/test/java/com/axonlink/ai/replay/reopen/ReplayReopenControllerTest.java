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
    void multipleBatchSelectionsScopeEventsStatisticsAndAppealsToTheirUnion() throws Exception {
        ReplayReopenTestSupport.event(jdbc,2,"ISS-B","RPT20261023-01","deva","开发甲","公共组");
        ReplayReopenTestSupport.event(jdbc,3,"ISS-A","RPT20261024-01","deva","开发甲","公共组");
        login("deva","LDAP","deva");
        String selected = json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"eventIds",List.of(1,2),
                "expectedVersions",Map.of("1",1,"2",1),"kind","APPEAL","suggestedOwnerUsername","devb",
                "reason","所选两批修复遗漏申请调整","evidence","两批回放日志和提交"));
        String appealResponse = mvc.perform(post(BASE+"/appeals").contentType(MediaType.APPLICATION_JSON).content(selected))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long appealId = json.readTree(appealResponse).path("data").path("id").asLong();
        String excluded = json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"eventIds",List.of(3),
                "expectedVersions",Map.of("3",1),"kind","APPEAL","suggestedOwnerUsername","devb",
                "reason","其他批次申请调整","evidence","其他批次回放日志和提交"));
        mvc.perform(post(BASE+"/appeals").contentType(MediaType.APPLICATION_JSON).content(excluded))
                .andExpect(status().isOk());

        mvc.perform(get(BASE+"/events").param("batchNames","RPT20261022-01","RPT20261023-01").param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get(BASE+"/statistics").param("batchNames"," RPT20261022-01 ","RPT20261023-01","RPT20261023-01","").param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(2))
                .andExpect(jsonPath("$.data.distinctIssues").value(2))
                .andExpect(jsonPath("$.data.pendingEvents").value(2))
                .andExpect(jsonPath("$.data.people[0].defaultCount").value(2))
                .andExpect(jsonPath("$.data.people[0].currentCount").value(2))
                .andExpect(jsonPath("$.data.people[0].pendingCount").value(2))
                .andExpect(jsonPath("$.data.batches.length()").value(2))
                .andExpect(jsonPath("$.data.batches[0].totalEvents").value(1))
                .andExpect(jsonPath("$.data.batches[1].totalEvents").value(1));
        mvc.perform(get(BASE+"/appeals").param("batchNames","RPT20261022-01","RPT20261023-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].items.length()").value(2));
        mvc.perform(get(BASE+"/statistics").param("batchName","RPT20261022-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(1));
        mvc.perform(get(BASE+"/statistics").param("batchNames","RPTmissing') OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(0));
        mvc.perform(get(BASE+"/statistics").param("batchName","RPT20261024-01").param("batchNames","RPT20261022-01","RPT20261023-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(0));
        login("leader","LDAP","leader");
        mvc.perform(post(BASE+"/appeals/"+appealId+"/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"decision\":\"APPROVE\",\"finalOwnerUsername\":\"devb\",\"reason\":\"核查两批后同意调整\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(BASE+"/statistics").param("batchNames","RPT20261022-01","RPT20261023-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(2))
                .andExpect(jsonPath("$.data.pendingEvents").value(0))
                .andExpect(jsonPath("$.data.people[0].username").value("deva"))
                .andExpect(jsonPath("$.data.people[0].adjustedOut").value(2))
                .andExpect(jsonPath("$.data.people[1].username").value("devb"))
                .andExpect(jsonPath("$.data.people[1].adjustedIn").value(2))
                .andExpect(jsonPath("$.data.people[1].currentCount").value(2));
        login("deva","LDAP","deva");
        mvc.perform(get(BASE+"/statistics").param("onlyMine","true").param("batchNames","RPT20261022-01","RPT20261023-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(0));
    }

    @Test
    void transactionTypesApplyMatchingBatchPrefixesToChoicesAndAllBatchStatistics() throws Exception {
        ReplayReopenTestSupport.event(jdbc,2,"ISS-B","DZ20261023-01","devb","开发乙","公共组");
        ReplayReopenTestSupport.event(jdbc,3,"ISS-A","LEGACY-BATCH","deva","开发甲","公共组");
        ReplayReopenTestSupport.event(jdbc,4,"ISS-B","DZ20261024-WRONG-TYPE","deva","开发甲","公共组");
        ReplayReopenTestSupport.event(jdbc,5,"ISS-B","RPT20261025-WRONG-TYPE","devb","开发乙","公共组");
        jdbc.update("UPDATE dii_replay_reopen_event SET replay_type='DZ' WHERE id IN (2,5)");

        mvc.perform(get(BASE+"/filters").param("replayType","QUERY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.batchNames.length()").value(1))
                .andExpect(jsonPath("$.data.batchNames[0]").value("RPT20261022-01"));
        mvc.perform(get(BASE+"/filters").param("replayType","DZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.batchNames.length()").value(1))
                .andExpect(jsonPath("$.data.batchNames[0]").value("DZ20261023-01"));
        mvc.perform(get(BASE+"/filters").param("replayType","ALL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.batchNames.length()").value(5));
        mvc.perform(get(BASE+"/statistics").param("replayType","QUERY").param("batchNames",""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(1));
        mvc.perform(get(BASE+"/statistics").param("replayType","DZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(1));
        mvc.perform(get(BASE+"/statistics").param("replayType","ALL").param("batchNames","RPT20261022-01","DZ20261023-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(2));
        mvc.perform(get(BASE+"/statistics").param("replayType","ALL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEvents").value(5));
    }

    @Test
    void crossTypeAndOversizedBatchSelectionsReturnStructuredValidationErrors() throws Exception {
        for (String path : List.of("/events","/statistics","/appeals")) {
            mvc.perform(get(BASE+path).param("replayType","QUERY").param("batchNames","RPT20261022-01","DZ20261023-01"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
            mvc.perform(get(BASE+path).param("replayType","DZ").param("batchNames","RPT20261022-01"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
            mvc.perform(get(BASE+path).param("batchNames","RPT"+"x".repeat(126)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
        }
        String[] tooMany = java.util.stream.IntStream.range(0,201).mapToObj(i -> "RPT"+i).toArray(String[]::new);
        mvc.perform(get(BASE+"/statistics").param("batchNames",tooMany))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
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
