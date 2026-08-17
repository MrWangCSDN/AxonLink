package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.service.ReplayIssueEditService;
import com.axonlink.ai.replay.service.ReplayIssueExcelParser;
import com.axonlink.ai.replay.service.ReplayIssueFullRefreshExcelParser;
import com.axonlink.ai.replay.service.ReplayIssueFullRefreshService;
import com.axonlink.ai.replay.service.ReplayIssueImportGate;
import com.axonlink.ai.replay.service.ReplayIssueImportService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayIssueControllerTest {

    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayIssueImportService importService;
    private ReplayIssueImportGate importGate;
    private DaoIndexAnalysisProperties properties;
    private UserPrincipalResolver.Resolved resolvedUser;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128), real_name VARCHAR(128), emp_no VARCHAR(64), email VARCHAR(128), phone VARCHAR(64), department VARCHAR(128), status INT, remark VARCHAR(255), creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.update("INSERT INTO ccbs_ai_sys_user (username, real_name, status) VALUES (?,?,?)", "sunhy1", "孙海英", 1);
        SysUserDao userDao = new SysUserDao(jdbc);
        importGate = new ReplayIssueImportGate();
        importService = new ReplayIssueImportService(new ReplayIssueExcelParser(), dao, importGate);
        ReplayIssueFullRefreshService fullRefreshService = new ReplayIssueFullRefreshService(
                new ReplayIssueFullRefreshExcelParser(), dao, userDao, importGate);
        properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        ReplayIssueEditService editService = new ReplayIssueEditService(dao, userDao);
        resolvedUser = new UserPrincipalResolver.Resolved("LDAP", "sunhy1", userDao.findByUsername("sunhy1"));
        UserPrincipalResolver resolver = new UserPrincipalResolver() {
            @Override public Resolved resolve(jakarta.servlet.http.HttpServletRequest request) {
                return resolvedUser;
            }
        };
        ReplayIssueController controller = new ReplayIssueController(importService, fullRefreshService, dao,
                properties, editService, resolver);
        mvc = MockMvcBuilders.standaloneSetup(controller, new ReplayIssueUserController(userDao)).build();
    }

    @Test
    void validTokenImportsThenListReturnsRows() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(8));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("limit", "50")
                        .param("offset", "0")
                        .param("sandbox", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[0].is_sandbox").value(1));
    }

    @Test
    void listAcceptsIndependentDeveloperAndBankOwnerFilters() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-A", "first"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-B", "second")),
                LocalDateTime.of(2026, 8, 11, 10, 0));
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)",
                List.of(
                        new Object[] {"贷款组", "T-A", "交易A", "张开发", "刘科技", LocalDateTime.of(2026, 8, 11, 9, 0)},
                        new Object[] {"贷款组", "T-B", "交易B", "张开发", "王科技", LocalDateTime.of(2026, 8, 11, 9, 0)}));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("developer", "张开发")
                        .param("bankOwner", "刘科技"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("T-A"));
    }

    @Test
    void exportsAllRowsMatchingQueryFilters() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)",
                "公共组", "6208", "交易6208", "张开发", "刘科技", LocalDateTime.of(2026, 8, 11, 9, 0));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("groupName", "公共组")
                        .param("sandbox", "false")
                        .param("issueStatus", "打开"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(body.length > 100, "导出文件应包含 Excel 内容");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertEquals(2, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("公共组", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("否", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals("开发负责人", workbook.getSheetAt(0).getRow(0).getCell(10).getStringCellValue());
            assertEquals("科技负责人", workbook.getSheetAt(0).getRow(0).getCell(11).getStringCellValue());
            assertEquals("张开发", workbook.getSheetAt(0).getRow(1).getCell(10).getStringCellValue());
            assertEquals("刘科技", workbook.getSheetAt(0).getRow(1).getCell(11).getStringCellValue());
            assertEquals("出现批次", workbook.getSheetAt(0).getRow(0).getCell(27).getStringCellValue());
            assertTrue(!workbook.getSheetAt(0).getRow(1).getCell(27).getStringCellValue().isBlank());
        }
    }

    @Test
    void fullRefreshRequiresTokenAndExplicitConfirmation() throws Exception {
        MockMultipartFile file = validFullRefreshWorkbook();
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(file).param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(file).param("confirm", "wrong")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("FULL_REFRESH")));
    }

    @Test
    void fullRefreshAcceptsTokenOnlyAuthentication() throws Exception {
        resolvedUser = new UserPrincipalResolver.Resolved("UNKNOWN", "dii-token", null);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());

        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                10, 0, null, null, null, null, null)).get(0).get("id")).longValue();
        assertEquals("dii-token", dao.findHistoryByIssueId(id, 10).get(0).operatorUsername());
    }

    @Test
    void fullRefreshUsesSystemOperatorWhenSecurityIsDisabled() throws Exception {
        resolvedUser = new UserPrincipalResolver.Resolved("ANONYMOUS", null, null);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());

        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                10, 0, null, null, null, null, null)).get(0).get("id")).longValue();
        assertEquals("SYSTEM", dao.findHistoryByIssueId(id, 10).get(0).operatorUsername());
    }

    @Test
    void fullRefreshRejectsMissingFileWithSharedBadRequestEnvelope() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件为空"));
    }

    @Test
    void fullRefreshImportsOnlyFirstSheetAndRecordsLoggedInOperator() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(1))
                .andExpect(jsonPath("$.data.rowsBySheet.0731").value(1))
                .andExpect(jsonPath("$.data.rowsBySheet.0803").doesNotExist());

        List<Map<String, Object>> rows = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                10, 0, null, null, null, null, null));
        assertEquals(1, rows.size());
        long id = ((Number) rows.get(0).get("id")).longValue();
        assertEquals("sunhy1", dao.findHistoryByIssueId(id, 10).get(0).operatorUsername());
    }

    @Test
    void fullRefreshReturnsConflictWhenAnyImportIsRunning() throws Exception {
        ReflectionTestUtils.setField(importGate, "permit", new Semaphore(0));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已有导入任务正在执行"));
    }

    @Test
    void validLegacyXlsImportsAllTargetSheets() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(validLegacyWorkbook())
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(8));
    }

    @Test
    void configuredTokenRejectsMissingAndWrongValues() throws Exception {
        MockMultipartFile file = ReplayIssueTestFixtures.validWorkbook(1);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(file)
                        .header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void blankConfiguredTokenDisablesAuthentication() throws Exception {
        properties.getBatchTrigger().setToken("  ");

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(8));
    }

    @Test
    void blankFileReturnsBadRequest() throws Exception {
        MockMultipartFile blank = new MockMultipartFile("file", "replay-issues.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(blank)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void oversizedXlsxReturnsBadRequestInSharedEnvelope() throws Exception {
        MockMultipartFile oversized = new MockMultipartFile("file", "replay-issues.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[50 * 1024 * 1024 + 1]);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(oversized)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("文件不能超过 50MB"));
    }

    @Test
    void unsupportedExtensionReturnsBadRequest() throws Exception {
        MockMultipartFile csv = new MockMultipartFile("file", "replay-issues.csv", "text/csv",
                new byte[] {1});

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(csv)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void parserValidationReturnsBadRequest() throws Exception {
        MockMultipartFile missingSheets = ReplayIssueTestFixtures.workbook(
                Map.of("公共组", List.of()), ReplayIssueTestFixtures.HEADERS, false);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(missingSheets)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("缺少目标页签")));
    }

    @Test
    void concurrentImportReturnsConflict() throws Exception {
        ReflectionTestUtils.setField(importGate, "permit", new Semaphore(0));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void daoErrorReturnsInternalServerErrorWithoutExceptionLeakage() throws Exception {
        jdbc.execute("DROP TABLE dii_replay_issue");

        mvc.perform(get("/api/ai/parallel-replay/issues/stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("请求失败"))
                .andExpect(content().string(not(containsString("DII_REPLAY_ISSUE"))));
    }

    @Test
    void optionsAndStatsDescribeImportedSnapshot() throws Exception {
        importService.importFile(ReplayIssueTestFixtures.validWorkbook(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.length()").value(4))
                .andExpect(jsonPath("$.data.issueLevels[0]").value("交易级"))
                .andExpect(jsonPath("$.data.issueTypes[0]").value("迁移问题"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.groupCount").value(4))
                .andExpect(jsonPath("$.data.sandboxCount").value(4))
                .andExpect(jsonPath("$.data.importedAt").isNotEmpty());
    }

    @Test
    void groupSummaryEndpointReturnsNonFixedStatusCounts() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-OPEN", "open"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-DEFERRED", "deferred"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "T-LEGACY", "legacy")),
                LocalDateTime.of(2026, 8, 11, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '延后修复' WHERE transaction_code = 'T-DEFERRED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '分析中' WHERE transaction_code = 'T-LEGACY'");

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].groupName").value("贷款组"))
                .andExpect(jsonPath("$.data[0].openCount").value(1))
                .andExpect(jsonPath("$.data[0].deferredCount").value(1))
                .andExpect(jsonPath("$.data[0].totalCount").value(3));
    }

    @Test
    void personRankingEndpointKeepsDeveloperCombinationAsOneRankingRow() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-COMBINATION", "one"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-COMBINATION", "two")),
                LocalDateTime.of(2026, 8, 11, 9, 0));
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                "贷款", "T-COMBINATION", "组合交易", "张三(c-zhangs3)、李四(c-lisi)", LocalDateTime.of(2026, 8, 11, 8, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].groupName").value("贷款组"))
                .andExpect(jsonPath("$.data[0].developer").value("张三(c-zhangs3)、李四(c-lisi)"))
                .andExpect(jsonPath("$.data[0].totalCount").value(2));
    }

    @Test
    void roundEndpointsGroupImportResultAndAllManualChanges() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, null)).get(0).get("id")).longValue();

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"延后修复\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"第一次\",\"finalSolution\":\"方案一\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"修复待验证\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"第二次\",\"finalSolution\":\"方案二\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ai/parallel-replay/issues/rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].inputRows").value(8))
                .andExpect(jsonPath("$.data[0].operatorUsername").value("SYSTEM"))
                .andExpect(jsonPath("$.data[0].operatorRealName").value("系统"));

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].appeared").value(true))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(2))
                .andExpect(jsonPath("$.data[0].finalStatus").value("修复待验证"))
                .andExpect(jsonPath("$.data[0].manualEvents.length()").value(2));
    }

    @Test
    void roundTrackingMovesLaterManualEditsToTheNewIssueRound() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                        50, 0, null, null, null, null, "TRAN|6208|响应码|公共组|1"))
                .get(0).get("id")).longValue();

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"延后修复\",\"issueType\":\"代码问题\","
                                + "\"initialAnalysis\":\"第一轮分析\",\"finalSolution\":\"第一轮方案\","
                                + "\"cooperationPersonUsername\":null,\"remark\":\"第一轮修改\"}"))
                .andExpect(status().isOk());

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"修复待验证\",\"issueType\":\"代码问题\","
                                + "\"initialAnalysis\":\"第二轮分析\",\"finalSolution\":\"第二轮方案\","
                                + "\"cooperationPersonUsername\":null,\"remark\":\"第二轮修改\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(1))
                .andExpect(jsonPath("$.data[0].manualEvents[0].remark").value("第二轮修改"))
                .andExpect(jsonPath("$.data[1].manualChangeCount").value(1))
                .andExpect(jsonPath("$.data[1].manualEvents[0].remark").value("第一轮修改"));
    }

    @Test
    void roundTrackingShowsInheritedContentSeparatelyFromManualChanges() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        Map<String, Object> issue = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                        50, 0, null, null, null, null, "TRAN|6208|响应码|公共组|1"))
                .get(0);
        long id = ((Number) issue.get("id")).longValue();
        jdbc.update("UPDATE dii_replay_issue SET issue_type=?,initial_analysis=?,final_solution=?,"
                        + "cooperation_person_username=?,cooperation_person_real_name=?,remark=? WHERE id=?",
                "代码问题", "人工分析", "人工方案", "alice", "艾丽丝", "人工备注", id);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actionType").value("覆盖并继承人工内容"))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(0))
                .andExpect(jsonPath("$.data[0].manualEvents.length()").value(0))
                .andExpect(jsonPath("$.data[0].inheritedEvents.length()").value(1))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].operationType")
                        .value("基础数据覆盖，人工内容继承"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].issueType").value("代码问题"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].initialAnalysis").value("人工分析"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].finalSolution").value("人工方案"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].cooperationPersonUsername").value("alice"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].cooperationPersonRealName").value("艾丽丝"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].remark").value("人工备注"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].beforeSnapshot").isNotEmpty())
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].afterSnapshot").isNotEmpty())
                .andExpect(jsonPath("$.data[0].inheritedEvents[0].incomingSnapshot").isNotEmpty());
    }

    @Test
    void roundTrackingShowsMissingOpenIssueAsAutoRepaired() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 99, "MISSING-TX", "原基础数据")),
                LocalDateTime.of(2026, 8, 10, 9, 0));
        Map<String, Object> seeded = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, "key-99")).get(0);
        long id = ((Number) seeded.get("id")).longValue();
        jdbc.update("UPDATE dii_replay_issue SET issue_type=?,initial_analysis=?,final_solution=?,"
                        + "cooperation_person_username=?,cooperation_person_real_name=?,remark=? WHERE id=?",
                "代码问题", "人工分析", "人工方案", "alice", "艾丽丝", "人工备注", id);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoRepairedRows").value(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].appeared").value(false))
                .andExpect(jsonPath("$.data[0].statusBefore").value("打开"))
                .andExpect(jsonPath("$.data[0].statusAfter").value("已修复"))
                .andExpect(jsonPath("$.data[0].actionType").value("自动修复"))
                .andExpect(jsonPath("$.data[0].finalStatus").value("已修复"))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(0));

        var history = dao.findHistoryByIssueId(id, 10).get(0);
        assertEquals("问题自动修复", history.operationType());
        assertTrue(history.afterSnapshot().contains("人工分析"));
        assertTrue(history.afterSnapshot().contains("人工备注"));
        org.junit.jupiter.api.Assertions.assertNull(history.incomingSnapshot());
        assertEquals(dao.findIssueRounds(id).get(0).roundId(), history.contextRoundId());
    }

    @Test
    void fullRefreshHistoryIsShownAsBaseDataWithoutCreatingRound() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, null)).get(0).get("id")).longValue();

        mvc.perform(get("/api/ai/parallel-replay/issues/rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roundCode").value("基础数据"))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(1));
    }

    @Test
    void oversizedLimitIsClampedThroughObservableItemCount() throws Exception {
        List<ReplayIssueRow> rows = new ArrayList<>();
        for (int rowOrder = 1; rowOrder <= 201; rowOrder++) {
            rows.add(ReplayIssueTestFixtures.row("公共组", false, rowOrder,
                    "T-" + rowOrder, "issue-" + rowOrder));
        }
        dao.replaceAll(rows, LocalDateTime.of(2026, 8, 4, 12, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(201))
                .andExpect(jsonPath("$.data.items.length()").value(200));
    }

    @Test
    void patchAcceptsAllFiveFieldsAndRejectsSystemStatus() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")),
                LocalDateTime.of(2026, 8, 5, 9, 0));
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(1, 0, null, null, null, null, null))
                .get(0).get("id")).longValue();
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"修复待验证\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"分析\",\"finalSolution\":\"方案\",\"cooperationPersonUsername\":\"sunhy1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueStatus").value("修复待验证"))
                .andExpect(jsonPath("$.data.cooperationPersonRealName").value("孙海英"));
        var history = dao.findHistoryByIssueId(id, 10);
        assertEquals("sunhy1", history.get(0).operatorUsername());
        assertEquals("孙海英", history.get(0).operatorRealName());

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"已修复\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"\",\"finalSolution\":\"\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedPrincipalWithoutUserMappingCanSaveAndIsRecordedAsOperator() throws Exception {
        long id = seedIssue();
        resolvedUser = new UserPrincipalResolver.Resolved("UIAS", "A012345", null);

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"延后修复\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"分析\",\"finalSolution\":\"方案\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isOk());

        var history = dao.findHistoryByIssueId(id, 10);
        assertEquals(1, history.size());
        assertEquals("A012345", history.get(0).operatorUsername());
        assertEquals("A012345", history.get(0).operatorRealName());
    }

    @Test
    void anonymousPrincipalCannotSave() throws Exception {
        long id = seedIssue();
        resolvedUser = new UserPrincipalResolver.Resolved("ANONYMOUS", null, null);

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"分析中\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"分析\",\"finalSolution\":\"方案\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));

        assertEquals(0, dao.findHistoryByIssueId(id, 10).size());
    }

    @Test
    void userLookupMatchesUsernameAndRealName() throws Exception {
        mvc.perform(get("/api/ai/parallel-replay/issues/users").param("keyword", "sunh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].displayName").value("孙海英(sunhy1)"));
        mvc.perform(get("/api/ai/parallel-replay/issues/users").param("keyword", "海英"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("sunhy1"));
    }

    private static MockMultipartFile validLegacyWorkbook() {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String sheetName : ReplayIssueTestFixtures.TARGET_SHEETS) {
                writeLegacySheet(workbook, sheetName);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "replay-issues.xls", "application/vnd.ms-excel",
                    output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create legacy test workbook", exception);
        }
    }

    private MockMultipartFile validFullRefreshWorkbook() {
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("0731", List.of(fullRefreshRow("KEY-0731", "否")));
        sheets.put("0803", List.of(fullRefreshRow("KEY-0803", "是")));
        return ReplayIssueTestFixtures.fullRefreshWorkbook(sheets);
    }

    private Map<String, String> fullRefreshRow(String issueKey, String sandbox) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String header : ReplayIssueTestFixtures.FULL_REFRESH_HEADERS) {
            row.put(header, "");
        }
        row.put("领域", "公共组");
        row.put("问题描述", "基础数据");
        row.put("issue_id", issueKey.replace("KEY", "ISSUE"));
        row.put("issue_key", issueKey);
        row.put("问题状态", "打开");
        row.put("是否沙箱", sandbox);
        return row;
    }

    private SysUserDao userDao() {
        return new SysUserDao(jdbc);
    }

    private static void writeLegacySheet(HSSFWorkbook workbook, String sheetName) {
        Sheet sheet = workbook.createSheet(sheetName);
        Row headerRow = sheet.createRow(0);
        Row dataRow = sheet.createRow(1);
        for (int columnIndex = 0; columnIndex < ReplayIssueTestFixtures.HEADERS.size(); columnIndex++) {
            String header = ReplayIssueTestFixtures.HEADERS.get(columnIndex);
            headerRow.createCell(columnIndex).setCellValue(header);
            dataRow.createCell(columnIndex).setCellValue(legacyValue(header, sheetName));
        }
    }

    private static String legacyValue(String header, String sheetName) {
        return switch (header) {
            case "领域" -> sheetName.replace("沙箱-", "");
            case "序号" -> "1";
            case "issue_key" -> "TRAN|6208|响应码|" + sheetName;
            default -> "value";
        };
    }

    private long seedIssue() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")),
                LocalDateTime.of(2026, 8, 5, 9, 0));
        return ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, null)).get(0).get("id")).longValue();
    }
}
