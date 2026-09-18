package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.PreviousUnresolved;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayLegacySummaryExtractorTest {

    private final ReplayDailyReportWorkbookWriter writer = new ReplayDailyReportWorkbookWriter();
    private final ReplayReportSummaryViewFactory factory = new ReplayReportSummaryViewFactory();
    private final ReplayLegacySummaryExtractor extractor = new ReplayLegacySummaryExtractor();

    @Test
    void extractsCompleteSummaryFromGeneratedWorkbook() {
        CalculatedReport report = report();
        var expected = factory.create(ReplayReportPeriod.DAILY, null, "RPT20260916-01", report);
        byte[] workbook = writer.write(report, expected, List.of(), List.of(), List.of());

        var actual = extractor.extract(workbook, ReplayReportPeriod.DAILY, null, "RPT20260916-01");

        assertEquals(expected, actual);
    }

    @Test
    void extractsWeeklyMetadataFromGeneratedWorkbook() {
        CalculatedReport report = report();
        var expected = factory.create(ReplayReportPeriod.WEEKLY,
                "DZ20260909-01", "DZ20260916-01", report);
        byte[] workbook = writer.write(report, expected, List.of(), List.of(), List.of());

        var actual = extractor.extract(workbook, ReplayReportPeriod.WEEKLY,
                "DZ20260909-01", "DZ20260916-01");

        assertEquals(expected.period(), actual.period());
        assertEquals(expected.family(), actual.family());
        assertEquals(expected.startBatchNo(), actual.startBatchNo());
        assertEquals(expected.endBatchNo(), actual.endBatchNo());
        assertEquals(expected.reportName(), actual.reportName());
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.totalRow(), actual.totalRow());
    }

    @Test
    void rejectsUnreadableOrStructurallyIncompatibleWorkbooks() throws Exception {
        assertThrows(ReplayLegacySummaryExtractor.LegacySummaryUnavailableException.class,
                () -> extractor.extract(new byte[0], ReplayReportPeriod.DAILY, null, "RPT20260916-01"));
        assertThrows(ReplayLegacySummaryExtractor.LegacySummaryUnavailableException.class,
                () -> extractor.extract(workbookWithSheet("其他"), ReplayReportPeriod.DAILY,
                        null, "RPT20260916-01"));
        assertThrows(ReplayLegacySummaryExtractor.LegacySummaryUnavailableException.class,
                () -> extractor.extract(workbookWithSheet("汇总信息"), ReplayReportPeriod.DAILY,
                        null, "RPT20260916-01"));

        CalculatedReport report = report();
        var view = factory.create(ReplayReportPeriod.DAILY, null, "RPT20260916-01", report);
        byte[] valid = writer.write(report, view, List.of(), List.of(), List.of());
        assertThrows(ReplayLegacySummaryExtractor.LegacySummaryUnavailableException.class,
                () -> extractor.extract(removeHeader(valid), ReplayReportPeriod.DAILY,
                        null, "RPT20260916-01"));
        assertThrows(ReplayLegacySummaryExtractor.LegacySummaryUnavailableException.class,
                () -> extractor.extract(removeTotal(valid), ReplayReportPeriod.DAILY,
                        null, "RPT20260916-01"));
    }

    private static byte[] workbookWithSheet(String sheetName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet(sheetName);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] removeHeader(byte[] bytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("汇总信息");
            for (var row : sheet) {
                if (row.getCell(15) != null
                        && row.getCell(15).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && "上一批次问题解决率".equals(row.getCell(15).getStringCellValue())) {
                    row.getCell(15).setCellValue("错误表头");
                    break;
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] removeTotal(byte[] bytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("汇总信息");
            org.apache.poi.ss.usermodel.Row totalRow = null;
            for (var row : sheet) {
                if (row.getCell(1) != null
                        && row.getCell(1).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && "合计".equals(row.getCell(1).getStringCellValue())) {
                    totalRow = row;
                }
            }
            if (totalRow != null) sheet.removeRow(totalRow);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static CalculatedReport report() {
        var previous = row("RPT20260909-01", "公共组", 1);
        var previousTotal = row("RPT20260909-01", "合计", 10);
        var current = row("RPT20260916-01", "公共组", 2);
        var currentTotal = row("RPT20260916-01", "合计", 20);
        var unresolved = new PreviousUnresolved(31, 32, 33, 34, 35, 165,
                new BigDecimal("0.6875"));
        return new CalculatedReport(List.of(previous), previousTotal,
                List.of(current), currentTotal, unresolved);
    }

    private static ReplayDailySummaryCalculatedRow row(String batch, String domain, long seed) {
        return new ReplayDailySummaryCalculatedRow(batch, domain, seed, seed + 1,
                seed + 2, seed + 3, seed + 4, seed + 5, seed + 6, seed + 7, seed + 8,
                seed + 9, new BigDecimal("0.125"), new BigDecimal("0.25"), seed + 10,
                seed + 10, seed + 11, seed + 12, seed + 13, seed + 14, seed + 15,
                seed + 16, seed + 17, seed + 18, seed + 19, new BigDecimal("0.375"),
                seed + 20, seed + 21, seed + 22, seed + 23, seed + 24, seed + 25,
                new BigDecimal("0.625"), (int) seed, "{}");
    }
}
