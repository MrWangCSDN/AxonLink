package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.DailyIssueSlice;
import com.axonlink.ai.replay.dto.DailyReportRow;
import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueDailyReportServiceTest {

    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayIssueDailyReportService service;
    private Path reportDir;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        reportDir = Files.createTempDirectory("daily-reports-test-");
        service = new ReplayIssueDailyReportService(dao, reportDir.toString());
    }

    @Test
    void aggregateGroupsByGroupNameAndSandbox() {
        List<DailyIssueSlice> slices = List.of(
                new DailyIssueSlice("存款组", true, "代码问题", "交易级", "已修复"),
                new DailyIssueSlice("存款组", true, "数据差异", "字段级", "打开"),
                new DailyIssueSlice("贷款组", false, "代码问题", "交易级", "已修复"),
                new DailyIssueSlice("贷款组", false, "", "交易级", "新建"));

        Map<String, DailyReportRow> byGroup = new LinkedHashMap<>();
        Map<String, List<DailyIssueSlice>> grouped = new LinkedHashMap<>();
        for (DailyIssueSlice slice : slices) {
            String key = slice.groupName() + "|" + slice.sandbox();
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(slice);
        }
        for (Map.Entry<String, List<DailyIssueSlice>> entry : grouped.entrySet()) {
            DailyIssueSlice first = entry.getValue().get(0);
            DailyReportRow row = DailyReportRow.aggregate(first.groupName(), first.sandbox(), entry.getValue());
            byGroup.put(entry.getKey(), row);
        }

        DailyReportRow sandboxDeposit = byGroup.get("存款组|true");
        assertEquals(2, sandboxDeposit.totalCount());
        assertEquals(1, sandboxDeposit.fixedCount());
        assertEquals(1, sandboxDeposit.unresolvedCount());
        assertEquals(50.0, sandboxDeposit.fixRate());
        // 已修复(1) + 延后修复(0) + 修复待验证(0) / 2 = 50%
        assertEquals(50.0, sandboxDeposit.inspectionProgress());
        assertEquals(1L, sandboxDeposit.fixedByIssueType().get("代码问题").longValue());

        DailyReportRow prodLoan = byGroup.get("贷款组|false");
        assertEquals(2, prodLoan.totalCount());
        assertEquals(1, prodLoan.fixedCount());
        assertEquals(1, prodLoan.unresolvedCount());
        // 已修复(1) + 延后修复(0) + 修复待验证(0) / 2 = 50%
        assertEquals(50.0, prodLoan.resolutionProgress());
        // 排查进度使用状态口径，与问题分类是否为空无关
        assertEquals(50.0, prodLoan.inspectionProgress());
    }

    @Test
    void aggregateCountsOnlyTransactionLevelNoActionAsReasonableDifference() {
        DailyReportRow row = DailyReportRow.aggregate("公共组", false, List.of(
                new DailyIssueSlice("公共组", false, "合理差异", "交易级", "无需处理"),
                new DailyIssueSlice("公共组", false, "合理差异", "字段级", "无需处理"),
                new DailyIssueSlice("公共组", false, "合理差异", "交易级", "打开")));

        assertEquals(1L, row.reasonableDifferenceCount());
    }

    @Test
    void findDailySlicesByBatchReturnsJoinedRows() {
        insertIssueWithOccurrence(1L, "存款组", false, "代码问题", "已修复", "BATCH-A");
        insertIssueWithOccurrence(2L, "存款组", false, "数据差异", "打开", "BATCH-A");
        insertIssueWithOccurrence(3L, "贷款组", true, "代码问题", "新建", "BATCH-B");

        List<DailyIssueSlice> batchA = dao.findDailySlicesByBatch("BATCH-A");
        assertEquals(2, batchA.size());

        List<DailyIssueSlice> batchB = dao.findDailySlicesByBatch("BATCH-B");
        assertEquals(1, batchB.size());
    }

    @Test
    void findDailySlicesByBatchUsesCurrentIssueStatusAndType() {
        insertIssueWithOccurrence(10L, "存款组", false, "代码问题", "打开", "BATCH-A");
        jdbc.update("UPDATE dii_replay_issue SET issue_status=?, issue_type=? WHERE id=?",
                "已修复", "参数问题", 10L);

        List<DailyIssueSlice> batchA = dao.findDailySlicesByBatch("BATCH-A");

        assertEquals(1, batchA.size());
        assertEquals("已修复", batchA.get(0).lastStatus(),
                "出现批次只负责圈定问题范围，日报状态应读取问题清单当前值");
        assertEquals("参数问题", batchA.get(0).issueType(),
                "日报问题分类应读取问题清单当前值");
        DailyReportRow aggregated = DailyReportRow.aggregate("存款组", false, batchA);
        assertEquals(1L, aggregated.fixedByIssueType().get("参数问题"));
        assertEquals(100.0, aggregated.fixRate(),
                "上轮问题解决率应为当前已修复数除以该出现批次的问题总数");
    }

    @Test
    void findDailySlicesByBatchReturnsIssueLevelAndCountsReasonableDifference() {
        insertIssueWithOccurrence(11L, "公共组", false, "合理差异", "交易级", "无需处理", "BATCH-A");
        insertIssueWithOccurrence(12L, "公共组", false, "合理差异", "字段级", "无需处理", "BATCH-A");
        insertIssueWithOccurrence(13L, "公共组", false, "合理差异", "交易级", "打开", "BATCH-A");

        List<DailyIssueSlice> slices = dao.findDailySlicesByBatch("BATCH-A");

        assertEquals(List.of("交易级", "字段级", "交易级"),
                slices.stream().map(DailyIssueSlice::issueLevel).toList());
        assertEquals(1L, DailyReportRow.aggregate("公共组", false, slices).reasonableDifferenceCount());
    }

    @Test
    void generateWritesReportFileWithUpperAndLowerParts() throws Exception {
        insertIssueWithOccurrence(1L, "存款组", false, "代码问题", "已修复", "BATCH-PREV");
        insertIssueWithOccurrence(2L, "存款组", false, "数据差异", "打开", "BATCH-PREV");
        insertIssueWithOccurrence(3L, "贷款组", false, "配置问题", "延后修复", "BATCH-PREV");
        insertIssueWithOccurrence(4L, "存款组", true, "平台问题", "已修复", "BATCH-PREV");
        insertIssueWithOccurrence(5L, "存款组", false, "规则差异问题", "新建", "BATCH-CURR");
        insertIssueWithOccurrence(6L, "存款组", false, "规则差异问题", "打开", "BATCH-CURR");
        insertIssueWithOccurrence(7L, "贷款组", false, "代码问题", "新建", "BATCH-CURR");
        insertIssueWithOccurrence(8L, "存款组", true, "平台问题", "打开", "BATCH-CURR");

        // 构造上一份日报的下半区静态行，以及本次 Excel 的下半区静态行。
        ReplayIssueSummaryRow upperRow = new ReplayIssueSummaryRow(
                "BATCH-PREV", "存款组", 2828L, 7490L, 5747L, 257L, 257L, 602L, 0L, 0L, 2921L, 36.96, 15.45,
                ReplayIssueSummaryRow.Part.UPPER, null);
        ReplayIssueSummaryRow upperLoanRow = new ReplayIssueSummaryRow(
                "BATCH-PREV", "贷款组", 100L, 200L, 10L, 1L, 2L, 3L, 4L, 5L, 6L, 90.0, 80.0,
                ReplayIssueSummaryRow.Part.UPPER, null);
        ReplayIssueSummaryRow upperSandboxRow = new ReplayIssueSummaryRow(
                "BATCH-PREV", "沙箱-存款组", 50L, 60L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 70.0, 60.0,
                ReplayIssueSummaryRow.Part.UPPER, null);
        ReplayIssueSummaryRow lowerRow = new ReplayIssueSummaryRow(
                "BATCH-CURR", "存款组", 528L, 12345L, 100L, 150L, 200L, 300L, 400L, 500L, 600L, 95.5, 98.2,
                ReplayIssueSummaryRow.Part.LOWER, null);
        ReplayIssueSummaryRow lowerLoanRow = new ReplayIssueSummaryRow(
                "BATCH-CURR", "贷款组", 101L, 201L, 11L, 2L, 3L, 4L, 5L, 6L, 7L, 91.0, 81.0,
                ReplayIssueSummaryRow.Part.LOWER, null);
        ReplayIssueSummaryRow lowerSandboxRow = new ReplayIssueSummaryRow(
                "BATCH-CURR", "沙箱-存款组", 51L, 61L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 71.0, 61.0,
                ReplayIssueSummaryRow.Part.LOWER, null);
        ReplayIssueSummaryParser.ParsedSummary excelSummary = new ReplayIssueSummaryParser.ParsedSummary(
                List.of(upperRow, upperLoanRow, upperSandboxRow),
                List.of(lowerRow, lowerLoanRow, lowerSandboxRow), true);

        service.generateNext("BATCH-PREV", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                        List.of(upperRow, upperLoanRow, upperSandboxRow), true));

        Path target = service.generateNext("BATCH-CURR", LocalDateTime.now(), excelSummary);
        assertNotNull(target);
        assertTrue(target.toFile().exists(), () -> "报告未生成: " + target);

        // 用 XSSFWorkbook 读取生成的 .xlsx 验证上半部分+下半部分
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertNotNull(sheet);
            String allText = sheetToString(sheet);
            assertTrue(allText.contains("批次号：BATCH-PREV"), () -> allText);
            assertTrue(allText.contains("批次号：BATCH-CURR"), () -> allText);
            assertTrue(allText.contains("存款组"), () -> allText);
            assertTrue(allText.contains("代码问题"), () -> allText);
            assertTrue(allText.contains("延后修复"), () -> allText);

            assertFalse(allText.contains("是否沙箱"), () -> allText);
            assertTrue(allText.contains("沙箱-存款组"), () -> allText);
            assertTrue(allText.contains("交易核对分类统计"), () -> allText);
            assertTrue(allText.contains("已解决问题分类统计"), () -> allText);
            assertTrue(allText.contains("上一批次未解决问题分类统计"), () -> allText);
            assertFalse(allText.contains("CCBS失败明细"), () -> allText);

            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            assertTrue(upperHeader >= 0 && lowerHeader > upperHeader);
            assertTrue(hasMergedRegion(sheet, upperHeader, upperHeader, 4, 10));
            assertTrue(sheet.getNumMergedRegions() >= 10, "标题、纵向表头与父表头均应合并");

            int upperDeposit = findDataRow(sheet, upperHeader, lowerHeader, "存款组");
            int upperLoan = findDataRow(sheet, upperHeader, lowerHeader, "贷款组");
            int upperSandbox = findDataRow(sheet, upperHeader, lowerHeader, "沙箱-存款组");
            int lowerDeposit = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "存款组");
            int lowerLoan = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "贷款组");
            int lowerSandbox = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "沙箱-存款组");
            assertEquals(2.0, numericCell(sheet, upperDeposit, "问题总数"));
            assertEquals(1.0, numericCell(sheet, upperLoan, "问题总数"));
            assertEquals(1.0, numericCell(sheet, upperSandbox, "问题总数"));
            assertEquals(2.0, numericCell(sheet, lowerDeposit, "问题总数"));
            assertEquals(1.0, numericCell(sheet, lowerLoan, "问题总数"));
            assertEquals(1.0, numericCell(sheet, lowerSandbox, "问题总数"));
            assertEquals(1.0, numericCell(sheet, lowerDeposit, "上一批次未解决问题数量"));
            assertEquals(1.0, numericCell(sheet, lowerLoan, "上一批次未解决问题数量"));
            assertEquals(0.0, numericCell(sheet, lowerSandbox, "上一批次未解决问题数量"));

            int upperTotal = findDataRow(sheet, upperHeader, lowerHeader, "合计");
            int lowerTotal = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "合计");
            assertEquals(4.0, numericCell(sheet, upperTotal, "问题总数"));
            assertEquals(4.0, numericCell(sheet, lowerTotal, "问题总数"));

            assertFill(sheet.getRow(0).getCell(0), "C6E0B4");
            int lowerTitle = findRow(sheet, "批次号：BATCH-CURR（本批次）");
            assertFill(sheet.getRow(lowerTitle).getCell(0), "F4CCCC");
            int upperTypeParentCol = findColumn(sheet, upperHeader, "已解决问题分类统计");
            assertFill(sheet.getRow(upperHeader).getCell(upperTypeParentCol), "FFF2CC");
            int successRateCol = findColumn(sheet, upperHeader, "接口成功率");
            assertTrue(sheet.getRow(upperDeposit).getCell(successRateCol).getCellStyle().getDataFormatString().contains("%"));
            assertTrue(sheet.getColumnWidth(1) >= 12 * 256);
            assertTrue(sheet.getRow(upperDeposit).getCell(0).getCellStyle().getBorderBottom() != org.apache.poi.ss.usermodel.BorderStyle.NONE);
        }

        ReplayIssueSummaryRow nextLower = new ReplayIssueSummaryRow(
                "BATCH-NEXT", "存款组", 600L, 13000L, 110L, 160L, 210L, 310L, 410L, 510L, 610L,
                96.0, 99.0, ReplayIssueSummaryRow.Part.LOWER, null);
        Path nextTarget = service.generateNext("BATCH-NEXT", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(nextLower), true));
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(nextTarget.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertTrue(sheetToString(sheet).contains("批次号：BATCH-CURR（上一批次）"));
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int deposit = findDataRow(sheet, upperHeader, lowerHeader, "存款组");
            assertEquals(528.0, numericCell(sheet, deposit, "覆盖528接口"),
                    "下一份日报上半区应继承上一份日报的本批次静态指标");
        }
    }

    @Test
    void reportUsesAllBatchIssueTypesAndStatusBasedProgress() throws Exception {
        insertIssueWithOccurrence(101L, "存款组", false, "代码问题", "已修复", "BATCH-PREV");
        insertIssueWithOccurrence(102L, "存款组", false, "参数问题", "打开", "BATCH-PREV");
        insertIssueWithOccurrence(103L, "存款组", false, " ", "已修复", "BATCH-PREV");
        insertIssueWithOccurrence(104L, "存款组", false, "平台问题", "延后修复", "BATCH-PREV");
        insertIssueWithOccurrence(105L, "存款组", false, "规则差异问题", "修复待验证", "BATCH-PREV");
        insertIssueWithOccurrence(106L, "存款组", false, "数据差异", "打开", "BATCH-PREV");
        insertIssueWithOccurrence(107L, "存款组", false, "代码问题", "新建", "BATCH-CURR");
        ReplayIssueSummaryParser.ParsedSummary summary = new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("BATCH-PREV", "存款组", 111L)),
                List.of(summaryRow("BATCH-CURR", "存款组", 222L)), true);

        service.generateNext("BATCH-PREV", LocalDateTime.now(), new ReplayIssueSummaryParser.ParsedSummary(
                List.of(), List.of(summaryRow("BATCH-PREV", "存款组", 111L)), true));

        Path target = service.generateNext("BATCH-CURR", LocalDateTime.now(), summary);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int upperDeposit = findDataRow(sheet, upperHeader, lowerHeader, "存款组");
            int lowerDeposit = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "存款组");

            List<String> issueTypes = List.of("代码问题", "参数问题", "平台问题", "规则差异问题", "数据差异");
            for (String issueType : issueTypes) {
                assertTrue(findColumn(sheet, upperHeader, issueType) >= 0, "缺少问题分类列: " + issueType);
            }
            int otherCol = findColumn(sheet, upperHeader, "其他问题");
            assertTrue(otherCol >= 0, "空问题分类应归入其他问题");
            for (String issueType : issueTypes) {
                assertTrue(otherCol > findColumn(sheet, upperHeader, issueType), "其他问题必须位于最后");
            }
            assertEquals(1.0, numericCell(sheet, upperDeposit, "代码问题"));
            assertEquals(1.0, numericCell(sheet, upperDeposit, "其他问题"));
            assertEquals(0.0, numericCell(sheet, upperDeposit, "参数问题"));
            assertEquals(0.6667, numericCell(sheet, upperDeposit, "问题排查进度"), 0.0001);

            assertEquals(4.0, numericCell(sheet, lowerDeposit, "上一批次未解决问题数量"));
            assertEquals(2.0, numericCell(sheet, lowerDeposit, "打开"));
            assertEquals(1.0, numericCell(sheet, lowerDeposit, "延后修复"));
            assertEquals(1.0, numericCell(sheet, lowerDeposit, "修复待验证"));
            assertEquals(0.3333, numericCell(sheet, lowerDeposit, "上轮问题解决率"), 0.0001);
            assertEquals(0.6667, numericCell(sheet, lowerDeposit, "问题解决进度"), 0.0001);
        }
    }

    @Test
    void firstImportLeavesWholePreviousSectionEmpty() throws Exception {
        ReplayIssueSummaryParser.ParsedSummary summary = new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("BATCH-PREV", "存款组", 111L)),
                List.of(summaryRow("BATCH-CURR", "贷款组", 222L)), true);

        Path target = service.generateNext("BATCH-CURR", LocalDateTime.now(), summary);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertTrue(sheetToString(sheet).contains("批次号：（上一批次）"));
            assertTrue(sheetToString(sheet).contains("批次号：BATCH-CURR（本批次）"));
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            assertFalse(hasDomainOrTotalRows(sheet, upperHeader, lowerHeader),
                    "首次导入上半区只保留表头，不得写入 Excel 上半区领域行或合计行");
            assertEquals(222.0, numericCell(sheet,
                    findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "贷款组"), "覆盖528接口"));
        }
    }

    @Test
    void secondImportShowsReasonableDifferenceForPreviousAndCurrentBatchIndependently() throws Exception {
        insertIssueWithOccurrence(21L, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-ONE");
        insertIssueWithOccurrence(22L, "存款组", false, "合理差异", "字段级", "无需处理", "BATCH-ONE");
        insertIssueWithOccurrence(23L, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-TWO");
        insertIssueWithOccurrence(24L, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-TWO");
        service.generateNext("BATCH-ONE", LocalDateTime.now(), new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("IGNORED", "存款组", 100L)),
                List.of(summaryRow("BATCH-ONE", "存款组", 201L)), true));

        Path target = service.generateNext("BATCH-TWO", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                        List.of(summaryRow("BATCH-TWO", "存款组", 302L)), true));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int upperRow = findDataRow(sheet, upperHeader, lowerHeader, "存款组");
            int lowerRow = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "存款组");
            int upperTotal = findDataRow(sheet, upperHeader, lowerHeader, "合计");
            int lowerTotal = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "合计");

            assertEquals(findColumn(sheet, upperHeader, "二者均成功") + 1,
                    findColumn(sheet, upperHeader, "合理差异"));
            assertEquals(findColumn(sheet, upperHeader, "合理差异") + 1,
                    findColumn(sheet, upperHeader, "响应码忽略"));
            assertEquals(1.0, numericCell(sheet, upperRow, "合理差异"));
            assertEquals(2.0, numericCell(sheet, lowerRow, "合理差异"));
            assertEquals(1.0, numericCell(sheet, upperTotal, "合理差异"));
            assertEquals(2.0, numericCell(sheet, lowerTotal, "合理差异"));
        }
    }

    @Test
    void recalculatesRowAndTotalRatesWithReasonableDifference() throws Exception {
        insertIssueWithOccurrence(31L, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-CURR");
        insertIssueWithOccurrence(32L, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-CURR");
        ReplayIssueSummaryRow current = rateFormulaRow("BATCH-CURR", "存款组", 100L,
                10L, 70L, 10L, 77.77777777777777);
        Path target = service.generateNext("BATCH-CURR", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(current), true,
                        ReplayIssueSummaryParser.SummaryRateTotals.EMPTY,
                        new ReplayIssueSummaryParser.SummaryRateTotals(12.34, 77.77777777777777)));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int dataRow = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "存款组");
            int totalRow = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "合计");
            double expectedSuccessRate = 80.0 / 88.0;
            double expectedMatchRate = 70.0 / 88.0;

            assertEquals(expectedSuccessRate, numericCell(sheet, dataRow, "接口成功率"), 0.0000001);
            assertEquals(expectedMatchRate, numericCell(sheet, dataRow, "比对通过率"), 0.0000001);
            assertEquals(expectedSuccessRate, numericCell(sheet, totalRow, "接口成功率"), 0.0000001);
            assertEquals(expectedMatchRate, numericCell(sheet, totalRow, "比对通过率"), 0.0000001);
        }
    }

    @Test
    void rateRecalculationReturnsZeroWhenAdjustedDenominatorIsNotPositive() throws Exception {
        insertIssueWithOccurrence(41L, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-CURR");
        insertIssueWithOccurrence(42L, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-CURR");
        ReplayIssueSummaryRow current = rateFormulaRow("BATCH-CURR", "存款组", 10L,
                1L, 5L, 9L, 100.0);

        Path target = service.generateNext("BATCH-CURR", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(current), true));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int dataRow = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "存款组");
            assertEquals(0.0, numericCell(sheet, dataRow, "接口成功率"));
            assertEquals(0.0, numericCell(sheet, dataRow, "比对通过率"));
        }
    }

    @Test
    void recalculatedRatesRemainStableAcrossReportRollForward() throws Exception {
        for (long id = 51L; id <= 52L; id++) {
            insertIssueWithOccurrence(id, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-ONE");
        }
        for (long id = 53L; id <= 54L; id++) {
            insertIssueWithOccurrence(id, "存款组", false, "合理差异", "交易级", "无需处理", "BATCH-TWO");
        }
        ReplayIssueSummaryRow first = rateFormulaRow("BATCH-ONE", "存款组", 100L,
                10L, 70L, 10L, 77.77777777777777);
        ReplayIssueSummaryRow second = rateFormulaRow("BATCH-TWO", "存款组", 100L,
                10L, 70L, 10L, 77.77777777777777);
        service.generateNext("BATCH-ONE", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(first), true));
        Path secondReport = service.generateNext("BATCH-TWO", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(second), true));
        Path thirdReport = service.generateNext("BATCH-THREE", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                        List.of(rateFormulaRow("BATCH-THREE", "存款组", 100L,
                                10L, 70L, 10L, 77.77777777777777)), true));

        double expected = 70.0 / 88.0;
        assertUpperRate(secondReport, "比对通过率", expected);
        assertUpperRate(thirdReport, "比对通过率", expected);
    }

    @Test
    void firstImportIgnoresUpperSourceTotalRatesAndUsesLowerSourceTotals() throws Exception {
        ReplayIssueSummaryParser.ParsedSummary summary = new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("BATCH-PREV", "存款组", 111L)),
                List.of(summaryRow("BATCH-CURR", "存款组", 222L)), true,
                new ReplayIssueSummaryParser.SummaryRateTotals(63.05, 39.77),
                new ReplayIssueSummaryParser.SummaryRateTotals(69.54, 45.88));

        Path target = service.generateNext("BATCH-CURR", LocalDateTime.now(), summary);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int lowerTotal = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "合计");
            assertFalse(hasDomainOrTotalRows(sheet, upperHeader, lowerHeader));
            double recalculatedRate = 10.0 / (2220.0 - 7.0);
            assertEquals(recalculatedRate, numericCell(sheet, lowerTotal, "接口成功率"), 0.0000001);
            assertEquals(recalculatedRate, numericCell(sheet, lowerTotal, "比对通过率"), 0.0000001);
        }
    }

    @Test
    void laterImportsRollForwardOnlyPreviousLowerSection() throws Exception {
        service.generateNext("BATCH-ONE", LocalDateTime.now(), new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("BATCH-ZERO", "存款组", 100L)),
                List.of(summaryRow("BATCH-ONE", "存款组", 201L)), true));
        Path second = service.generateNext("BATCH-TWO", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(
                        List.of(summaryRow("IGNORED-UPPER", "存款组", 999L)),
                        List.of(summaryRow("BATCH-TWO", "存款组", 302L)), true));
        Path third = service.generateNext("BATCH-THREE", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(
                        List.of(summaryRow("IGNORED-AGAIN", "存款组", 888L)),
                        List.of(summaryRow("BATCH-THREE", "存款组", 403L)), true));

        assertUpperStaticMetric(second, "BATCH-ONE", 201.0);
        assertUpperStaticMetric(third, "BATCH-TWO", 302.0);
    }

    @Test
    void rptAndDzReportsRollForwardOnlyWithinTheirOwnFamily() throws Exception {
        Path rpt = service.generateNext("RPT20260819-100000-0001", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(
                        List.of(summaryRow("RPT20260818-100000-0001", "存款组", 100L)),
                        List.of(summaryRow("RPT20260819-100000-0001", "存款组", 201L)), true));
        Path dz = service.generateNext("DZ20260819-110000-0001", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(
                        List.of(summaryRow("DZ20260818-110000-0001", "存款组", 300L)),
                        List.of(summaryRow("DZ20260819-110000-0001", "存款组", 401L)), true));
        Files.setLastModifiedTime(rpt, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(dz, FileTime.fromMillis(2_000));

        Path rptNext = service.generateNext("RPT20260820-100000-0001", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                        List.of(summaryRow("RPT20260820-100000-0001", "存款组", 502L)), true));
        Path dzNext = service.generateNext("DZ20260820-110000-0001", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                        List.of(summaryRow("DZ20260820-110000-0001", "存款组", 602L)), true));

        assertUpperStaticMetric(rptNext, "RPT20260819-100000-0001", 201.0);
        assertUpperStaticMetric(dzNext, "DZ20260819-110000-0001", 401.0);
    }

    @Test
    void firstDzReportLeavesUpperSectionEmptyEvenWhenRptHistoryExists() throws Exception {
        service.generateNext("RPT20260819-100000-0001", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(
                        List.of(summaryRow("RPT20260818-100000-0001", "存款组", 100L)),
                        List.of(summaryRow("RPT20260819-100000-0001", "存款组", 201L)), true));

        Path firstDz = service.generateNext("DZ20260820-110000-0001", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(
                        List.of(summaryRow("DZ20260819-110000-0001", "存款组", 301L)),
                        List.of(summaryRow("DZ20260820-110000-0001", "存款组", 402L)), true));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(firstDz.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            assertTrue(sheetToString(sheet).contains("批次号：（上一批次）"));
            assertFalse(hasDomainOrTotalRows(sheet, upperHeader, lowerHeader));
            assertEquals(402.0, numericCell(sheet,
                    findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "存款组"), "覆盖528接口"));
        }
    }

    @Test
    void laterImportInheritsPreviousLowerSourceTotalRates() throws Exception {
        service.generateNext("BATCH-ONE", LocalDateTime.now(), new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("BATCH-ZERO", "存款组", 100L)),
                List.of(summaryRow("BATCH-ONE", "存款组", 201L)), true,
                new ReplayIssueSummaryParser.SummaryRateTotals(63.05, 39.77),
                new ReplayIssueSummaryParser.SummaryRateTotals(69.54, 45.88)));

        Path second = service.generateNext("BATCH-TWO", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                        List.of(summaryRow("BATCH-TWO", "存款组", 302L)), true,
                        ReplayIssueSummaryParser.SummaryRateTotals.EMPTY,
                        new ReplayIssueSummaryParser.SummaryRateTotals(72.34, 52.16)));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(second.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int upperTotal = findDataRow(sheet, upperHeader, lowerHeader, "合计");
            int lowerTotal = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "合计");
            double upperRate = 10.0 / (2010.0 - 7.0);
            double lowerRate = 10.0 / (3020.0 - 7.0);
            assertEquals(upperRate, numericCell(sheet, upperTotal, "接口成功率"), 0.0000001);
            assertEquals(upperRate, numericCell(sheet, upperTotal, "比对通过率"), 0.0000001);
            assertEquals(lowerRate, numericCell(sheet, lowerTotal, "接口成功率"), 0.0000001);
            assertEquals(lowerRate, numericCell(sheet, lowerTotal, "比对通过率"), 0.0000001);
        }
    }

    @Test
    void missingSourceTotalMatchRateFallsBackToSummedRowInference() throws Exception {
        List<ReplayIssueSummaryRow> upperRows = List.of(
                rateFormulaRow("BATCH-PREV", "存款组", 100L, 10L, 50L, 0L, 40.0),
                rateFormulaRow("BATCH-PREV", "贷款组", 300L, 30L, 150L, 0L, 60.0));
        List<ReplayIssueSummaryRow> lowerRows = List.of(
                rateFormulaRow("BATCH-CURR", "存款组", 100L, 10L, 50L, 0L, 40.0),
                rateFormulaRow("BATCH-CURR", "贷款组", 300L, 30L, 150L, 0L, 60.0));
        ReplayIssueSummaryParser.ParsedSummary summary = new ReplayIssueSummaryParser.ParsedSummary(
                upperRows, lowerRows, true,
                new ReplayIssueSummaryParser.SummaryRateTotals(63.05, null),
                ReplayIssueSummaryParser.SummaryRateTotals.EMPTY);

        service.generateNext("BATCH-PREV", LocalDateTime.now(),
                new ReplayIssueSummaryParser.ParsedSummary(List.of(), upperRows, true,
                        ReplayIssueSummaryParser.SummaryRateTotals.EMPTY,
                        ReplayIssueSummaryParser.SummaryRateTotals.EMPTY));

        Path target = service.generateNext("BATCH-CURR", LocalDateTime.now(), summary);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(target.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int upperTotal = findDataRow(sheet, upperHeader, lowerHeader, "合计");
            int lowerTotal = findDataRow(sheet, lowerHeader, sheet.getLastRowNum() + 1, "合计");
            assertEquals(0.6000, numericCell(sheet, upperTotal, "接口成功率"), 0.0001,
                    "接口成功率必须按合计计数重算，不能读取本次 Excel 上半区");
            assertEquals(0.5500, numericCell(sheet, upperTotal, "比对通过率"), 0.0001,
                    "缺失的合计比对通过率应汇总各领域反算数量");
            assertEquals(0.6000, numericCell(sheet, lowerTotal, "接口成功率"), 0.0001);
            assertEquals(0.5500, numericCell(sheet, lowerTotal, "比对通过率"), 0.0001);
        }
    }

    @Test
    void laterImportRejectsHistoricalReportWithoutLowerSection() throws Exception {
        Path malformed = reportDir.resolve("old-report.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.OutputStream output = Files.newOutputStream(malformed)) {
            workbook.createSheet("汇总信息").createRow(0).createCell(0).setCellValue("旧日报无下半区");
            workbook.write(output);
        }
        ReplayIssueSummaryParser.ParsedSummary summary = new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("BATCH-PREV", "存款组", 111L)),
                List.of(summaryRow("BATCH-CURR", "贷款组", 222L)), true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.generateNext("BATCH-CURR", LocalDateTime.now(), summary));

        assertTrue(error.getMessage().contains("无法读取上一份日报的本批次区域"), error::getMessage);
        assertFalse(Files.exists(reportDir.resolve("BATCH-CURR日报.xlsx")));
    }

    @Test
    void rejectsCurrentExcelWithoutLowerSection() {
        ReplayIssueSummaryParser.ParsedSummary summary = new ReplayIssueSummaryParser.ParsedSummary(
                List.of(summaryRow("BATCH-PREV", "存款组", 111L)), List.of(), true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.generateNext("BATCH-PREV", LocalDateTime.now(), summary));

        assertTrue(error.getMessage().contains("本批次区域"), error::getMessage);
    }

    @Test
    void locateReportReturnsExpectedFile() {
        Path path = service.locateReport("SOME_BATCH");
        assertNotNull(path);
        assertTrue(path.toString().endsWith("SOME_BATCH日报.xlsx"));
    }

    private void insertIssueWithOccurrence(long issueId, String groupName, boolean sandbox,
                                           String issueType, String lastStatus, String batchName) {
        insertIssueWithOccurrence(issueId, groupName, sandbox, issueType, "交易级", lastStatus, batchName);
    }

    private void insertIssueWithOccurrence(long issueId, String groupName, boolean sandbox,
                                           String issueType, String issueLevel, String lastStatus, String batchName) {
        jdbc.update("INSERT INTO dii_replay_issue(id,source_sheet,group_name,is_sandbox,row_order,domain,issue_type,issue_level,issue_status,imported_at,issue_id,issue_key) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                issueId, (sandbox ? "沙箱-" : "") + groupName, groupName, sandbox, 1,
                groupName, issueType, issueLevel, lastStatus, LocalDateTime.now(),
                "issue-" + issueId, "key-" + issueId);
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,last_status,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,?)",
                issueId, "key-" + issueId, batchName, LocalDateTime.now(), LocalDateTime.now(),
                lastStatus, LocalDateTime.now(), LocalDateTime.now());
    }

    private static ReplayIssueSummaryRow summaryRow(String batch, String domain, long covered) {
        return new ReplayIssueSummaryRow(batch, domain, covered, covered * 10,
                1L, 2L, 3L, 4L, 5L, 6L, 7L, 90.0, 80.0,
                ReplayIssueSummaryRow.Part.LOWER, null);
    }

    private static ReplayIssueSummaryRow rateFormulaRow(String batch, String domain, long sent,
                                                         long bothFailSame, long bothSuccess,
                                                         long codeIgnored, double originalMatchRate) {
        return new ReplayIssueSummaryRow(batch, domain, 1L, sent,
                0L, null, 0L, bothFailSame, 0L, bothSuccess, codeIgnored,
                0.0, originalMatchRate, ReplayIssueSummaryRow.Part.LOWER, null);
    }

    private static void assertUpperRate(Path report, String header, double expected) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(report.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int row = findDataRow(sheet, upperHeader, lowerHeader, "存款组");
            assertEquals(expected, numericCell(sheet, row, header), 0.0000001);
        }
    }

    private static void assertUpperStaticMetric(Path report, String batch, double covered) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(report.toFile())) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertTrue(sheetToString(sheet).contains("批次号：" + batch + "（上一批次）"));
            int upperHeader = findRow(sheet, "交易核对分类统计");
            int lowerHeader = findLastRow(sheet, "交易核对分类统计");
            int row = findDataRow(sheet, upperHeader, lowerHeader, "存款组");
            assertEquals(covered, numericCell(sheet, row, "覆盖528接口"));
        }
    }

    private static String sheetToString(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        for (Row row : sheet) {
            for (int i = 0; i < row.getLastCellNum(); i++) {
                sb.append(row.getCell(i) == null ? "" : row.getCell(i).toString()).append("\t");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static int findRow(Sheet sheet, String value) {
        for (Row row : sheet) for (Cell cell : row) if (value.equals(cell.toString())) return row.getRowNum();
        return -1;
    }

    private static int findLastRow(Sheet sheet, String value) {
        int found = -1;
        for (Row row : sheet) for (Cell cell : row) if (value.equals(cell.toString())) found = row.getRowNum();
        return found;
    }

    private static int findColumn(Sheet sheet, int parentHeaderRow, String value) {
        for (int r = parentHeaderRow; r <= parentHeaderRow + 1; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) if (value.equals(cell.toString())) return cell.getColumnIndex();
        }
        return -1;
    }

    private static int findDataRow(Sheet sheet, int headerRow, int endExclusive, String domain) {
        for (int r = headerRow + 2; r < endExclusive; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getCell(1) != null && domain.equals(row.getCell(1).toString())) return r;
        }
        throw new AssertionError("未找到领域行: " + domain);
    }

    private static boolean hasDomainOrTotalRows(Sheet sheet, int headerRow, int endExclusive) {
        for (int r = headerRow + 2; r < endExclusive; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getCell(1) != null && !row.getCell(1).toString().isBlank()) return true;
        }
        return false;
    }

    private static double numericCell(Sheet sheet, int row, String header) {
        int upperHeader = row < findLastRow(sheet, "交易核对分类统计")
                ? findRow(sheet, "交易核对分类统计") : findLastRow(sheet, "交易核对分类统计");
        int col = findColumn(sheet, upperHeader, header);
        return sheet.getRow(row).getCell(col).getNumericCellValue();
    }

    private static boolean hasMergedRegion(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        CellRangeAddress wanted = new CellRangeAddress(firstRow, lastRow, firstCol, lastCol);
        for (CellRangeAddress actual : sheet.getMergedRegions()) if (wanted.equals(actual)) return true;
        return false;
    }

    private static void assertFill(Cell cell, String expectedRgb) {
        XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
        assertEquals(FillPatternType.SOLID_FOREGROUND, style.getFillPattern());
        XSSFColor color = style.getFillForegroundXSSFColor();
        assertNotNull(color);
        assertEquals(expectedRgb, color.getARGBHex().substring(2));
    }
}
