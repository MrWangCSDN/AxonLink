package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.service.ReplayIssueExcelParser;
import com.axonlink.ai.replay.service.ReplayIssueImportService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayIssueControllerTest {

    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayIssueImportService importService;
    private DaoIndexAnalysisProperties properties;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        importService = new ReplayIssueImportService(new ReplayIssueExcelParser(), dao);
        properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        ReplayIssueController controller = new ReplayIssueController(importService, dao, properties);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
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
        ReflectionTestUtils.setField(importService, "importPermit", new Semaphore(0));

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
                .andExpect(jsonPath("$.data.issueTypes[0]").value("数据差异"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.groupCount").value(4))
                .andExpect(jsonPath("$.data.sandboxCount").value(4))
                .andExpect(jsonPath("$.data.importedAt").isNotEmpty());
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
            case "issue_key" -> "TRAN|6208|响应码";
            default -> "value";
        };
    }
}
