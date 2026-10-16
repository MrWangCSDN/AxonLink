package com.axonlink.ai.replay.dbissue;
import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.io.ByteArrayInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ReplayDatabaseIssueControllerTest {
    private MockMvc mvc;private UserPrincipalResolver resolver;private ReplayDatabaseIssueDao dao;private JdbcTemplate jdbc;private SysUserDao users;
    @BeforeEach void setup(){
        jdbc=ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),new ClassPathResource("db/daoindex/V78__replay_database_issue_review_projection.sql"),new ClassPathResource("db/daoindex/V79__replay_database_issue_repair_date_projection.sql"),new ClassPathResource("db/daoindex/V80__replay_database_issue_collaborator_projection.sql")).execute(jdbc.getDataSource());
        dao=new ReplayDatabaseIssueDao(jdbc);
        new ResourceDatabasePopulator(new ClassPathResource("db/user/01_user_ddl.sql")).execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('editor','编辑人','E001',1),('other','编辑人','E002',1)");
        users=spy(new SysUserDao(jdbc));
        var service=new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(),dao,users);
        resolver=mock(UserPrincipalResolver.class);when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP","editor",null));
        mvc=MockMvcBuilders.standaloneSetup(new ReplayDatabaseIssueController(dao,service,resolver)).build();
    }
    @Test void oldReleaseProtectsPendingVerificationDespiteNewBatchDate() throws Exception {
        assertReleaseBoundary("2026-10-14 12:00:01", "修复待验证");
    }
    @Test void saveAtReleaseTimeCanBeReopened() throws Exception {
        assertReleaseBoundary("2026-10-14 12:00:00", "重新打开");
    }
    @Test void saveBeforeReleaseTimeCanBeReopened() throws Exception {
        assertReleaseBoundary("2026-10-14 11:59:59", "重新打开");
    }
    private void assertReleaseBoundary(String manualAt, String expectedStatus) throws Exception {
        importBatch("DB20261014-01",3,0,0);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        row.put("issueStatus","修复待验证");row.put("lastManualSaveAt",manualAt);row.put("remark","保留分析");dao.update(row);
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import")
                .file(ReplayDatabaseIssueTest.file(true,"DB20261015-01",2,0,0))
                .param("releaseAt","2026-10-14T12:00:00"))
                .andExpect(status().isOk());
        var after=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals(expectedStatus,after.get("issueStatus"));
        assertEquals("DB20261015-01",after.get("batchNo"));
        assertEquals(2L,after.get("differenceCount"));
        assertEquals("保留分析",after.get("remark"));
    }
    @Test void protectedPendingIssuesStillAutoFixWhenAbsentAsRequested() throws Exception {
        importBatch("DB20261014-01",3,0,0);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        row.put("issueStatus","修复待验证");row.put("lastManualSaveAt","2026-10-14 15:00:00");dao.update(row);
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import")
                .file(ReplayDatabaseIssueTest.file(true,"DB20261015-01",0,0,0)).param("releaseAt","2026-10-14 12:00:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.autoRepairedRows").value(1));
        assertEquals("已修复",dao.findByIssueId("koba_off_sheet_acct-ccbs").get("issueStatus"));
    }
    @Test void rejectsInvalidReleaseTimeBeforeWriting() throws Exception {
        for(String value:List.of("", "2026-02-30 12:00:00", "2026-10-14 12:00", "not-a-time")) {
            mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import")
                    .file(ReplayDatabaseIssueTest.file(true,"DB20261015-01",3,0,0)).param("releaseAt",value))
                    .andExpect(status().isBadRequest());
        }
        assertTrue(dao.current().isEmpty());
    }
    @Test void importListEditAndTrackUseSeparateLevelsAndTypes() throws Exception{
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(ReplayDatabaseIssueTest.file(true,"DB20261012-01",3,1,2)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.createdRows").value(3));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("issueLevel","仅ccbs"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].differenceCount").value(3));
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":0,\"issueStatus\":\"打开\",\"issueType\":\"需规问题\",\"remark\":\"处理\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.issueLevel").value("仅ccbs")).andExpect(jsonPath("$.data.issueType").value("需规问题"));
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":0,\"issueType\":\"代码问题\"}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/ai/parallel-replay/database-issues/1/tracking"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].batchNo").value("DB20261012-01")).andExpect(jsonPath("$.data[0].events.length()").value(2));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/options")).andExpect(jsonPath("$.data.issueLevels[2]").value("字段级"));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/export").param("issueLevel","仅ccbs"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
    @Test void requiresLoginForMutationAndRejectsInvalidWorkbook() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS",null,null));
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(ReplayDatabaseIssueTest.file(true,"DB20261012-01",3,0,0))).andExpect(status().isUnauthorized());
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP","editor",null));
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(ReplayDatabaseIssueTest.file(false,"",3,0,0))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ai/parallel-replay/database-issues")).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void repeatedFiltersApplyOrWithinColumnsAndAndBetweenColumnsBeforePagingAndExport() throws Exception {
        seedFilterRows();
        mvc.perform(get("/api/ai/parallel-replay/database-issues")
                .param("issueLevels","仅ccbs","仅528").param("domains","贷款组").param("limit","1").param("offset","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.items[0].issueLevel").value("仅ccbs"));
        byte[] content=mvc.perform(get("/api/ai/parallel-replay/database-issues/export")
                .param("issueLevels","仅ccbs","仅528").param("domains","贷款组").param("limit","1").param("offset","100"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet=workbook.getSheetAt(0);
            assertEquals(2,sheet.getLastRowNum());
            assertEquals(Set.of("仅ccbs","仅528"),Set.of(sheet.getRow(1).getCell(4).getStringCellValue(),sheet.getRow(2).getCell(4).getStringCellValue()));
        }
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("issueLevels","仅ccbs","仅528").param("issueLevel","字段级"))
            .andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void facetsUseAllScopedRowsExcludeTheirOwnFilterAndKeepBlankCandidates() throws Exception {
        seedFilterRows();
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","domain")
                .param("domains","不存在").param("domain","不存在").param("issueLevels","仅ccbs").param("limit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(2))
            .andExpect(jsonPath("$.data.matchedIssueCount").value(2)).andExpect(jsonPath("$.data.truncated").value(false))
            .andExpect(jsonPath("$.data.items[0].count").value(1)).andExpect(jsonPath("$.data.items[1].count").value(1));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","fieldName").param("domains","贷款组"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].value").value(""))
            .andExpect(jsonPath("$.data.items[0].count").value(2)).andExpect(jsonPath("$.data.items[1].value").value("dtl_sn"))
            .andExpect(jsonPath("$.data.matchedIssueCount").value(3));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("fieldNames",""))
            .andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("issueTypes",""))
            .andExpect(jsonPath("$.data.total").value(3));
    }
    @Test void exactRepeatedValuesPreserveCommasAndOtherColumns() throws Exception {
        seedFilterRows();
        mvc.perform(get("/api/ai/parallel-replay/database-issues")
                .param("issueIds","extra_table-ccbs").param("tableNames","extra_table").param("tableComments","账户,明细")
                .param("owners","张三,李四").param("differenceCounts","8").param("batchNos","DB20261012-01")
                .param("issueStatuses","新建").param("issueTypes","代码问题").param("domains","存款组").param("issueLevels","仅ccbs").param("fieldNames",""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].issueId").value("extra_table-ccbs"));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("owners","张三","李四"))
            .andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void candidateSearchEscapesWildcardsAndRejectsInvalidFieldAndCounts() throws Exception {
        seedFilterRows();
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","fieldName").param("keyword","DTL_"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].value").value("dtl_sn"))
            .andExpect(jsonPath("$.data.matchedIssueCount").value(1));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","fieldName").param("keyword","%"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(0));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","domain_name OR 1=1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("differenceCounts","not-a-number"))
            .andExpect(status().isBadRequest());
    }
    @Test void exportRejectsInvalidRepeatedCriteriaAsBadRequest() throws Exception {
        seedFilterRows();
        mvc.perform(get("/api/ai/parallel-replay/database-issues/export").param("differenceCounts","not-a-number"))
            .andExpect(status().isBadRequest());
    }
    @Test void rejectsBlankNumericFilterValues() throws Exception {
        seedFilterRows();
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("differenceCounts",""))
            .andExpect(status().isBadRequest());
    }
    @Test void facetsReportTruncationAndCountsWithoutScanningOnlyTheCurrentPage() throws Exception {
        seedFilterRows();
        var original=dao.findByIssueId("extra_table-ccbs");
        for(int i=0;i<500;i++) {
            String table="extra_"+i;
            registerMetadata(table,"存款组","owner-"+i,"","");
            var row=new LinkedHashMap<String,Object>(original);row.put("issueId",table+"-ccbs");row.put("tableName",table);row.put("owner","旧负责人");dao.insert(row);
        }
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","owner").param("domains","存款组"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(500))
            .andExpect(jsonPath("$.data.candidateCount").value(501)).andExpect(jsonPath("$.data.matchedIssueCount").value(501))
            .andExpect(jsonPath("$.data.truncated").value(true));
    }
    @Test void postFilteringAndExportSupportSelectionsLargerThanNormalUrlLimits() throws Exception {
        seedFilterRows();
        var ids=new ArrayList<String>();
        for(int i=0;i<499;i++)ids.add("long_table_name_"+"x".repeat(128)+i+"-ccbs");
        ids.add("extra_table-ccbs");
        var criteria=new LinkedHashMap<String,Object>();criteria.put("issueIds",ids);criteria.put("limit",1);
        String body=new ObjectMapper().writeValueAsString(criteria);
        assertTrue(body.length()>8192);
        mvc.perform(post("/api/ai/parallel-replay/database-issues").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].issueId").value("extra_table-ccbs"));
        criteria.put("field","domain");criteria.put("domains",List.of("不存在"));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/filter-options").contentType("application/json").content(new ObjectMapper().writeValueAsString(criteria)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(1))
            .andExpect(jsonPath("$.data.items[0].value").value("存款组"));
        byte[] content=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertEquals(1,workbook.getSheetAt(0).getLastRowNum());
            assertEquals("extra_table-ccbs",workbook.getSheetAt(0).getRow(1).getCell(8).getStringCellValue());
        }
    }
    @Test void postFilteringRejectsInvalidCriteria() throws Exception {
        for(String path:List.of("","/export")) {
            mvc.perform(post("/api/ai/parallel-replay/database-issues"+path).contentType("application/json").content("{\"differenceCounts\":[\"invalid\"]}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/ai/parallel-replay/database-issues/filter-options").contentType("application/json").content("{\"field\":\"invalid\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void occurrenceBatchesIncludeEveryActualImportNewestFirstAndExportThem() throws Exception {
        importBatch("DB20261012-01",3,1,2);
        importBatch("DB20261013-01",2,1,0);
        importBatch("DB20261013-01",1,1,0);
        importBatch("DB20261014-01",0,0,0);
        var issue=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        dao.history(issue,issue,null,"DB20261015-01","人工审核","2026-10-15 09:00:00","editor","编辑人",false);
        mvc.perform(post("/api/ai/parallel-replay/database-issues").contentType("application/json").content("{\"issueIds\":[\"koba_off_sheet_acct-ccbs\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].batchNo").value("DB20261013-01"))
            .andExpect(jsonPath("$.data.items[0].occurrenceBatches.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].occurrenceBatches[0]").value("DB20261013-01"))
            .andExpect(jsonPath("$.data.items[0].occurrenceBatches[1]").value("DB20261012-01"));
        byte[] content=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json").content("{\"issueIds\":[\"koba_off_sheet_acct-ccbs\"]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertEquals("DB20261013-01、DB20261012-01",workbook.getSheetAt(0).getRow(1).getCell(16).getStringCellValue());
        }
    }
    @Test void historicalBatchFiltersAndFacetsCountDistinctIssuesAcrossAllOccurrences() throws Exception {
        importBatch("DB20261012-01",3,1,2);
        importBatch("DB20261013-01",2,1,0);
        importBatch("DB20261014-01",0,0,0);
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("batchNos","DB20261012-01").param("limit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("batchNos","DB20261012-01","DB20261013-01").param("issueLevels","仅ccbs"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("batchNo","DB20261012-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/filter-options").contentType("application/json")
                .content("{\"field\":\"batchNo\",\"batchNos\":[\"DB20261014-01\"],\"batchNo\":\"DB20261014-01\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(2))
            .andExpect(jsonPath("$.data.matchedIssueCount").value(3))
            .andExpect(jsonPath("$.data.items[0].value").value("DB20261013-01")).andExpect(jsonPath("$.data.items[0].count").value(2))
            .andExpect(jsonPath("$.data.items[1].value").value("DB20261012-01")).andExpect(jsonPath("$.data.items[1].count").value(3));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","batchNo").param("keyword","20261013"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(1))
            .andExpect(jsonPath("$.data.matchedIssueCount").value(2)).andExpect(jsonPath("$.data.items[0].count").value(2));
    }
    @Test void occurrenceBatchesFallBackToCurrentBatchWhenHistoryIsMissing() throws Exception {
        seedFilterRows();
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("issueIds","extra_table-ccbs"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].occurrenceBatches.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].occurrenceBatches[0]").value("DB20261012-01"));
    }
    @Test void anActualAppearanceSurvivesAnAbsentOverwriteOfTheSameBatchAndLaterAppearance() throws Exception {
        importBatch("DB20261012-01",3,0,0);
        importBatch("DB20261013-01",2,0,0);
        importBatch("DB20261013-01",0,0,0);
        importBatch("DB20261014-01",1,0,0);
        assertEquals(2,dao.history(((Number)dao.findByIssueId("koba_off_sheet_acct-ccbs").get("id")).longValue())
                .stream().filter(event->event.get("batchNo").equals("DB20261013-01")).count());
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].occurrenceBatches.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].occurrenceBatches[0]").value("DB20261014-01"))
            .andExpect(jsonPath("$.data.items[0].occurrenceBatches[1]").value("DB20261013-01"))
            .andExpect(jsonPath("$.data.items[0].occurrenceBatches[2]").value("DB20261012-01"));
    }
    @Test void mergedTableFacetSearchesChineseNameAndIncludesDeterministicDisplayLabel() throws Exception {
        seedFilterRows();
        var row=new LinkedHashMap<String,Object>(dao.findByIssueId("extra_table-ccbs"));
        row.put("issueId","extra_table-528");row.put("issueLevel","仅528");row.put("tableComment","账户明细二");dao.insert(row);
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","tableName").param("keyword","账户"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(1))
            .andExpect(jsonPath("$.data.matchedIssueCount").value(2)).andExpect(jsonPath("$.data.items[0].value").value("extra_table"))
            .andExpect(jsonPath("$.data.items[0].count").value(2)).andExpect(jsonPath("$.data.items[0].label").value("extra_table\n账户,明细、账户明细二"));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","tableName").param("keyword","EXTRA_TABLE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].label").value("extra_table\n账户,明细、账户明细二"));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","tableName").param("keyword","明细二"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].count").value(2))
            .andExpect(jsonPath("$.data.matchedIssueCount").value(2));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("tableNames","extra_table"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
    }
    @Test void exportUsesTheCurrentVisibleColumnsAndMergedTableNames() throws Exception {
        seedFilterRows();
        byte[] content=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json").content("{\"issueIds\":[\"extra_table-ccbs\"]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet=workbook.getSheetAt(0);
            var expected=List.of("表英文名/中文名","领域","开发负责人","科技负责人","问题级别","字段英文名","差异笔数","缺陷修复日期","issue_id","问题状态","审核状态","问题类型","需协同人","初步问题分析","最终处理方案","备注","出现批次");
            assertEquals(expected.size(),sheet.getRow(0).getLastCellNum());
            for(int i=0;i<expected.size();i++)assertEquals(expected.get(i),sheet.getRow(0).getCell(i).getStringCellValue());
            assertEquals("extra_table\n账户,明细",sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("DB20261012-01",sheet.getRow(1).getCell(16).getStringCellValue());
        }
    }
    @Test void exportShowsCurrentRegisteredTechnologyOwnerAndReviserSeparately() throws Exception {
        importBatch("DB20261012-01",3,0,0);
        registerTechnologyOwner("public","koba_off_sheet_acct","贷款组","E001");
        jdbc.update("UPDATE dii_replay_db_compare_registration SET reviser_username='c-zhangs3',reviser_name='张三'");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        byte[] first=mvc.perform(get("/api/ai/parallel-replay/database-issues/export").param("issueIds","koba_off_sheet_acct-ccbs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(first))){
            var sheet=workbook.getSheetAt(0);
            assertEquals("开发负责人",sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("科技负责人",sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("张三(c-zhangs3)",sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("编辑人(E001)",sheet.getRow(1).getCell(3).getStringCellValue());
        }
        jdbc.update("UPDATE dii_replay_db_compare_registration SET group_owner_emp_no='E002' WHERE table_name='koba_off_sheet_acct'");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        byte[] refreshed=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json").content("{\"issueIds\":[\"koba_off_sheet_acct-ccbs\"]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(refreshed))){
            var sheet=workbook.getSheetAt(0);
            assertEquals("张三(c-zhangs3)",sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("编辑人(E002)",sheet.getRow(1).getCell(3).getStringCellValue());
        }
        assertFalse(dao.find(1).containsKey("technologyOwnerDisplay"));
    }
    @Test void differenceCountSortingIsNumericStableFilteredAndSharedByPagedListsAndExports() throws Exception {
        registerMetadata("koba_off_sheet_acct","贷款组","张三","c-zhangs3","");
        registerMetadata("another_table","贷款组","张三","c-zhangs3","");
        registerMetadata("excluded_table","存款组","张三","c-zhangs3","");
        importBatch("DB20261012-01",2,10,100);
        var tied=new LinkedHashMap<String,Object>(dao.findByIssueId("koba_off_sheet_acct-528"));
        tied.put("issueId","another_table-528");tied.put("tableName","another_table");dao.insert(tied);
        var excluded=new LinkedHashMap<String,Object>(tied);
        excluded.put("issueId","excluded_table-528");excluded.put("tableName","excluded_table");excluded.put("domain","存款组");excluded.put("differenceCount",1L);dao.insert(excluded);
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","贷款组"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4))
            .andExpect(jsonPath("$.data.items[0].id").value(4)).andExpect(jsonPath("$.data.items[1].id").value(3));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","贷款组").param("differenceCountOrder","  "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(4));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","贷款组").param("differenceCountOrder"," aSc ").param("limit","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4)).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(1)).andExpect(jsonPath("$.data.items[0].differenceCount").value(2))
            .andExpect(jsonPath("$.data.items[1].id").value(4)).andExpect(jsonPath("$.data.items[1].differenceCount").value(10));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","贷款组").param("differenceCountOrder","ASC").param("limit","2").param("offset","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(2)).andExpect(jsonPath("$.data.items[0].differenceCount").value(10))
            .andExpect(jsonPath("$.data.items[1].id").value(3)).andExpect(jsonPath("$.data.items[1].differenceCount").value(100));
        mvc.perform(post("/api/ai/parallel-replay/database-issues").contentType("application/json")
                .content("{\"domains\":[\"贷款组\"],\"differenceCountOrder\":\" dEsC \",\"limit\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4))
            .andExpect(jsonPath("$.data.items[0].id").value(3)).andExpect(jsonPath("$.data.items[0].differenceCount").value(100))
            .andExpect(jsonPath("$.data.items[1].id").value(4)).andExpect(jsonPath("$.data.items[1].differenceCount").value(10));
        mvc.perform(post("/api/ai/parallel-replay/database-issues").contentType("application/json")
                .content("{\"domains\":[\"贷款组\"],\"differenceCountOrder\":\"DESC\",\"limit\":2,\"offset\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(2)).andExpect(jsonPath("$.data.items[1].id").value(1));
        byte[] ascending=mvc.perform(get("/api/ai/parallel-replay/database-issues/export").param("domains","贷款组")
                .param("differenceCountOrder","asc").param("limit","1").param("offset","100"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(ascending))){
            var sheet=workbook.getSheetAt(0);assertEquals(4,sheet.getLastRowNum());assertEquals("差异笔数",sheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals(List.of("koba_off_sheet_acct-ccbs","another_table-528","koba_off_sheet_acct-528","koba_off_sheet_acct-dtl_sn"),
                List.of(sheet.getRow(1).getCell(8).getStringCellValue(),sheet.getRow(2).getCell(8).getStringCellValue(),sheet.getRow(3).getCell(8).getStringCellValue(),sheet.getRow(4).getCell(8).getStringCellValue()));
        }
        byte[] descending=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json")
                .content("{\"domains\":[\"贷款组\"],\"differenceCountOrder\":\"DESC\",\"limit\":1,\"offset\":100}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(descending))){
            var sheet=workbook.getSheetAt(0);assertEquals(4,sheet.getLastRowNum());
            assertEquals(List.of("koba_off_sheet_acct-dtl_sn","another_table-528","koba_off_sheet_acct-528","koba_off_sheet_acct-ccbs"),
                List.of(sheet.getRow(1).getCell(8).getStringCellValue(),sheet.getRow(2).getCell(8).getStringCellValue(),sheet.getRow(3).getCell(8).getStringCellValue(),sheet.getRow(4).getCell(8).getStringCellValue()));
        }
    }
    @Test void listsAndExportsRejectInvalidDifferenceCountSortDirections() throws Exception {
        for(String path:List.of("","/export"))for(String value:List.of("up","ASC, i.id ASC","DESC; DROP TABLE dii_replay_database_issue")){
            mvc.perform(get("/api/ai/parallel-replay/database-issues"+path).param("differenceCountOrder",value))
                .andExpect(status().isBadRequest());
            mvc.perform(post("/api/ai/parallel-replay/database-issues"+path).contentType("application/json")
                    .content(new ObjectMapper().writeValueAsString(Map.of("differenceCountOrder",value))))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/ai/parallel-replay/database-issues")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void repairDateFiltersAndCandidatesUseAllScopedRowsAndShareSortedExportCriteria() throws Exception {
        seedFilterRows();
        for(long id=1;id<=4;id++){
            var row=dao.find(id);row.put("defectRepairDate",id==3?"":id==2?"2026-10-13":"2026-10-12");dao.update(row);
        }
        var additional=new LinkedHashMap<String,Object>(dao.find(1));
        registerMetadata("additional_table","贷款组","张三","c-zhangs3","");
        additional.put("issueId","additional_table-ccbs");additional.put("tableName","additional_table");additional.put("differenceCount",10L);dao.insert(additional);
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("defectRepairDates","","2026-10-12")
                .param("domains","贷款组").param("differenceCountOrder","ASC").param("limit","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(3)).andExpect(jsonPath("$.data.items[0].defectRepairDate").value(""))
            .andExpect(jsonPath("$.data.items[1].id").value(1));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("defectRepairDates","","2026-10-12")
                .param("domains","贷款组").param("differenceCountOrder","ASC").param("limit","2").param("offset","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.items[0].id").value(5));
        String criteria="{\"defectRepairDates\":[\"\",\"2026-10-12\"],\"domains\":[\"贷款组\"],\"differenceCountOrder\":\"DESC\",\"limit\":1,\"offset\":0}";
        mvc.perform(post("/api/ai/parallel-replay/database-issues").contentType("application/json").content(criteria))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items[0].id").value(5));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","defectRepairDate")
                .param("domains","贷款组").param("defectRepairDates","2026-10-13").param("limit","1").param("offset","100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(3)).andExpect(jsonPath("$.data.matchedIssueCount").value(4))
            .andExpect(jsonPath("$.data.items[0].value").value("")).andExpect(jsonPath("$.data.items[0].count").value(1))
            .andExpect(jsonPath("$.data.items[1].value").value("2026-10-12")).andExpect(jsonPath("$.data.items[1].count").value(2))
            .andExpect(jsonPath("$.data.items[2].value").value("2026-10-13")).andExpect(jsonPath("$.data.items[2].count").value(1));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/filter-options").contentType("application/json")
                .content("{\"field\":\"defectRepairDate\",\"domains\":[\"贷款组\"],\"defectRepairDates\":[\"\"],\"keyword\":\"2026-10-12\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(1)).andExpect(jsonPath("$.data.matchedIssueCount").value(2))
            .andExpect(jsonPath("$.data.items[0].value").value("2026-10-12")).andExpect(jsonPath("$.data.items[0].count").value(2));
        byte[] content=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json").content(criteria))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(content))){
            var sheet=workbook.getSheetAt(0);assertEquals(3,sheet.getLastRowNum());
            assertEquals(List.of("additional_table-ccbs","koba_off_sheet_acct-ccbs","koba_off_sheet_acct-dtl_sn"),
                List.of(sheet.getRow(1).getCell(8).getStringCellValue(),sheet.getRow(2).getCell(8).getStringCellValue(),sheet.getRow(3).getCell(8).getStringCellValue()));
            assertEquals("2026-10-12",sheet.getRow(1).getCell(7).getStringCellValue());assertEquals("",sheet.getRow(3).getCell(7).getStringCellValue());
        }
    }
    @Test void collaboratorDisplayFiltersCombineWithOtherColumnsAndSharedPagingSortingAndExport() throws Exception {
        seedFilterRows();
        for(long id=1;id<=4;id++){
            var row=dao.find(id);row.put("cooperationPerson",id==3?"":id==2?"李四(other)":"张三(editor)");
            row.put("issueStatus","打开");row.put("defectRepairDate","");dao.update(row);
        }
        String[] tables={"excluded_status_table","excluded_date_table","another_collaborator_table"};
        for(int i=0;i<tables.length;i++){
            registerMetadata(tables[i],"贷款组","张三","c-zhangs3","");
            var row=new LinkedHashMap<String,Object>(dao.find(1));row.put("issueId",tables[i]+"-ccbs");row.put("tableName",tables[i]);
            row.put("cooperationPerson",i==0?"张三(editor)":i==1?"李四(other)":"王五(third)");row.put("differenceCount",10L+i);
            row.put("issueStatus",i==0?"无需处理":"打开");row.put("defectRepairDate",i==1?"2026-10-12":"");dao.insert(row);
        }
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("cooperationPersons","张三(editor)","李四(other)")
                .param("domains","贷款组").param("issueStatuses","打开").param("defectRepairDates","").param("differenceCountOrder","DESC").param("limit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].id").value(1));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("cooperationPersons","张三(editor)","李四(other)")
                .param("domains","贷款组").param("issueStatuses","打开").param("defectRepairDates","").param("differenceCountOrder","DESC").param("limit","1").param("offset","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(2));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("cooperationPersons","editor"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        String criteria="{\"cooperationPersons\":[\"张三(editor)\",\"李四(other)\",\"\"],\"domains\":[\"贷款组\"],\"issueStatuses\":[\"打开\"],\"defectRepairDates\":[\"\"],\"differenceCountOrder\":\"DESC\",\"limit\":2,\"offset\":0}";
        mvc.perform(post("/api/ai/parallel-replay/database-issues").contentType("application/json").content(criteria))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(1)).andExpect(jsonPath("$.data.items[1].id").value(3))
            .andExpect(jsonPath("$.data.items[1].cooperationPerson").value(""));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","cooperationPerson")
                .param("cooperationPersons","李四(other)").param("domains","贷款组").param("issueStatuses","打开").param("defectRepairDates","").param("limit","1").param("offset","100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(4)).andExpect(jsonPath("$.data.matchedIssueCount").value(4))
            .andExpect(jsonPath("$.data.items[0].value").value("")).andExpect(jsonPath("$.data.items[0].count").value(1));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/filter-options").contentType("application/json")
                .content("{\"field\":\"cooperationPerson\",\"cooperationPersons\":[\"李四(other)\"],\"issueStatuses\":[\"打开\"],\"defectRepairDates\":[\"\"],\"keyword\":\"张三\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(1)).andExpect(jsonPath("$.data.matchedIssueCount").value(2))
            .andExpect(jsonPath("$.data.items[0].value").value("张三(editor)")).andExpect(jsonPath("$.data.items[0].count").value(2));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","cooperationPerson")
                .param("domains","贷款组").param("issueStatuses","打开").param("defectRepairDates","").param("keyword","EDITOR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(1)).andExpect(jsonPath("$.data.matchedIssueCount").value(1))
            .andExpect(jsonPath("$.data.items[0].value").value("张三(editor)"));
        byte[] content=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json").content(criteria))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(content))){
            var sheet=workbook.getSheetAt(0);assertEquals(3,sheet.getLastRowNum());
            assertEquals(List.of("koba_off_sheet_acct-ccbs","koba_off_sheet_acct-dtl_sn","koba_off_sheet_acct-528"),
                List.of(sheet.getRow(1).getCell(8).getStringCellValue(),sheet.getRow(2).getCell(8).getStringCellValue(),sheet.getRow(3).getCell(8).getStringCellValue()));
            assertEquals("张三(editor)",sheet.getRow(1).getCell(12).getStringCellValue());assertEquals("",sheet.getRow(2).getCell(12).getStringCellValue());
        }
    }
    @Test void tableTechnologyOwnerControlsReviewAndReturnedRowPermissions() throws Exception {
        importBatch("DB20261012-01",3,0,0);
        registerTechnologyOwner("public","koba_off_sheet_acct","贷款组","E001");
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json")
                .content("{\"version\":0,\"issueStatus\":\"无需处理\",\"issueType\":\"外围问题\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("待审核"))
            .andExpect(jsonPath("$.data.reviewStatusDisplay").value("待审核"))
            .andExpect(jsonPath("$.data.technologyOwnerUsername").value("editor"))
            .andExpect(jsonPath("$.data.canReview").value(true)).andExpect(jsonPath("$.data.canEdit").value(true));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":1,\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest());
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP","other",null));
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(jsonPath("$.data.items[0].canReview").value(false));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":1,\"reason\":\"合理\",\"canReview\":true}"))
            .andExpect(status().isForbidden());
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP","editor",null));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":1,\"reason\":\"合理\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("已审核"))
            .andExpect(jsonPath("$.data.reviewStatusDisplay").value("已审核（已填写原因）"))
            .andExpect(jsonPath("$.data.issueType").value("合理差异"))
            .andExpect(jsonPath("$.data.canReview").value(true)).andExpect(jsonPath("$.data.canEdit").value(true));
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP","other",null));
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(jsonPath("$.data.items[0].canReview").value(false)).andExpect(jsonPath("$.data.items[0].canEdit").value(false));
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":2,\"issueType\":\"合理差异\",\"canEdit\":true}"))
            .andExpect(status().isForbidden());
    }
    @Test void technologyOwnerIdentityNeverComesFromDomainReviserNameOrAmbiguousRegistration() throws Exception {
        importBatch("DB20261012-01",3,0,0);
        var row=dao.find(1);row.put("issueStatus","无需处理");row.put("reviewStatus","待审核");row.put("owner","编辑人(editor)");dao.update(row);
        mvc.perform(get("/api/ai/parallel-replay/database-issues")).andExpect(jsonPath("$.data.items[0].canReview").value(false));
        registerTechnologyOwner("public","unrelated_table","贷款组","E001");
        mvc.perform(get("/api/ai/parallel-replay/database-issues")).andExpect(jsonPath("$.data.items[0].canReview").value(false));
        registerTechnologyOwner("public","koba_off_sheet_acct","贷款组","E002");
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(jsonPath("$.data.items[0].technologyOwnerUsername").value("other")).andExpect(jsonPath("$.data.items[0].canReview").value(false));
        jdbc.update("UPDATE dii_replay_db_compare_registration SET group_owner_emp_no='E001' WHERE table_name='koba_off_sheet_acct'");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('E001','同名科技人员','OTHER',1)");
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(jsonPath("$.data.items[0].technologyOwnerUsername").value("")).andExpect(jsonPath("$.data.items[0].canReview").value(false));
        jdbc.update("DELETE FROM ccbs_ai_sys_user WHERE username='E001'");
        registerTechnologyOwner("another","koba_off_sheet_acct","贷款组","E001");
        mvc.perform(get("/api/ai/parallel-replay/database-issues")).andExpect(jsonPath("$.data.items[0].canReview").value(false));
        jdbc.update("UPDATE dii_replay_db_compare_registration SET deleted=1 WHERE schema_name='another'");
        jdbc.update("UPDATE ccbs_ai_sys_user SET status=0 WHERE username='editor'");
        mvc.perform(get("/api/ai/parallel-replay/database-issues")).andExpect(jsonPath("$.data.items[0].canReview").value(false));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":1,\"reason\":\"理由\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void legacyUsernameOwnerCanReviewAndApprovalReasonIsIdempotent() throws Exception {
        jdbc.update("UPDATE ccbs_ai_sys_user SET emp_no=NULL WHERE username='editor'");
        registerTechnologyOwner("public","koba_off_sheet_acct","贷款组","editor");
        importBatch("DB20261012-01",3,0,0);
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":0,\"issueStatus\":\"无需处理\",\"issueType\":\"外围问题\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":1,\"reason\":\"理由\"}"))
            .andExpect(status().isOk());
        var approved=dao.find(1);int history=dao.history(1).size();
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":2,\"reason\":\" 理由 \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        assertEquals(approved.get("reviewedAt"),dao.find(1).get("reviewedAt"));assertEquals(approved.get("defectRepairDate"),dao.find(1).get("defectRepairDate"));
        assertEquals(history,dao.history(1).size());assertEquals("审核通过",dao.history(1).get(0).get("operationType"));
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":2,\"issueStatus\":\"无需处理\",\"issueType\":\"合理差异\",\"remark\":\"修改备注\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("已审核")).andExpect(jsonPath("$.data.reviewReason").value("理由"));
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":3,\"issueStatus\":\"打开\",\"issueType\":\"代码问题\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("")).andExpect(jsonPath("$.data.defectRepairDate").value(""));
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":4,\"issueStatus\":\"无需处理\",\"issueType\":\"外围问题\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("待审核")).andExpect(jsonPath("$.data.reviewReason").value(""));
    }
    @Test void reviewFacetAndExportUseTheThreeDisplayStatusesWithoutChangingRawValues() throws Exception {
        seedFilterRows();
        for(long id=1;id<=3;id++){var row=dao.find(id);row.put("issueStatus","无需处理");row.put("reviewStatus",id==1?"待审核":"已审核");row.put("reviewReason",id==3?"理由":"");dao.update(row);}
        mvc.perform(post("/api/ai/parallel-replay/database-issues/filter-options").contentType("application/json").content("{\"field\":\"reviewStatus\",\"reviewStatuses\":[\"不存在\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(4)).andExpect(jsonPath("$.data.matchedIssueCount").value(4));
        mvc.perform(post("/api/ai/parallel-replay/database-issues").contentType("application/json").content("{\"reviewStatuses\":[\"已审核（未填写原因）\",\"待审核\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].reviewStatus").value("已审核"))
            .andExpect(jsonPath("$.data.items[0].reviewStatusDisplay").value("已审核（未填写原因）"));
        byte[] content=mvc.perform(post("/api/ai/parallel-replay/database-issues/export").contentType("application/json").content("{\"reviewStatuses\":[\"已审核（已填写原因）\"]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(content))){assertEquals("审核状态",workbook.getSheetAt(0).getRow(0).getCell(10).getStringCellValue());assertEquals("已审核（已填写原因）",workbook.getSheetAt(0).getRow(1).getCell(10).getStringCellValue());}
    }
    @Test void listPermissionsResolveTechnologyOwnersOncePerPageAndDoNotUseNamesAsIdentifiers() throws Exception {
        registerTechnologyOwner("public","koba_off_sheet_acct","贷款组","E001");
        importBatch("DB20261012-01",3,1,2);clearInvocations(users);
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].technologyOwnerDisplay").value("编辑人(E001)"));
        verify(users,times(1)).findByUsernamesOrEmpNos(anyCollection());
        verify(users,never()).findByUsername(anyString());verify(users,never()).findByEmpNo(anyString());
        jdbc.update("UPDATE dii_replay_db_compare_registration SET group_owner_emp_no='编辑人'");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        var row=dao.find(1);row.put("issueStatus","无需处理");row.put("reviewStatus","待审核");dao.update(row);
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("issueIds","koba_off_sheet_acct-ccbs"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].canReview").value(false));
    }
    @Test void currentRegistrationDrivesDomainOwnerFilteringOptionsExportAndMutationResponsesWithoutReimport() throws Exception {
        importBatch("DB20261012-01",3,1,2);
        assertEquals("",dao.find(1).get("domain"));assertEquals("",dao.find(1).get("owner"));
        registerTechnologyOwner("public","KOBA_OFF_SHEET_ACCT","登记领域","E001");
        jdbc.update("UPDATE dii_replay_db_compare_registration SET reviser_name=' 登记修订人 ',reviser_username=' c-owner ',reviser_emp_no='R001'");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        String snapshot=jdbc.queryForObject("SELECT data_snapshot FROM dii_replay_database_issue WHERE id=1",String.class);
        var history=dao.history(1);
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","登记领域").param("owners","登记修订人(c-owner)").param("differenceCountOrder","DESC").param("limit","1").param("offset","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.items[0].issueId").value("koba_off_sheet_acct-dtl_sn"))
            .andExpect(jsonPath("$.data.items[0].domain").value("登记领域")).andExpect(jsonPath("$.data.items[0].owner").value("登记修订人(c-owner)"));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domain","登记领域").param("owner","c-owner"))
            .andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domain","贷款组"))
            .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","domain").param("domains","不存在").param("owners","登记修订人(c-owner)"))
            .andExpect(jsonPath("$.data.candidateCount").value(1)).andExpect(jsonPath("$.data.items[0].value").value("登记领域")).andExpect(jsonPath("$.data.items[0].count").value(3));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/filter-options").param("field","owner").param("owners","不存在").param("domains","登记领域").param("keyword","C-OWNER"))
            .andExpect(jsonPath("$.data.items[0].value").value("登记修订人(c-owner)"));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/options"))
            .andExpect(jsonPath("$.data.domains[0]").value("登记领域")).andExpect(jsonPath("$.data.owners[0]").value("登记修订人(c-owner)"));
        byte[] exported=mvc.perform(get("/api/ai/parallel-replay/database-issues/export").param("domains","登记领域").param("owners","登记修订人(c-owner)").param("limit","1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(exported))){
            assertEquals(3,workbook.getSheetAt(0).getLastRowNum());
            assertEquals("登记领域",workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("登记修订人(c-owner)",workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
        }
        assertEquals(snapshot,jdbc.queryForObject("SELECT data_snapshot FROM dii_replay_database_issue WHERE id=1",String.class));
        assertEquals(history,dao.history(1));assertEquals("",dao.find(1).get("domain"));
        jdbc.update("UPDATE dii_replay_db_compare_registration SET domain_name='最新领域',reviser_username='  ',reviser_emp_no=' R002 '");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","最新领域").param("owners","登记修订人(R002)"))
            .andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items[0].owner").value("登记修订人(R002)"));
        mvc.perform(patch("/api/ai/parallel-replay/database-issues/1").contentType("application/json").content("{\"version\":0,\"issueStatus\":\"无需处理\",\"issueType\":\"合理差异\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.domain").value("最新领域")).andExpect(jsonPath("$.data.owner").value("登记修订人(R002)"))
            .andExpect(jsonPath("$.data.canReview").value(true));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":1,\"reason\":\"已核实\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.domain").value("最新领域")).andExpect(jsonPath("$.data.owner").value("登记修订人(R002)"));
        assertEquals("",dao.find(1).get("domain"));assertEquals("",dao.find(1).get("owner"));
    }
    @Test void sameTableAcrossSchemasIsAmbiguousRegardlessOfReportDomainAndDeletedRegistrationDoesNotCount() throws Exception {
        importBatch("DB20261012-01",3,0,0);
        var row=dao.find(1);row.put("issueStatus","无需处理");row.put("reviewStatus","待审核");dao.update(row);
        registerTechnologyOwner("public","koba_off_sheet_acct","另一领域","E001");
        jdbc.update("UPDATE dii_replay_db_compare_registration SET reviser_name='修订人',reviser_emp_no='R003'");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(jsonPath("$.data.items[0].domain").value("另一领域")).andExpect(jsonPath("$.data.items[0].owner").value("修订人(R003)"))
            .andExpect(jsonPath("$.data.items[0].canReview").value(true));
        registerTechnologyOwner("another","koba_off_sheet_acct","贷款组","E001");
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","").param("owners",""))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].domain").value(""))
            .andExpect(jsonPath("$.data.items[0].owner").value(""))
            .andExpect(jsonPath("$.data.items[0].technologyOwnerDisplay").value(""))
            .andExpect(jsonPath("$.data.items[0].canReview").value(false));
        mvc.perform(post("/api/ai/parallel-replay/database-issues/1/review").contentType("application/json").content("{\"version\":1,\"reason\":\"已核实\"}"))
            .andExpect(status().isForbidden());
        jdbc.update("UPDATE dii_replay_db_compare_registration SET domain_name='另一领域' WHERE schema_name='another'");
        mvc.perform(get("/api/ai/parallel-replay/database-issues"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].domain").value(""));
        importBatch("DB20261013-01",2,0,0);
        assertEquals("",dao.find(1).get("domain"));assertEquals("",dao.find(1).get("owner"));
        jdbc.update("UPDATE dii_replay_db_compare_registration SET deleted=1 WHERE schema_name='another'");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        mvc.perform(get("/api/ai/parallel-replay/database-issues").param("domains","另一领域"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].owner").value("修订人(R003)"))
            .andExpect(jsonPath("$.data.items[0].canReview").value(true));
    }
    private void registerTechnologyOwner(String schema,String table,String domain,String identity){
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,table_comment,domain_name,group_owner_emp_no,group_owner_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES (?,?,?,?,?,'编辑人','2026-10-12','editor','编辑人',CURRENT_TIMESTAMP,'editor','编辑人',CURRENT_TIMESTAMP)",schema,table,"表中文名",domain,identity);
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, table);
    }
    private void registerMetadata(String table,String domain,String name,String username,String empNo){
        registerTechnologyOwner("public",table,domain,"");
        jdbc.update("UPDATE dii_replay_db_compare_registration SET reviser_name=?,reviser_username=?,reviser_emp_no=? WHERE schema_name='public' AND table_name=?",name,username,empNo,table);
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, table);
    }
    private void importBatch(String batch,long ccbs,long other,long field) throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(ReplayDatabaseIssueTest.file(true,batch,ccbs,other,field)))
            .andExpect(status().isOk());
    }
    private void seedFilterRows() throws Exception {
        registerMetadata("koba_off_sheet_acct","贷款组","张三","c-zhangs3","");
        registerMetadata("extra_table","存款组","张三,李四","","");
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(ReplayDatabaseIssueTest.file(true,"DB20261012-01",3,1,2)))
            .andExpect(status().isOk());
        var row=new LinkedHashMap<String,Object>(dao.findByIssueId("koba_off_sheet_acct-ccbs"));
        row.put("issueId","extra_table-ccbs");row.put("tableName","extra_table");row.put("tableComment","账户,明细");
        row.put("domain","存款组");row.put("owner","张三,李四");row.put("differenceCount",8L);row.put("issueType","代码问题");dao.insert(row);
    }
}
