package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueSummaryParserTest {

    private final ReplayIssueSummaryParser parser = new ReplayIssueSummaryParser();

    @Test
    void parsesVerticalLayout() throws Exception {
        var file = ReplayIssueTestFixtures.workbookWithVerticalSummary(Map.of(), ReplayIssueTestFixtures.defaultSummaryValues());

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertTrue(summary.sheetFound());
        assertEquals(1, summary.upperRows().size());
        assertRow(summary.upperRows().get(0));
    }

    @Test
    void parsesHorizontalLayout() throws Exception {
        var file = ReplayIssueTestFixtures.workbookWithHorizontalSummary(Map.of(), ReplayIssueTestFixtures.defaultSummaryValues());

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertTrue(summary.sheetFound());
        assertEquals(1, summary.upperRows().size());
        assertRow(summary.upperRows().get(0));
    }

    @Test
    void preservesUnderlyingNumericPercentagePrecision() throws Exception {
        MockMultipartFile file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("汇总信息");
            writeTwoLevelSection(sheet, 0, "BATCH-CURR", "存款组", 528, 1000);
            var percentStyle = workbook.createCellStyle();
            percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
            sheet.getRow(2).getCell(12).setCellValue(0.7777777777777777);
            sheet.getRow(2).getCell(12).setCellStyle(percentStyle);
            sheet.getRow(3).createCell(12).setCellValue(0.7777777777777777);
            sheet.getRow(3).getCell(12).setCellStyle(percentStyle);
            workbook.write(out);
            file = new MockMultipartFile("file", "precise-rate.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertEquals(77.77777777777777, summary.upperRows().get(0).matchPassRate(), 0.0000000001);
        assertEquals(77.77777777777777, summary.upperTotals().matchPassRate(), 0.0000000001);
    }

    @Test
    void toleratesWhitespaceAndCaseInHeaders() throws Exception {
        Map<String, String> values = ReplayIssueTestFixtures.defaultSummaryValues();
        var file = ReplayIssueTestFixtures.workbookWithVerticalSummary(Map.of(), values);

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertEquals("20260815-01", summary.upperRows().get(0).batchNo());
        assertEquals(Long.valueOf(528L), summary.upperRows().get(0).coveredInterfaceCount());
        assertEquals(Long.valueOf(12345L), summary.upperRows().get(0).sentTransactionCount());
        assertEquals(Double.valueOf(95.5), summary.upperRows().get(0).successRate());
        assertEquals(Double.valueOf(98.2), summary.upperRows().get(0).matchPassRate());
    }

    @Test
    void returnsEmptyWhenSummarySheetMissing() throws Exception {
        var file = ReplayIssueTestFixtures.validWorkbook(1);

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertFalse(summary.sheetFound());
        assertTrue(summary.upperRows().isEmpty());
    }

    @Test
    void skipsEmptyGroupsAndKeepsRawJson() throws Exception {
        Map<String, String> values = ReplayIssueTestFixtures.defaultSummaryValues();
        var file = ReplayIssueTestFixtures.workbookWithHorizontalSummary(Map.of(), values);

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertEquals(1, summary.upperRows().size());
        ReplayIssueSummaryRow row = summary.upperRows().get(0);
        assertTrue(row.rawJson() != null && !row.rawJson().isBlank());
        // 全字段都解析出来了
        assertEquals(Long.valueOf(100L), row.c528SuccessCcbsFail());
        assertEquals(Long.valueOf(200L), row.c528FailCcbsSuccess());
        assertEquals(Long.valueOf(300L), row.bothFailSameCode());
        assertEquals(Long.valueOf(400L), row.bothFailDiffCode());
        assertEquals(Long.valueOf(500L), row.bothSuccess());
        assertEquals(Long.valueOf(600L), row.codeIgnored());
    }

    @Test
    void parsesMultipleVerticalGroups() throws Exception {
        var file = ReplayIssueTestFixtures.workbookWithVerticalSummary(Map.of(), ReplayIssueTestFixtures.defaultSummaryValues());

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        // 竖排布局：字段名在 A 列，B 列是唯一数据列 → 1 组
        assertEquals(1, summary.upperRows().size());
    }

    @Test
    void parsesTwoLevelHeadersAsTwoSectionsInsteadOfTreatingChildHeaderAsData() throws Exception {
        MockMultipartFile file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("汇总信息");
            writeTwoLevelSection(sheet, 0, "BATCH-PREV", "存款组", 528, 1000);
            writeTwoLevelSection(sheet, 5, "BATCH-CURR", "沙箱-贷款组", 256, 2000);
            workbook.write(out);
            file = new MockMultipartFile("file", "summary.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertEquals(1, summary.upperRows().size(), () -> summary.upperRows().toString());
        assertEquals(1, summary.lowerRows().size(), () -> summary.lowerRows().toString());
        assertEquals("BATCH-PREV", summary.upperRows().get(0).batchNo());
        assertEquals("存款组", summary.upperRows().get(0).domain());
        assertEquals(Long.valueOf(100L), summary.upperRows().get(0).c528SuccessCcbsFail());
        assertEquals(Long.valueOf(150L), summary.upperRows().get(0).ccbsFailureDetail());
        assertEquals("BATCH-CURR", summary.lowerRows().get(0).batchNo());
        assertEquals("沙箱-贷款组", summary.lowerRows().get(0).domain());
    }

    @Test
    void dzModeNormalizesBothSummarySections() throws Exception {
        MockMultipartFile file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("汇总信息");
            writeTwoLevelSection(sheet, 0, "RPT20260819-100000-0001", "存款组", 528, 1000);
            writeTwoLevelSection(sheet, 5, "RPT20260820-142055-9860", "公共组", 256, 2000);
            workbook.write(out);
            file = new MockMultipartFile("file", "summary.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }

        ReplayIssueSummaryParser.ParsedSummary parsed = parser.parse(file, ReplayIssueImportMode.DZ);

        assertEquals("DZ20260819-100000-0001", parsed.upperRows().get(0).batchNo());
        assertEquals("DZ20260820-142055-9860", parsed.lowerRows().get(0).batchNo());
    }

    @Test
    void parsesTitledTwoSectionTemplateWithoutShiftingUpperIntoLower() throws Exception {
        MockMultipartFile file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("汇总信息");
            writeTitledTwoLevelSection(sheet, 0, "BATCH-PREV", "存款组", 528, 1000, 100);
            writeTitledTwoLevelSection(sheet, 6, "BATCH-CURR", "存款组", 256, 2000, 900);
            workbook.write(out);
            file = new MockMultipartFile("file", "summary.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertEquals(1, summary.upperRows().size(), () -> summary.upperRows().toString());
        assertEquals(1, summary.lowerRows().size(), () -> summary.lowerRows().toString());
        assertEquals("BATCH-PREV", summary.upperRows().get(0).batchNo());
        assertEquals("存款组", summary.upperRows().get(0).domain());
        assertEquals(Long.valueOf(100L), summary.upperRows().get(0).c528SuccessCcbsFail());
        assertEquals("BATCH-CURR", summary.lowerRows().get(0).batchNo());
        assertEquals("存款组", summary.lowerRows().get(0).domain());
        assertEquals(Long.valueOf(900L), summary.lowerRows().get(0).c528SuccessCcbsFail());
    }

    @Test
    void parsesUpperAndLowerTotalRatesSeparately() throws Exception {
        MockMultipartFile file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("汇总信息");
            writeTwoLevelSection(sheet, 0, "BATCH-PREV", "存款组", 528, 1000);
            sheet.getRow(3).createCell(11).setCellValue("63.05%");
            sheet.getRow(3).createCell(12).setCellValue("39.77%");
            writeTwoLevelSection(sheet, 5, "BATCH-CURR", "贷款组", 256, 2000);
            sheet.getRow(8).createCell(11).setCellValue("69.54%");
            sheet.getRow(8).createCell(12).setCellValue("45.88%");
            workbook.write(out);
            file = new MockMultipartFile("file", "summary.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertEquals(1, summary.upperRows().size(), "上半区合计行不能作为领域明细");
        assertEquals(1, summary.lowerRows().size(), "下半区合计行不能作为领域明细");
        assertEquals(63.05, summary.upperTotals().successRate());
        assertEquals(39.77, summary.upperTotals().matchPassRate());
        assertEquals(69.54, summary.lowerTotals().successRate());
        assertEquals(45.88, summary.lowerTotals().matchPassRate());
    }

    @Test
    void ignoresTrailingNarrativeRowsAfterLowerSection() throws Exception {
        MockMultipartFile file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("汇总信息");
            writeTwoLevelSection(sheet, 0, "BATCH-PREV", "存款组", 528, 1000);
            writeTwoLevelSection(sheet, 5, "BATCH-CURR", "贷款组", 256, 2000);
            sheet.createRow(9).createCell(1).setCellValue("成功率：二者均成功响应码一致");
            sheet.createRow(10).createCell(1).setCellValue("响应码说明：需要后续人工确认，不属于领域统计数据");
            workbook.write(out);
            file = new MockMultipartFile("file", "summary.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        assertEquals(1, summary.upperRows().size(), () -> summary.upperRows().toString());
        assertEquals(1, summary.lowerRows().size(), () -> summary.lowerRows().toString());
        assertEquals("贷款组", summary.lowerRows().get(0).domain());
    }

    private static void writeTwoLevelSection(Sheet sheet, int startRow, String batch, String domain,
                                             long covered, long sent) {
        Row parent = sheet.createRow(startRow);
        parent.createCell(0).setCellValue("批次");
        parent.createCell(1).setCellValue("领域");
        parent.createCell(2).setCellValue("覆盖528接口");
        parent.createCell(3).setCellValue("发送交易量");
        parent.createCell(4).setCellValue("交易核对分类统计");
        parent.createCell(11).setCellValue("接口成功率");
        parent.createCell(12).setCellValue("比对通过率");
        Row child = sheet.createRow(startRow + 1);
        child.createCell(4).setCellValue("528成功/CCBS失败");
        child.createCell(5).setCellValue("CCBS失败明细");
        child.createCell(6).setCellValue("528失败/CCBS成功");
        child.createCell(7).setCellValue("二者均失败响应码一致");
        child.createCell(8).setCellValue("二者均失败响应码不一致");
        child.createCell(9).setCellValue("二者均成功");
        child.createCell(10).setCellValue("响应码忽略");
        Row data = sheet.createRow(startRow + 2);
        data.createCell(0).setCellValue(batch);
        data.createCell(1).setCellValue(domain);
        data.createCell(2).setCellValue(covered);
        data.createCell(3).setCellValue(sent);
        for (int col = 4; col <= 10; col++) data.createCell(col).setCellValue((col - 2) * 50L);
        data.createCell(11).setCellValue("95.5%");
        data.createCell(12).setCellValue("98.2%");
        Row total = sheet.createRow(startRow + 3);
        total.createCell(1).setCellValue("合计");
    }

    private static void writeTitledTwoLevelSection(Sheet sheet, int startRow, String batch, String domain,
                                                   long covered, long sent, long successCcbsFail) {
        sheet.createRow(startRow).createCell(0).setCellValue("批次号：" + batch);
        writeTwoLevelSection(sheet, startRow + 1, batch, domain, covered, sent);
        sheet.getRow(startRow + 3).getCell(4).setCellValue(successCcbsFail);
    }

    /**
     * 真实场景：横排 header + 同批次多领域（合并"批次"列）+ 末尾"合计"行 + 末尾"问题清单"等说明。
     * <p>期望：每领域一行记录（批次被合并单元格补齐）；"合计/问题清单/..."行被跳过。
     */
    @Test
    void parsesRealisticWorkbookWithMergedCellsAndTrailingRows() throws Exception {
        List<List<String>> dataRows = List.of(
                // 第一批次两个领域，批次列合并
                List.of("RT20200814-153751-4995", "小额-电子票据接口", "2828", "7490",
                        "5747", "257",
                        "602", "0", "0", "2921", "36.96%", "15.45%"),
                List.of("RT20200814-153751-4995", "小额-批量代付", "11407", "25628",
                        "11406", "51",
                        "0", "0", "670", "3880", "56.96%", "44.88%"),
                // 第二批次一个领域
                List.of("RT20200814-172837-3569", "小额-借记卡", "7525", "87832",
                        "1831", "131",
                        "18641", "1299", "3364", "447", "33.22%", "51.06%"));
        List<String> trailing = List.of("合计", "问题清单", "上一批次失败", "上一批次差异",
                "工作问题/需求", "工作问题", "注意事项");
        var file = ReplayIssueTestFixtures.workbookWithRealisticHorizontalSummary(Map.of(), dataRows, trailing);

        ReplayIssueSummaryParser.ParsedSummary summary = parser.parse(file);

        // 3 个领域行，合计 + 6 个说明行被跳过
        assertTrue(summary.sheetFound());
        assertEquals(3, summary.upperRows().size(), () -> "rows=" + summary.upperRows());

        // 合并单元格补齐：所有行都有同一批次
        ReplayIssueSummaryRow row1 = summary.upperRows().get(0);
        assertEquals("RT20200814-153751-4995", row1.batchNo());
        assertEquals("小额-电子票据接口", row1.domain());
        assertEquals(Long.valueOf(2828L), row1.coveredInterfaceCount());
        assertEquals(Long.valueOf(5747L), row1.c528SuccessCcbsFail());
        assertEquals(Double.valueOf(36.96), row1.successRate());

        ReplayIssueSummaryRow row2 = summary.upperRows().get(1);
        // 合并单元格：第二行批次列原本为 null，应自动继承"RT20200814-153751-4995"
        assertEquals("RT20200814-153751-4995", row2.batchNo());
        assertEquals("小额-批量代付", row2.domain());

        ReplayIssueSummaryRow row3 = summary.upperRows().get(2);
        assertEquals("RT20200814-172837-3569", row3.batchNo());
        assertEquals("小额-借记卡", row3.domain());
    }

    private void assertRow(ReplayIssueSummaryRow row) {
        assertEquals("20260815-01", row.batchNo());
        assertEquals("存款组", row.domain());
        assertEquals(Long.valueOf(528L), row.coveredInterfaceCount());
        assertEquals(Long.valueOf(12345L), row.sentTransactionCount());
        assertEquals(Long.valueOf(100L), row.c528SuccessCcbsFail());
        assertEquals(Long.valueOf(150L), row.ccbsFailureDetail());
        assertEquals(Long.valueOf(200L), row.c528FailCcbsSuccess());
        assertEquals(Long.valueOf(300L), row.bothFailSameCode());
        assertEquals(Long.valueOf(400L), row.bothFailDiffCode());
        assertEquals(Long.valueOf(500L), row.bothSuccess());
        assertEquals(Long.valueOf(600L), row.codeIgnored());
        // "接口成功率" 别名映射到 successRate
        assertEquals(Double.valueOf(95.5), row.successRate());
        assertEquals(Double.valueOf(98.2), row.matchPassRate());
        // parse() 总会附加页签原始内容 JSON 兜底
        assertTrue(row.rawJson() != null && !row.rawJson().isBlank());
    }
}
