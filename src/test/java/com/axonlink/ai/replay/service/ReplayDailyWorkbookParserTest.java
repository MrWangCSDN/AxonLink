package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.PreviousUnresolved;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDailyWorkbookParserTest {

    private final ReplayDailyWorkbookParser parser = new ReplayDailyWorkbookParser();

    @Test
    void parsesLowerSummaryAndInterfaceDetailAndTotal() throws Exception {
        ReplayDailyWorkbookData data = parser.parse(ReplayIssueTestFixtures.dailyWorkbook(), ReplayIssueImportMode.QUERY);

        assertEquals("RPT20260904-094201-5355", data.batchNo());
        assertEquals(4, data.summaries().size());
        assertTrue(data.summaries().stream().noneMatch(row -> "合计".equals(row.domain())));
        assertEquals(83L, data.summaries().get(3).issueTotal());
        assertEquals(ReplayDailyRowType.DETAIL, data.comparisons().get(0).rowType());
        assertEquals(ReplayDailyRowType.TOTAL, data.comparisons().get(1).rowType());
        assertEquals(700L, data.comparisons().get(1).sentTransactionCount());
        assertEquals("RPT20260904-094201-5355", data.comparisons().get(1).batchNo());
        assertTrue(data.comparisons().get(0).rawJson().contains("6208"));
    }

    @Test
    void normalizesAllDailyRowsForDzImports() throws Exception {
        ReplayDailyWorkbookData data = parser.parse(ReplayIssueTestFixtures.dailyWorkbook(), ReplayIssueImportMode.DZ);

        assertEquals("DZ20260904-094201-5355", data.batchNo());
        assertTrue(data.summaries().stream().allMatch(row -> row.batchNo().startsWith("DZ")));
        assertTrue(data.comparisons().stream().allMatch(row -> row.batchNo().startsWith("DZ")));
    }

    @Test
    void rejectsMissingRequiredDailySheet() {
        MockMultipartFile file = mutate(workbook -> workbook.removeSheetAt(
                workbook.getSheetIndex("接口比对明细")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("接口比对明细"));
    }

    @Test
    void optionalDailyParsingReturnsEmptyWhenBothDailyDetailSheetsAreMissing() throws Exception {
        MockMultipartFile file = mutate(workbook -> {
            workbook.removeSheetAt(workbook.getSheetIndex("接口比对明细"));
            workbook.removeSheetAt(workbook.getSheetIndex("回放交易覆盖情况"));
        });

        assertTrue(parser.parseIfDailySheetsPresent(file, ReplayIssueImportMode.QUERY).isEmpty());
    }

    @Test
    void optionalDailyParsingRejectsPartialDailyDetailSheets() {
        MockMultipartFile file = mutate(workbook -> workbook.removeSheetAt(
                workbook.getSheetIndex("接口比对明细")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parseIfDailySheetsPresent(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("接口比对明细"));
        assertTrue(exception.getMessage().contains("回放交易覆盖情况"));
    }

    @Test
    void optionalDailyParsingRequiresSummaryWhenBothDetailSheetsExist() {
        MockMultipartFile file = mutate(workbook -> workbook.removeSheetAt(
                workbook.getSheetIndex("汇总信息")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parseIfDailySheetsPresent(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("汇总信息"));
    }

    @Test
    void optionalDailyParsingParsesCompleteDailyWorkbook() throws Exception {
        ReplayDailyWorkbookData data = parser.parseIfDailySheetsPresent(
                ReplayIssueTestFixtures.dailyWorkbook(), ReplayIssueImportMode.QUERY).orElseThrow();

        assertEquals("RPT20260904-094201-5355", data.batchNo());
    }

    @Test
    void rejectsMalformedSummaryCountWithSheetRowAndField() {
        MockMultipartFile file = mutate(workbook -> workbook.getSheet("汇总信息")
                .getRow(10).getCell(3).setCellValue("8千"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("汇总信息"));
        assertTrue(exception.getMessage().contains("11"));
        assertTrue(exception.getMessage().contains("发送交易量"));
    }

    @Test
    void rejectsInterfaceBatchDifferentFromSummaryBatch() {
        MockMultipartFile file = mutate(workbook -> workbook.getSheet("接口比对明细")
                .getRow(1).getCell(0).setCellValue("RPT-DIFFERENT"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("接口比对明细"));
        assertTrue(exception.getMessage().contains("批次"));
    }

    @Test
    void rejectsWorkbookWithoutLowerSummaryData() {
        MockMultipartFile file = mutate(workbook -> {
            var sheet = workbook.getSheet("汇总信息");
            for (int row = 7; row <= sheet.getLastRowNum(); row++) {
                sheet.removeRow(sheet.getRow(row));
            }
        });

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("汇总信息"));
        assertTrue(exception.getMessage().contains("下半部分"));
    }

    @Test
    void parsesCoverageSummaryTotalAndTransactionDetail() throws Exception {
        ReplayDailyWorkbookData data = parser.parse(ReplayIssueTestFixtures.dailyWorkbook(), ReplayIssueImportMode.QUERY);

        assertEquals(2, data.coverageSummaries().size());
        assertEquals(ReplayDailyRowType.DOMAIN_DETAIL, data.coverageSummaries().get(0).rowType());
        assertEquals(ReplayDailyRowType.DOMAIN_TOTAL, data.coverageSummaries().get(1).rowType());
        assertEquals(714L, data.coverageSummaries().get(1).fullTransactionCount());
        assertEquals(1, data.coverageDetails().size());
        assertEquals("0126", data.coverageDetails().get(0).transactionCode());
        assertEquals(LocalDate.of(2026, 8, 2), data.coverageDetails().get(0).latestTransactionDate());
        assertEquals("RPT20260904-094201-5355", data.coverageDetails().get(0).batchNo());
    }

    @Test
    void parsesScreenshotCoverageLayoutWithDomainAndGroupSummaries() throws Exception {
        ReplayDailyWorkbookData data = parser.parse(screenshotCoverageWorkbook(), ReplayIssueImportMode.QUERY);

        assertEquals(List.of("公共组", "合计", "公共组", "合计"), data.coverageSummaries().stream()
                .map(ReplayCoverageSummaryRow::businessDomain)
                .toList());
        assertEquals(List.of("DOMAIN_DETAIL", "DOMAIN_TOTAL", "GROUP_DETAIL", "GROUP_TOTAL"),
                data.coverageSummaries().stream().map(row -> row.rowType().name()).toList());
        assertEquals(List.of(76L, 714L, 196L, 714L), data.coverageSummaries().stream()
                .map(ReplayCoverageSummaryRow::fullTransactionCount)
                .toList());
        assertEquals(List.of("0126"), data.coverageDetails().stream()
                .map(ReplayCoverageDetailRow::transactionCode)
                .toList());
    }

    @Test
    void acceptsLastTransactionDateAsCoverageDateHeader() throws Exception {
        MockMultipartFile file = mutate(screenshotCoverageWorkbook(), workbook -> workbook
                .getSheet("回放交易覆盖情况").getRow(9).getCell(6).setCellValue("最后交易日期"));

        ReplayDailyWorkbookData data = parser.parse(file, ReplayIssueImportMode.QUERY);

        assertEquals(LocalDate.of(2026, 8, 2), data.coverageDetails().get(0).latestTransactionDate());
    }

    @Test
    void acceptsRelatedSCodeAsCoverageRelatedCodeHeader() throws Exception {
        MockMultipartFile file = mutate(screenshotCoverageWorkbook(), workbook -> workbook
                .getSheet("回放交易覆盖情况").getRow(9).getCell(4).setCellValue("关联S码"));

        ReplayDailyWorkbookData data = parser.parse(file, ReplayIssueImportMode.QUERY);

        assertEquals("S120032322", data.coverageDetails().get(0).relatedCode());
    }

    @Test
    void normalizesCoverageRowsForDzImports() throws Exception {
        ReplayDailyWorkbookData data = parser.parse(ReplayIssueTestFixtures.dailyWorkbook(), ReplayIssueImportMode.DZ);

        assertTrue(data.coverageSummaries().stream().allMatch(row -> row.batchNo().startsWith("DZ")));
        assertTrue(data.coverageDetails().stream().allMatch(row -> row.batchNo().startsWith("DZ")));
    }

    @Test
    void rejectsCoverageSheetWithoutDetailHeader() {
        MockMultipartFile file = mutate(workbook -> workbook.getSheet("回放交易覆盖情况")
                .getRow(5).getCell(0).setCellValue("旧交易码"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("回放交易覆盖情况"));
        assertTrue(exception.getMessage().contains("表头"));
    }

    @Test
    void rejectsIllegalCoverageDateWithSheetRowAndField() {
        MockMultipartFile file = mutate(workbook -> workbook.getSheet("回放交易覆盖情况")
                .getRow(6).getCell(6).setCellValue("20261340"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("回放交易覆盖情况"));
        assertTrue(exception.getMessage().contains("7"));
        assertTrue(exception.getMessage().contains("最近交易日期"));
    }

    @Test
    void rejectsIllegalCoverageCountAndRate() {
        MockMultipartFile badCount = mutate(workbook -> workbook.getSheet("回放交易覆盖情况")
                .getRow(1).getCell(1).setCellValue("一百"));
        MockMultipartFile badRate = mutate(workbook -> workbook.getSheet("回放交易覆盖情况")
                .getRow(2).getCell(7).setCellValue("五成"));

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(badCount, ReplayIssueImportMode.QUERY));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(badRate, ReplayIssueImportMode.QUERY));
    }

    @Test
    void parsesCoverageLayoutWrittenByReportWriter() throws Exception {
        ReplayDailyWorkbookData data = parser.parse(generatedWorkbook(), ReplayIssueImportMode.QUERY);

        assertEquals(List.of("公共组", "合计"), data.coverageSummaries().stream()
                .map(ReplayCoverageSummaryRow::businessDomain)
                .toList());
        assertEquals(List.of("0126"), data.coverageDetails().stream()
                .map(ReplayCoverageDetailRow::transactionCode)
                .toList());
    }

    @Test
    void rejectsMalformedCoverageSummaryCountInWrittenLayout() {
        MockMultipartFile file = mutate(generatedWorkbook(), workbook -> workbook
                .getSheet("回放交易覆盖情况").getRow(2).getCell(1).setCellValue("一百"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file, ReplayIssueImportMode.QUERY));

        assertTrue(exception.getMessage().contains("回放交易覆盖情况"));
        assertTrue(exception.getMessage().contains("3"));
        assertTrue(exception.getMessage().contains("全量清单交易数"));
        assertTrue(exception.getMessage().contains("一百"));
    }

    private static MockMultipartFile mutate(Consumer<Workbook> mutation) {
        return mutate(ReplayIssueTestFixtures.dailyWorkbook(), mutation);
    }

    private static MockMultipartFile mutate(MockMultipartFile original, Consumer<Workbook> mutation) {
        try (Workbook workbook = new XSSFWorkbook(original.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            mutation.accept(workbook);
            workbook.write(output);
            return new MockMultipartFile("file", "replay-daily.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static MockMultipartFile generatedWorkbook() {
        byte[] bytes = new ReplayDailyReportWorkbookWriter().write(generatedSummary(),
                generatedComparisons(), generatedCoverageSummaries(), generatedCoverageDetails());
        return new MockMultipartFile("file", "generated-replay-daily.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static MockMultipartFile screenshotCoverageWorkbook() {
        return mutate(workbook -> {
            int sheetIndex = workbook.getSheetIndex("回放交易覆盖情况");
            workbook.removeSheetAt(sheetIndex);
            Sheet sheet = workbook.createSheet("回放交易覆盖情况");
            workbook.setSheetOrder("回放交易覆盖情况", sheetIndex);

            sheet.createRow(0).createCell(0).setCellValue("按业务领域汇总");
            writeHeader(sheet.createRow(1), "业务领域", "全量清单交易数", "本次已发送", "本次未发送",
                    "不回放", "近期无交易", "待分析", "覆盖率");
            writeCoverageSummary(sheet.createRow(2), "公共组", 76, 69, 7, 1, 6, 0, "90.79%");
            writeCoverageSummary(sheet.createRow(3), "合计", 714, 504, 210, 118, 92, 0, "70.59%");

            sheet.createRow(4).createCell(0).setCellValue("按大组汇总");
            writeHeader(sheet.createRow(5), "大组", "全量清单交易数", "本次已发送", "本次未发送",
                    "不回放", "近期无交易", "待分析", "覆盖率");
            writeCoverageSummary(sheet.createRow(6), "公共组", 196, 138, 58, 21, 37, 0, "70.41%");
            writeCoverageSummary(sheet.createRow(7), "合计", 714, 504, 210, 118, 92, 0, "70.59%");

            sheet.createRow(8).createCell(0).setCellValue("交易明细");
            writeHeader(sheet.createRow(9), "交易码", "交易描述", "业务领域", "S码", "关联码", "是否需要回放",
                    "最近交易日期", "本次发送交易量", "覆盖状态", "未发送原因", "开发负责人", "行内负责人");
            Row detail = sheet.createRow(10);
            String[] values = {"0126", "客户号账号查询", "公共组", "S120032322", "S120032322", "是",
                    "20260802", "100", "已发送", "", "许威", "常硕"};
            for (int index = 0; index < values.length; index++) {
                detail.createCell(index).setCellValue(values[index]);
            }
        });
    }

    private static void writeHeader(Row row, String... headers) {
        for (int index = 0; index < headers.length; index++) {
            row.createCell(index).setCellValue(headers[index]);
        }
    }

    private static void writeCoverageSummary(Row row, String name, long full, long sent, long unsent,
                                             long excluded, long recent, long pending, String rate) {
        row.createCell(0).setCellValue(name);
        long[] values = {full, sent, unsent, excluded, recent, pending};
        for (int index = 0; index < values.length; index++) {
            row.createCell(index + 1).setCellValue(values[index]);
        }
        row.createCell(7).setCellValue(rate);
    }

    private static CalculatedReport generatedSummary() {
        ReplayDailySummaryCalculatedRow previous = calculatedRow("RPT20260903-094201-5355", "公共组", 1);
        ReplayDailySummaryCalculatedRow previousTotal = calculatedRow("RPT20260903-094201-5355", "合计", 10);
        ReplayDailySummaryCalculatedRow current = calculatedRow("RPT20260904-094201-5355", "公共组", 2);
        ReplayDailySummaryCalculatedRow currentTotal = calculatedRow("RPT20260904-094201-5355", "合计", 20);
        PreviousUnresolved unresolved = new PreviousUnresolved(1, 2, 3, 4, 5, 15, new BigDecimal("0.5"));
        return new CalculatedReport(List.of(previous), previousTotal, List.of(current), currentTotal, unresolved);
    }

    private static ReplayDailySummaryCalculatedRow calculatedRow(String batch, String domain, long seed) {
        return new ReplayDailySummaryCalculatedRow(batch, domain, seed, seed + 1,
                seed + 2, seed + 3, seed + 4, seed + 5, seed + 6, seed + 7, seed + 8,
                seed + 9, new BigDecimal("0.8"), new BigDecimal("0.9"), seed + 10,
                seed + 10, seed + 11, seed + 12, seed + 13, seed + 14, seed + 15,
                seed + 16, seed + 17, seed + 18, seed + 19, new BigDecimal("0.75"),
                seed + 20, seed + 21, seed + 22, seed + 23, seed + 24, seed + 25,
                new BigDecimal("0.6"), (int) seed, "{}");
    }

    private static List<ReplayInterfaceComparisonRow> generatedComparisons() {
        return List.of(new ReplayInterfaceComparisonRow("RPT20260904-094201-5355", ReplayDailyRowType.DETAIL,
                "0126", "S0126", "交易0126", "开发", "行内", "公共组",
                100L, 1L, 2L, 3L, 4L, 90L, 0L, new BigDecimal("0.9"),
                new BigDecimal("0.95"), new BigDecimal("1.2"), new BigDecimal("1.3"), 1, "{}"));
    }

    private static List<ReplayCoverageSummaryRow> generatedCoverageSummaries() {
        return List.of(
                new ReplayCoverageSummaryRow("RPT20260904-094201-5355", ReplayDailyRowType.DOMAIN_DETAIL,
                        "公共组", 100L, 90L, 10L, 1L, 2L, 3L, new BigDecimal("0.9"), 1, "{}"),
                new ReplayCoverageSummaryRow("RPT20260904-094201-5355", ReplayDailyRowType.DOMAIN_TOTAL,
                        "合计", 100L, 90L, 10L, 1L, 2L, 3L, new BigDecimal("0.9"), 2, "{}"));
    }

    private static List<ReplayCoverageDetailRow> generatedCoverageDetails() {
        return List.of(new ReplayCoverageDetailRow("RPT20260904-094201-5355", "0126", "交易0126",
                "公共组", "S0126", "R0126", "是", LocalDate.of(2026, 8, 2), 100L,
                "已发送", null, "开发", "行内", 1, "{}"));
    }
}
