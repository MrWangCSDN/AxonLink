package com.axonlink.ai.replay.dbissue.report;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueReportWorkbookWriterTest {
    private final ReplayDatabaseIssueReportWorkbookWriter writer = new ReplayDatabaseIssueReportWorkbookWriter();

    @Test
    void writesTheScreenshotLayoutWithActualTypeColumnsAndWeightedTotalProgress() throws Exception {
        byte[] bytes = writer.write(example());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(2, workbook.getNumberOfSheets());
            var sheet = workbook.getSheet("领域汇总");
            var header = sheet.getRow(2);
            assertEquals(17, header.getLastCellNum());
            assertEquals("领域", header.getCell(0).getStringCellValue());
            assertEquals("字段总数", header.getCell(7).getStringCellValue());
            assertEquals("比对一致字段数量", header.getCell(8).getStringCellValue());
            assertEquals("比对一致表数量", header.getCell(9).getStringCellValue());
            assertEquals("问题总数（注）", header.getCell(10).getStringCellValue());
            assertEquals("上一批遗留问题总数", header.getCell(11).getStringCellValue());
            assertEquals("未处理", header.getCell(12).getStringCellValue());
            assertEquals("修改代码", header.getCell(13).getStringCellValue());
            assertEquals("规则性差异", header.getCell(14).getStringCellValue());
            assertEquals("新增类型`测试", header.getCell(15).getStringCellValue());
            assertEquals("排查进度", header.getCell(16).getStringCellValue());
            assertEquals(header.getCell(0).getCellStyle().getFillForegroundColorColor(),
                    header.getCell(9).getCellStyle().getFillForegroundColorColor());
            assertNotEquals(header.getCell(0).getCellStyle().getFillForegroundColorColor(),
                    header.getCell(10).getCellStyle().getFillForegroundColorColor());
            assertEquals(150, sheet.getRow(3).getCell(1).getNumericCellValue());
            assertEquals("#,##0", sheet.getRow(3).getCell(1).getCellStyle().getDataFormatString());
            assertEquals(3, sheet.getRow(3).getCell(8).getNumericCellValue());
            assertEquals(2, sheet.getRow(3).getCell(11).getNumericCellValue());
            assertEquals(0, sheet.getRow(4).getCell(8).getNumericCellValue());
            assertEquals(1, sheet.getRow(4).getCell(11).getNumericCellValue());
            assertEquals(2.0 / 3, sheet.getRow(3).getCell(16).getNumericCellValue(), 0.0000001);
            var total = sheet.getRow(5);
            assertEquals("合计", total.getCell(0).getStringCellValue());
            assertEquals(180, total.getCell(1).getNumericCellValue());
            assertEquals(3, total.getCell(8).getNumericCellValue());
            assertEquals(3, total.getCell(11).getNumericCellValue());
            assertEquals(0.6, total.getCell(16).getNumericCellValue(), 0.0000001);
            assertEquals("0.00%", total.getCell(16).getCellStyle().getDataFormatString());
            assertTrue(workbook.getFontAt(total.getCell(0).getCellStyle().getFontIndex()).getBold());
            assertNotNull(sheet.getPaneInformation());
            assertTrue(sheet.getPrintSetup().getLandscape());
            assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().contains("上一日期批次 DB20261028-02"));
            assertTrue(workbook.getSheet("统计口径").getRow(1).getCell(1).getStringCellValue().contains("DB20261029-01"));
            assertEquals("上一日期批次", workbook.getSheet("统计口径").getRow(2).getCell(0).getStringCellValue());
            assertEquals("DB20261028-02", workbook.getSheet("统计口径").getRow(2).getCell(1).getStringCellValue());
        }
        String output = System.getProperty("axon.report.example.path");
        if (output != null) {
            Path path = Path.of(output); Files.createDirectories(path.getParent()); Files.write(path, bytes);
        }
    }

    @Test
    void allowsLongMetricExplanationsToWrapWithoutClippingTheirRows() throws Exception {
        String note = "字段".repeat(80);
        var original = example();
        var report = new ReplayDatabaseIssueReport(original.batchNo(), original.issueTypes(), original.rows(), original.total(),
                List.of("简短说明", note), true, original.registrationVersionNo(), original.previousBatchNo());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(writer.write(report)))) {
            var sheet = workbook.getSheet("统计口径");
            assertEquals(44, sheet.getRow(3).getHeightInPoints());
            assertEquals(note, sheet.getRow(4).getCell(1).getStringCellValue());
            assertTrue(sheet.getRow(4).getCell(1).getCellStyle().getWrapText());
            assertTrue(sheet.getRow(4).getHeightInPoints() >= 64);
        }
    }

    @Test
    void unknownIdenticalFieldCountsRetainKnownFieldTotalsAndNumericCarryoverCounts() throws Exception {
        var row = new ReplayDatabaseIssueReport.Row("公共组", 10, 10, 0, 0, 4,
                1, 3L, 0, 2, 0, Map.of(), 1L, null, 1);
        var total = new ReplayDatabaseIssueReport.Row("合计", 10, 10, 0, 0, 4,
                1, 3L, 0, 2, 0, Map.of(), 1L, null, 1);
        var report = new ReplayDatabaseIssueReport("DB20261029-01", List.of(), List.of(row), total,
                List.of("差异字段超出登记字段，一致字段数未知。"), true, null, "DB20261028-02");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(writer.write(report)))) {
            var sheet = workbook.getSheet("领域汇总");
            for (int rowIndex : List.of(3, 4)) {
                assertEquals(3, sheet.getRow(rowIndex).getCell(7).getNumericCellValue());
                assertEquals("—", sheet.getRow(rowIndex).getCell(8).getStringCellValue());
                assertEquals(1, sheet.getRow(rowIndex).getCell(11).getNumericCellValue());
            }
        }
    }

    @Test
    void preservesUnknownCountsAndLargeIntegersAndWritesTypeLabelsAsText() throws Exception {
        String type = "=HYPERLINK(\"https://example.invalid\")";
        var row = new ReplayDatabaseIssueReport.Row("公共组", 1_234_567_890_123_456L, 0, 0, 0, 1,
                1, null, 0, 1, 0, Map.of(type, 1L));
        var report = new ReplayDatabaseIssueReport("DB20261029-01", List.of(type), List.of(row), row, List.of("当前配置未知"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(writer.write(report)))) {
            var sheet = workbook.getSheet("领域汇总");
            assertEquals(CellType.STRING, sheet.getRow(2).getCell(13).getCellType());
            assertEquals(type, sheet.getRow(2).getCell(13).getStringCellValue());
            assertEquals(CellType.STRING, sheet.getRow(3).getCell(1).getCellType());
            assertEquals("1234567890123456", sheet.getRow(3).getCell(1).getStringCellValue());
            assertEquals("—", sheet.getRow(3).getCell(7).getStringCellValue());
            assertEquals("—", sheet.getRow(3).getCell(8).getStringCellValue());
            assertEquals(0, sheet.getRow(3).getCell(11).getNumericCellValue());
            assertEquals("—", sheet.getRow(3).getCell(5).getStringCellValue());
        }
    }

    @Test
    void rendersIncompleteLegacyTableMetricsAsUnknownButRetainsFieldAndClassificationCounts() throws Exception {
        var original = example();
        var report = new ReplayDatabaseIssueReport(original.batchNo(), original.issueTypes(), original.rows(), original.total(),
                List.of("旧格式：缺少完整表级原始明细，部分表级指标不可还原。"), false,
                original.registrationVersionNo(), original.previousBatchNo());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(writer.write(report)))) {
            var sheet = workbook.getSheet("领域汇总");
            assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().contains("旧格式"));
            for (int index : List.of(1, 2, 3, 4, 6, 7, 8, 9)) {
                assertEquals("—", sheet.getRow(3).getCell(index).getStringCellValue());
                assertEquals("—", sheet.getRow(5).getCell(index).getStringCellValue());
            }
            assertEquals(5, sheet.getRow(3).getCell(5).getNumericCellValue());
            assertEquals(5, sheet.getRow(5).getCell(10).getNumericCellValue());
            assertEquals(2, sheet.getRow(3).getCell(11).getNumericCellValue());
            assertEquals(3, sheet.getRow(5).getCell(11).getNumericCellValue());
            assertEquals(0.6, sheet.getRow(5).getCell(16).getNumericCellValue(), 0.0000001);
        }
    }

    @Test
    void emptyCatalogStillProducesFixedColumnsAndZeroProgress() throws Exception {
        var row = new ReplayDatabaseIssueReport.Row("公共组", 0, 0, 0, 0, 0, 0, 0L, 0, 0, 0, Map.of(), 0L, 0L, 0);
        var total = new ReplayDatabaseIssueReport.Row("合计", 0, 0, 0, 0, 0, 0, 0L, 0, 0, 0, Map.of(), 0L, 0L, 0);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(writer.write(
                new ReplayDatabaseIssueReport(null, List.of(), List.of(row), total, List.of()))))) {
            var sheet = workbook.getSheet("领域汇总");
            assertEquals(14, sheet.getRow(2).getLastCellNum());
            assertEquals("排查进度", sheet.getRow(2).getCell(13).getStringCellValue());
            assertEquals(0, sheet.getRow(3).getCell(8).getNumericCellValue());
            assertEquals(0, sheet.getRow(3).getCell(11).getNumericCellValue());
            assertEquals(0, sheet.getRow(3).getCell(13).getNumericCellValue());
            assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().contains("暂无已导入批次"));
            assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().contains("无上一日期批次"));
            assertTrue(workbook.getSheet("统计口径").getRow(2).getCell(1).getStringCellValue().contains("无上一日期批次"));
        }
    }

    static ReplayDatabaseIssueReport example() {
        var common = new ReplayDatabaseIssueReport.Row("公共组", 150, 160, 2, 12, 5, 2, 5L, 1, 3, 1,
                Map.of("修改代码", 1L, "新增类型`测试", 1L), 5L, 3L, 2);
        var deposit = new ReplayDatabaseIssueReport.Row("存款组", 30, 32, 0, 2, 2, 1, 1L, 0, 2, 1,
                Map.of("规则性差异", 1L), 2L, 0L, 1);
        var total = new ReplayDatabaseIssueReport.Row("合计", 180, 192, 2, 14, 7, 3, 6L, 1, 5, 2,
                Map.of("修改代码", 1L, "规则性差异", 1L, "新增类型`测试", 1L), 7L, 3L, 3);
        return new ReplayDatabaseIssueReport("DB20261029-01", List.of("修改代码", "规则性差异", "新增类型`测试"),
                List.of(common, deposit), total, List.of("统计批次 DB20261029-01", "主键相同、字段不同按批次表明细原始数量逐表汇总。", "字段总数取当前登记配置。"),
                true, null, "DB20261028-02");
    }
}
