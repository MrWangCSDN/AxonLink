package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateProperties;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateService;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplayDatabaseIssuePlanDateTest {
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueDao dao;
    private ReplayDatabaseIssueService issues;
    private ReplayDatabaseIssuePlanDateService dates;
    private ReplayIssuePlanDateProperties properties;
    private ReplayIssuePlanDateService policy;
    private final ReplayIssueOperator editor=new ReplayIssueOperator("editor","编辑人");
    private final ReplayIssueOperator advanced=new ReplayIssueOperator("advanced","高级维护人");
    private long id;

    @BeforeEach void setup(){
        jdbc=ReplayIssueTestFixtures.newJdbc();ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(Arrays.stream(new String[]{"V77__replay_database_issues.sql","V78__replay_database_issue_review_projection.sql","V79__replay_database_issue_repair_date_projection.sql","V80__replay_database_issue_collaborator_projection.sql","V81__replay_database_issue_completion_totals.sql","V82__replay_database_table_person.sql","V83__replay_database_issue_plan_date.sql","V89__replay_database_issue_registration_version.sql"}).map(name->new ClassPathResource("db/daoindex/"+name)).toArray(ClassPathResource[]::new)).execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource("db/user/01_user_ddl.sql")).execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('editor','编辑人','E001',1),('advanced','高级维护人','A001',1),('developer','开发人','D001',1),('outsider','无权限','O001',1)");
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,developer_usernames,imported_at) VALUES ('koba_off_sheet_acct','贷款组','other、developer',CURRENT_TIMESTAMP)");
        properties=new ReplayIssuePlanDateProperties();var regular=new ReplayIssuePlanDateProperties.EditorGroup();regular.setEmpNos(List.of("E001"));properties.getEditors().put("贷款组",regular);
        var elevated=new ReplayIssuePlanDateProperties.EditorGroup();elevated.setEmpNos(List.of("A001"));properties.getAdvancedEditors().put("贷款组",elevated);
        var users=new SysUserDao(jdbc);dao=new ReplayDatabaseIssueDao(jdbc);
        policy=new ReplayIssuePlanDateService(mock(ReplayIssueDao.class),users,mock(ReplayTransactionPersonDao.class),properties);
        var clock=Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"),ZoneId.of("Asia/Shanghai"));
        dates=new ReplayDatabaseIssuePlanDateService(dao,policy,users,clock);
        issues=new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(),dao,users,clock);issues.setPlanDates(dates);
        issues.importFile(ReplayDatabaseIssueTest.file(true,"DB20261012-01",1,0,0),null,editor);
        id=((Number)dao.findByIssueId("koba_off_sheet_acct").get("id")).longValue();
    }
    private Map<String,Object> save(String value,ReplayIssueOperator actor){var body=new LinkedHashMap<String,Object>();body.put("version",dao.find(id).get("version"));body.put("plannedCompletionDate",value);return dates.update(id,body,actor);}

    @Test void registrationChangeClearAndNoopAreAuditedAndCounted(){
        assertEquals(0L,dao.find(id).get("plannedCompletionDateChangeCount"));
        var first=save("2026-10-19",editor);assertEquals(1L,first.get("plannedCompletionDateChangeCount"));assertEquals(1L,first.get("version"));
        assertEquals(1L,save("2026-10-19",editor).get("plannedCompletionDateChangeCount"));
        assertEquals(2L,save("2026-10-18",editor).get("plannedCompletionDateChangeCount"));
        assertEquals(3L,save(null,editor).get("plannedCompletionDateChangeCount"));
        assertEquals("",dao.find(id).get("plannedCompletionDate"));
        var history=dao.planDateChanges(id);assertEquals(3,history.size());assertEquals("",history.get(0).get("plannedCompletionDate"));assertEquals("编辑人",history.get(0).get("operatorRealName"));assertEquals("2026-10-12 10:00:00",history.get(0).get("changedAt"));
        assertTrue(issues.tracking(id).toString().contains("计划验证日期"));assertEquals(3L,dates.changes(id).get("changeCount"));
    }
    @Test void regularEditorsRespectSevenCalendarDaysAndStrictDateFormat(){
        assertEquals("2026-10-19",save("2026-10-19",editor).get("plannedCompletionDate"));
        assertEquals(ReplayIssuePlanDateService.DATE_LIMIT_MESSAGE,assertThrows(IllegalArgumentException.class,()->save("2026-10-20",editor)).getMessage());
        for(String invalid:List.of("2026-02-30","2026-1-1","2026/10/18"))assertThrows(IllegalArgumentException.class,()->save(invalid,editor));
        assertEquals(1L,dao.find(id).get("plannedCompletionDateChangeCount"));
    }
    @Test void advancedEditorsReuseConfigurationAndBypassOnlyDateBoundary(){
        assertEquals("2027-01-01",save("2027-01-01",advanced).get("plannedCompletionDate"));
        assertThrows(IllegalArgumentException.class,()->save("2026-02-30",advanced));
        var repaired=dao.find(id);repaired.put("defectRepairDate","2026-10-13");dao.update(repaired);
        assertEquals(ReplayIssuePlanDateService.REPAIRED_DATE_LOCK_MESSAGE,assertThrows(IllegalArgumentException.class,()->save("2027-01-02",advanced)).getMessage());
    }
    @Test void permissionsUseActiveIdentityAndTableDevelopersWithEmployeeNumber(){
        var developer=new ReplayIssueOperator("developer","开发人");save("2026-10-19",developer);
        assertThrows(SecurityException.class,()->save("2026-10-18",new ReplayIssueOperator("outsider","无权限")));
        jdbc.update("UPDATE ccbs_ai_sys_user SET emp_no=NULL WHERE username='developer'");assertThrows(SecurityException.class,()->save("2026-10-18",developer));
        jdbc.update("UPDATE ccbs_ai_sys_user SET status=0 WHERE username='editor'");assertThrows(SecurityException.class,()->save("2026-10-18",editor));
        assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,()->save("2026-10-18",null));
    }
    @Test void sameDomainConfigChangesImmediatelyAndOtherDomainsStayForbidden(){
        properties.getEditors().clear();assertThrows(SecurityException.class,()->save("2026-10-19",editor));
        jdbc.update("UPDATE dii_replay_database_table_person SET domain_name='存款组'");assertThrows(SecurityException.class,()->save("2026-10-20",advanced));
    }
    @Test void importsAndRecurrencePreservePlanAndHistoryWithoutChangingStatusRules(){
        save("2026-10-19",editor);
        issues.importFile(ReplayDatabaseIssueTest.file(true,"DB20261012-01",1,0,0),null,editor);
        assertEquals(1L,dao.find(id).get("plannedCompletionDateChangeCount"));assertEquals("2026-10-19",dao.find(id).get("plannedCompletionDate"));
        issues.importFile(ReplayDatabaseIssueTest.file(true,"DB20261013-01",0,0,0),null,editor);
        assertEquals("已修复",dao.find(id).get("issueStatus"));assertThrows(IllegalArgumentException.class,()->save(null,editor));
        issues.importFile(ReplayDatabaseIssueTest.file(true,"DB20261014-01",1,0,0),null,editor);
        assertEquals("新建",dao.find(id).get("issueStatus"));assertEquals("",dao.find(id).get("defectRepairDate"));assertEquals("2026-10-19",dao.find(id).get("plannedCompletionDate"));assertEquals(1L,dao.find(id).get("plannedCompletionDateChangeCount"));
        assertEquals(1,dao.planDateChanges(id).size());
    }
    @Test void listFiltersAndPermissionsIncludePlanDate(){
        assertEquals(1L,dao.list(Map.of("plannedCompletionDates",List.of(""))).get("total"));save("2026-10-19",editor);
        assertEquals(1L,dao.list(Map.of("plannedCompletionDates",List.of("2026-10-19"))).get("total"));
        assertEquals(0L,dao.list(Map.of("plannedCompletionDates",List.of(""))).get("total"));
        assertTrue(dao.filterOptions("plannedCompletionDate",Map.of()).toString().contains("2026-10-19"));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)issues.list(Map.of(),editor).get("items");assertEquals(true,rows.get(0).get("canEditPlanDate"));
    }
    @Test void endpointEnforcesAuthenticationPermissionsAndVersionAndExportsDate() throws Exception {
        var resolver=mock(com.axonlink.security.UserPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(new com.axonlink.security.UserPrincipalResolver.Resolved("LDAP","editor",null));
        var controller=new ReplayDatabaseIssueController(dao,issues,resolver,new com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties());
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"planDates",dates);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String url="/api/ai/parallel-replay/database-issues/"+id;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url+"/planned-completion-date").contentType("application/json").content("{\"version\":0,\"plannedCompletionDate\":\"2026-10-19\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.plannedCompletionDateChangeCount").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url+"/planned-completion-date-changes"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].operatorUsername").value("editor"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url+"/planned-completion-date").contentType("application/json").content("{\"version\":0,\"plannedCompletionDate\":null}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        when(resolver.resolve(any())).thenReturn(new com.axonlink.security.UserPrincipalResolver.Resolved("LDAP","outsider",null));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url+"/planned-completion-date").contentType("application/json").content("{\"version\":1,\"plannedCompletionDate\":null}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        when(resolver.resolve(any())).thenReturn(null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url+"/planned-completion-date").contentType("application/json").content("{\"version\":1,\"plannedCompletionDate\":null}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        byte[] excel=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/ai/parallel-replay/database-issues/export")).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(excel))){
            var sheet=workbook.getSheetAt(0);assertEquals("计划验证日期",sheet.getRow(0).getCell(11).getStringCellValue());assertEquals("缺陷修复日期",sheet.getRow(0).getCell(12).getStringCellValue());assertEquals("2026-10-19",sheet.getRow(1).getCell(11).getStringCellValue());
        }
    }
    @Test void failedHistoryWriteRollsBackDateVersionAndCounter(){
        var failingDao=spy(dao);
        doThrow(new IllegalStateException("history unavailable")).when(failingDao).history(anyMap(),anyMap(),isNull(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(false));
        var failingService=new ReplayDatabaseIssuePlanDateService(failingDao,policy,new SysUserDao(jdbc));
        assertThrows(IllegalStateException.class,()->failingService.update(id,Map.of("version",0,"plannedCompletionDate","2026-10-19"),editor));
        assertEquals("",dao.find(id).get("plannedCompletionDate"));assertEquals(0L,dao.find(id).get("version"));assertEquals(0L,dao.find(id).get("plannedCompletionDateChangeCount"));
    }
    @Test void staleVersionCannotOverwritePlanAndNoopDoesNotAcceptMissingPermission(){
        save("2026-10-18",editor);
        assertThrows(ReplayDatabaseIssueConflictException.class,()->dates.update(id,Map.of("version",0,"plannedCompletionDate","2026-10-19"),editor));
        assertThrows(SecurityException.class,()->save("2026-10-18",new ReplayIssueOperator("outsider","无权限")));
        assertEquals(1L,dao.find(id).get("plannedCompletionDateChangeCount"));
    }
}
