package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueExcelParserTest {

    private ReplayIssueExcelParser parser;

    @BeforeEach
    void setUp() {
        parser = new ReplayIssueExcelParser();
    }

    @Test
    void importsOnlyEightNamedSheetsAndDerivesSandbox() throws Exception {
        MockMultipartFile file = ReplayIssueTestFixtures.validWorkbook(1, true);

        ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file);

        assertEquals(8, parsed.rows().size());
        assertEquals(8, parsed.rowsBySheet().size());
        assertEquals(4, parsed.sandboxRows());
        assertEquals(4, parsed.nonSandboxRows());
        assertEquals("公共组", parsed.rows().get(0).groupName());
        assertFalse(parsed.rows().get(0).sandbox());
        assertEquals("公共组", parsed.rows().get(4).groupName());
        assertTrue(parsed.rows().get(4).sandbox());
        assertFalse(parsed.rowsBySheet().containsKey("总信息"));
    }

    @Test
    void normalizesSandboxSheetDomainToItsGroupName() throws Exception {
        ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(
                ReplayIssueTestFixtures.workbook(
                        ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of("领域", "沙箱-公共组")),
                        ReplayIssueTestFixtures.HEADERS, false));

        assertEquals("公共组", parsed.rows().get(0).domain());
        assertEquals("公共组", parsed.rows().get(4).domain());
        assertTrue(parsed.rows().get(4).sandbox());
    }

    @Test
    void matchesHeadersAfterReorderingAndPreservesDisplayedIdentifiers() throws Exception {
        List<String> reversed = new ArrayList<>(ReplayIssueExcelParser.HEADERS);
        Collections.reverse(reversed);
        MockMultipartFile file = ReplayIssueTestFixtures.workbook(
                ReplayIssueTestFixtures.oneRowPerTargetSheet(
                        Map.of("流水号", "001012213710102", "issue_id", "000845")),
                reversed, false);

        ReplayIssueRow row = parser.parse(file).rows().get(0);

        assertEquals("001012213710102", row.serialNo());
        assertEquals("000845", row.issueId());
    }

    @Test
    void repairDateHeadersAreOptionalAndRenamedHeaderIsAccepted() throws Exception {
        List<String> headers = new ArrayList<>(ReplayIssueTestFixtures.HEADERS);
        headers.remove("数据修复日期");
        headers.add("缺陷修复日期");

        ReplayIssueRow row = parser.parse(ReplayIssueTestFixtures.workbook(
                ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of("issue_key", "KEY-1")), headers, false))
                .rows().get(0);

        assertEquals("KEY-1", row.issueKey());
        assertEquals("", row.dataRepairDate());
    }

    @Test
    void rejectsMissingTargetSheetWithItsName() {
        Map<String, List<Map<String, String>>> sheets = ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of());
        sheets.remove("沙箱-结算组");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(ReplayIssueTestFixtures.workbook(sheets, ReplayIssueTestFixtures.HEADERS, false)));

        assertTrue(exception.getMessage().contains("沙箱-结算组"));
    }

    @Test
    void rejectsMissingHeaderWithSheetAndHeader() {
        List<String> headers = new ArrayList<>(ReplayIssueTestFixtures.HEADERS);
        headers.remove("issue_key");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(ReplayIssueTestFixtures.workbook(
                        ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of()), headers, false)));

        assertTrue(exception.getMessage().contains("公共组"));
        assertTrue(exception.getMessage().contains("issue_key"));
    }

    @Test
    void usesEvaluatedFormulaDisplayText() throws Exception {
        MockMultipartFile file = workbookWithFormula();

        ReplayIssueRow row = parser.parse(file).rows().get(0);

        assertEquals("000845", row.issueId());
        assertEquals("001012213710102", row.serialNo());
    }

    @Test
    void skipsFullyBlankRowsAndRetainsPartiallyBlankRows() throws Exception {
        MockMultipartFile file = workbookWithBlankAndPartialRows();

        ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file);

        assertEquals(9, parsed.rows().size());
        assertEquals(2, parsed.rowsBySheet().get("公共组"));
        ReplayIssueRow partial = parsed.rows().get(1);
        assertEquals("only issue", partial.issueDescription());
        assertEquals("", partial.issueId());
        assertEquals(3, partial.rowOrder());
    }

    @Test
    void rejectsDuplicateHeaders() {
        List<String> headers = new ArrayList<>(ReplayIssueTestFixtures.HEADERS);
        headers.add(" ISSUE_ID ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(ReplayIssueTestFixtures.workbook(
                        ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of()), headers, false)));

        assertTrue(exception.getMessage().contains("公共组"));
        assertTrue(exception.getMessage().contains("issue_id"));
    }

    @Test
    void rejectsAllEmptyTargetSheets() {
        Map<String, List<Map<String, String>>> emptySheets = new LinkedHashMap<>();
        for (String sheet : ReplayIssueTestFixtures.TARGET_SHEETS) {
            emptySheets.put(sheet, List.of());
        }

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(ReplayIssueTestFixtures.workbook(
                        emptySheets, ReplayIssueTestFixtures.HEADERS, false)));

        assertTrue(exception.getMessage().contains("没有可导入数据"));
    }

    private MockMultipartFile workbookWithFormula() throws IOException {
        try (Workbook workbook = baseWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet("公共组");
            Row row = sheet.createRow(1);
            row.createCell(16).setCellValue("001012213710102");
            Cell issueId = row.createCell(20);
            issueId.setCellFormula("845");
            issueId.setCellStyle(workbook.createCellStyle());
            issueId.getCellStyle().setDataFormat(workbook.createDataFormat().getFormat("000000"));
            workbook.write(output);
            return file(output);
        }
    }

    private MockMultipartFile workbookWithBlankAndPartialRows() throws IOException {
        try (Workbook workbook = baseWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet("公共组");
            Row full = sheet.createRow(1);
            full.createCell(0).setCellValue("公共组");
            full.createCell(20).setCellValue("first");
            sheet.createRow(2);
            Row partial = sheet.createRow(3);
            partial.createCell(8).setCellValue("only issue");
            for (String targetSheet : ReplayIssueTestFixtures.TARGET_SHEETS.subList(1,
                    ReplayIssueTestFixtures.TARGET_SHEETS.size())) {
                workbook.getSheet(targetSheet).createRow(1).createCell(0).setCellValue(targetSheet);
            }
            workbook.write(output);
            return file(output);
        }
    }

    private Workbook baseWorkbook() {
        Workbook workbook = new XSSFWorkbook();
        for (String targetSheet : ReplayIssueTestFixtures.TARGET_SHEETS) {
            Sheet sheet = workbook.createSheet(targetSheet);
            Row header = sheet.createRow(0);
            for (int column = 0; column < ReplayIssueTestFixtures.HEADERS.size(); column++) {
                header.createCell(column).setCellValue(ReplayIssueTestFixtures.HEADERS.get(column));
            }
        }
        return workbook;
    }

    private MockMultipartFile file(ByteArrayOutputStream output) {
        return new MockMultipartFile("file", "replay-issues.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
}
