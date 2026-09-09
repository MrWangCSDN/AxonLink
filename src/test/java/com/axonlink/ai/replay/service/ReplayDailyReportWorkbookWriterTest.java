package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.PreviousUnresolved;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDailyReportWorkbookWriterTest {

    private final ReplayDailyReportWorkbookWriter writer = new ReplayDailyReportWorkbookWriter();

    @Test
    void writesExactlyThreeSheetsInRequiredOrder() throws Exception {
        try (Workbook workbook = workbook()) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("汇总信息", workbook.getSheetName(0));
            assertEquals("接口比对明细", workbook.getSheetName(1));
            assertEquals("回放交易覆盖情况", workbook.getSheetName(2));
        }
    }

    @Test
    void writesStyledSummaryWithMergedParentHeadersAndCalculatedRows() throws Exception {
        try (Workbook workbook = workbook()) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int lowerTitleRow = findRow(sheet, "批次号：RPT-CURRENT（本批次）");

            assertEquals("C6E0B4", fillRgb(sheet.getRow(0).getCell(0)));
            assertEquals("F4CCCC", fillRgb(sheet.getRow(lowerTitleRow).getCell(0)));
            assertEquals("FFF2CC", fillRgb(sheet.getRow(1).getCell(13)));
            assertEquals("FFF2CC", fillRgb(sheet.getRow(2).getCell(13)));
            assertEquals("FFF9E6", fillRgb(sheet.getRow(3).getCell(13)));
            assertEquals("FFF2CC", fillRgb(sheet.getRow(4).getCell(13)));
            assertEquals("F4CCCC", fillRgb(sheet.getRow(lowerTitleRow + 1).getCell(13)));
            assertEquals("F4CCCC", fillRgb(sheet.getRow(lowerTitleRow + 2).getCell(13)));
            assertEquals("FFFFFF", fillRgb(sheet.getRow(lowerTitleRow + 3).getCell(13)));
            assertEquals("E2F0D9", fillRgb(sheet.getRow(lowerTitleRow + 4).getCell(13)));
            assertTrue(hasMergedRegion(sheet, new CellRangeAddress(1, 1, 4, 10)));
            assertTrue(hasMergedRegion(sheet, new CellRangeAddress(1, 1, 14, 23)));
            assertTrue(hasMergedRegion(sheet, new CellRangeAddress(lowerTitleRow, lowerTitleRow, 0, 13)));
            assertTrue(hasMergedRegion(sheet, new CellRangeAddress(lowerTitleRow, lowerTitleRow, 14, 20)));
            assertTrue(hasMergedRegion(sheet, new CellRangeAddress(lowerTitleRow + 1, lowerTitleRow + 1, 16, 20)));
            assertEquals("批次号：RPT-PREVIOUS（上批次）",
                    sheet.getRow(lowerTitleRow).getCell(14).getStringCellValue());

            Cell header = sheet.getRow(2).getCell(4);
            assertEquals("交易核对分类统计", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("交易核对分类统计", sheet.getRow(lowerTitleRow + 1).getCell(4).getStringCellValue());
            assertEquals("528成功/CCBS失败", header.getStringCellValue());
            assertEquals("无需处理", sheet.getRow(2).getCell(9).getStringCellValue());
            assertEquals(HorizontalAlignment.CENTER, header.getCellStyle().getAlignment());
            assertTrue(header.getCellStyle().getWrapText());
            assertEquals(BorderStyle.THIN, header.getCellStyle().getBorderBottom());
            assertEquals("0.00%", sheet.getRow(3).getCell(11).getCellStyle().getDataFormatString());
            assertEquals(0.125d, sheet.getRow(3).getCell(11).getNumericCellValue());
            assertEquals(11d, sheet.getRow(3).getCell(14).getNumericCellValue());
            assertTrue(sheet.getColumnWidth(0) > sheet.getDefaultColumnWidth() * 256);
            assertTrue(sheet.getRow(2).getHeightInPoints() > sheet.getDefaultRowHeightInPoints());
        }
    }

    @Test
    void writesInterfaceRowsBySourceOrderAndPreservesStoredTotalValues() throws Exception {
        try (Workbook workbook = workbook()) {
            Sheet sheet = workbook.getSheet("接口比对明细");

            assertEquals(0, sheet.getNumMergedRegions());
            assertDarkTealBand(sheet.getRow(0), 18);
            assertEquals(List.of("批次号", "交易码", "S码", "交易描述", "开发负责人", "行内负责人", "领域",
                            "发送交易量", "528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致",
                            "二者均失败响应码不一致", "二者均成功", "响应码忽略", "交易成功率", "接口比对通过率",
                            "528平均耗时", "CCBS平均耗时"),
                    rowValues(sheet.getRow(0), 18));
            assertEquals("TX-EARLY", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("TX-LATE", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("合计", sheet.getRow(3).getCell(0).getStringCellValue());
            assertNumericValues(sheet.getRow(3), 7,
                    777d, 1d, 2d, 3d, 4d, 5d, 6d, 0.4567d, 0.7654d, 7.654321d, 8.765432d);
            assertEquals("0.00%", sheet.getRow(3).getCell(14).getCellStyle().getDataFormatString());
            assertEquals("0.000000", sheet.getRow(3).getCell(16).getCellStyle().getDataFormatString());
            assertEquals("B7E1F0", fillRgb(sheet.getRow(1).getCell(0)));
            assertEquals("FFFFFF", fillRgb(sheet.getRow(2).getCell(0)));
            assertEquals(BorderStyle.THIN, sheet.getRow(1).getCell(0).getCellStyle().getBorderBottom());
            assertEquals("A1:R1", ((XSSFSheet) sheet).getCTWorksheet().getAutoFilter().getRef());
            assertTrue(sheet.getColumnWidth(3) > sheet.getDefaultColumnWidth() * 256);
            assertTrue(sheet.getRow(0).getHeightInPoints() > sheet.getDefaultRowHeightInPoints());
        }
    }

    @Test
    void writesCoverageSummaryAndDetailSectionsWithStoredFormatsAndOrder() throws Exception {
        try (Workbook workbook = workbook()) {
            Sheet sheet = workbook.getSheet("回放交易覆盖情况");
            int domainHeaderRow = findRow(sheet, "业务领域");
            int groupTitleRow = findRow(sheet, "按大组汇总");
            int groupHeaderRow = findRow(sheet, "大组");
            int detailHeaderRow = findRow(sheet, "交易码");

            assertEquals(0, sheet.getNumMergedRegions());
            assertEquals("按业务领域汇总", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("005B6B", fillRgb(sheet.getRow(0).getCell(0)));
            assertDarkTealBand(sheet.getRow(domainHeaderRow), 8);
            assertEquals("005B6B", fillRgb(sheet.getRow(groupTitleRow).getCell(0)));
            assertDarkTealBand(sheet.getRow(groupHeaderRow), 8);
            assertEquals(List.of("业务领域", "全量清单交易数", "本次已发送", "本次未发送", "不回放",
                            "近期无交易", "待分析", "覆盖率"),
                    rowValues(sheet.getRow(domainHeaderRow), 8));
            assertEquals("公共组", sheet.getRow(domainHeaderRow + 1).getCell(0).getStringCellValue());
            assertEquals("存款组", sheet.getRow(domainHeaderRow + 2).getCell(0).getStringCellValue());
            assertEquals("合计", sheet.getRow(domainHeaderRow + 3).getCell(0).getStringCellValue());
            assertEquals(26f, sheet.getRow(domainHeaderRow).getHeightInPoints());
            assertEquals(20f, sheet.getRow(domainHeaderRow + 1).getHeightInPoints());
            assertEquals(22f, sheet.getRow(domainHeaderRow + 3).getHeightInPoints());
            assertNumericValues(sheet.getRow(domainHeaderRow + 3), 1,
                    999d, 989d, 10d, 1d, 2d, 3d, 0.5432d);
            assertEquals("0.00%", sheet.getRow(domainHeaderRow + 3).getCell(7).getCellStyle().getDataFormatString());
            assertEquals(List.of("大组", "全量清单交易数", "本次已发送", "本次未发送", "不回放",
                            "近期无交易", "待分析", "覆盖率"),
                    rowValues(sheet.getRow(groupHeaderRow), 8));
            assertEquals("零售组", sheet.getRow(groupHeaderRow + 1).getCell(0).getStringCellValue());
            assertEquals("合计", sheet.getRow(groupHeaderRow + 2).getCell(0).getStringCellValue());
            assertEquals(26f, sheet.getRow(groupHeaderRow).getHeightInPoints());
            assertEquals(20f, sheet.getRow(groupHeaderRow + 1).getHeightInPoints());
            assertEquals(22f, sheet.getRow(groupHeaderRow + 2).getHeightInPoints());

            assertEquals("交易明细", sheet.getRow(detailHeaderRow - 1).getCell(0).getStringCellValue());
            assertEquals("005B6B", fillRgb(sheet.getRow(detailHeaderRow - 1).getCell(0)));
            assertDarkTealBand(sheet.getRow(detailHeaderRow), 12);
            assertEquals(List.of("交易码", "交易描述", "业务领域", "S码", "关联码", "是否需要回放",
                            "最近交易日期", "本次发送交易量", "覆盖状态", "未发送原因", "开发负责人", "行内负责人"),
                    rowValues(sheet.getRow(detailHeaderRow), 12));
            assertEquals("0012", sheet.getRow(detailHeaderRow + 1).getCell(0).getStringCellValue());
            assertEquals("0099", sheet.getRow(detailHeaderRow + 2).getCell(0).getStringCellValue());
            assertEquals("yyyy-mm-dd", sheet.getRow(detailHeaderRow + 1).getCell(6).getCellStyle().getDataFormatString());
            assertEquals("0", sheet.getRow(detailHeaderRow + 1).getCell(7).getCellStyle().getDataFormatString());
            assertEquals("005B6B", fillRgb(sheet.getRow(domainHeaderRow).getCell(0)));
            assertEquals("B7E1F0", fillRgb(sheet.getRow(domainHeaderRow + 1).getCell(0)));
            assertEquals("B7E1F0", fillRgb(sheet.getRow(detailHeaderRow + 1).getCell(0)));
            assertTrue(sheet.getColumnWidth(4) > sheet.getDefaultColumnWidth() * 256);
            assertTrue(sheet.getRow(detailHeaderRow).getHeightInPoints() > sheet.getDefaultRowHeightInPoints());
        }
    }

    private Workbook workbook() throws Exception {
        byte[] bytes = writer.write(summary(), comparisons(), coverageSummaries(), coverageDetails());
        return WorkbookFactory.create(new ByteArrayInputStream(bytes));
    }

    private static CalculatedReport summary() {
        ReplayDailySummaryCalculatedRow previous = calculatedRow("RPT-PREVIOUS", "公共组", 1);
        ReplayDailySummaryCalculatedRow previousTotal = calculatedRow("RPT-PREVIOUS", "合计", 10);
        ReplayDailySummaryCalculatedRow current = calculatedRow("RPT-CURRENT", "公共组", 2);
        ReplayDailySummaryCalculatedRow currentTotal = calculatedRow("RPT-CURRENT", "合计", 20);
        PreviousUnresolved unresolved = new PreviousUnresolved(31, 32, 33, 34, 35, 165,
                new BigDecimal("0.6875"));
        return new CalculatedReport(List.of(previous), previousTotal, List.of(current), currentTotal, unresolved);
    }

    private static ReplayDailySummaryCalculatedRow calculatedRow(String batch, String domain, long seed) {
        return new ReplayDailySummaryCalculatedRow(batch, domain, seed, seed + 1,
                seed + 2, seed + 3, seed + 4, seed + 5, seed + 6, seed + 7, seed + 8,
                seed + 9, new BigDecimal("0.125"), new BigDecimal("0.25"), seed + 10,
                seed + 10, seed + 11, seed + 12, seed + 13, seed + 14, seed + 15,
                seed + 16, seed + 17, seed + 18, seed + 19, new BigDecimal("0.375"),
                seed + 20, seed + 21, seed + 22, seed + 23, seed + 24, seed + 25,
                new BigDecimal("0.625"), (int) seed, "{}");
    }

    private static List<ReplayInterfaceComparisonRow> comparisons() {
        return List.of(
                comparison(9, ReplayDailyRowType.DETAIL, "TX-LATE", 90, "0.901", "1.234567"),
                comparison(20, ReplayDailyRowType.TOTAL, null, 777, "0.4567", "7.654321"),
                comparison(3, ReplayDailyRowType.DETAIL, "TX-EARLY", 30, "0.801", "2.345678"));
    }

    private static ReplayInterfaceComparisonRow comparison(int sourceRow, ReplayDailyRowType rowType,
                                                            String transactionCode, long sent,
                                                            String successRate, String duration) {
        return new ReplayInterfaceComparisonRow("RPT-CURRENT", rowType, transactionCode,
                transactionCode == null ? null : "S-" + transactionCode,
                transactionCode == null ? null : "交易" + transactionCode,
                transactionCode == null ? null : "开发", transactionCode == null ? null : "行内",
                transactionCode == null ? null : "公共组", sent, 1L, 2L, 3L, 4L, 5L, 6L,
                new BigDecimal(successRate), new BigDecimal("0.7654"), new BigDecimal(duration),
                new BigDecimal("8.765432"), sourceRow, "{}");
    }

    private static List<ReplayCoverageSummaryRow> coverageSummaries() {
        return List.of(
                coverageSummary(8, ReplayDailyRowType.DOMAIN_DETAIL, "存款组", 200, "0.75"),
                coverageSummary(20, ReplayDailyRowType.DOMAIN_TOTAL, "合计", 999, "0.5432"),
                coverageSummary(2, ReplayDailyRowType.DOMAIN_DETAIL, "公共组", 100, "0.50"),
                coverageSummary(30, ReplayDailyRowType.GROUP_DETAIL, "零售组", 300, "0.80"),
                coverageSummary(31, ReplayDailyRowType.GROUP_TOTAL, "合计", 300, "0.80"));
    }

    private static ReplayCoverageSummaryRow coverageSummary(int sourceRow, ReplayDailyRowType rowType,
                                                             String domain, long total, String rate) {
        return new ReplayCoverageSummaryRow("RPT-CURRENT", rowType, domain, total,
                total - 10, 10L, 1L, 2L, 3L, new BigDecimal(rate), sourceRow, "{}");
    }

    private static List<ReplayCoverageDetailRow> coverageDetails() {
        return List.of(
                coverageDetail(12, "0099", LocalDate.of(2026, 8, 9), 99),
                coverageDetail(4, "0012", LocalDate.of(2026, 8, 2), 12));
    }

    private static ReplayCoverageDetailRow coverageDetail(int sourceRow, String transactionCode,
                                                           LocalDate date, long sent) {
        return new ReplayCoverageDetailRow("RPT-CURRENT", transactionCode, "交易" + transactionCode,
                "公共组", "S" + transactionCode, "R" + transactionCode, "是", date, sent,
                "已发送", null, "开发", "行内", sourceRow, "{}");
    }

    private static List<String> rowValues(Row row, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> row.getCell(index).getStringCellValue())
                .toList();
    }

    private static int findRow(Sheet sheet, String value) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && value.equals(cell.getStringCellValue())) {
                    return row.getRowNum();
                }
            }
        }
        throw new AssertionError("Missing cell value: " + value);
    }

    private static boolean hasMergedRegion(Sheet sheet, CellRangeAddress expected) {
        return sheet.getMergedRegions().stream().anyMatch(expected::equals);
    }

    private static void assertDarkTealBand(Row row, int cellCount) {
        for (int column = 0; column < cellCount; column++) {
            assertEquals("005B6B", fillRgb(row.getCell(column)),
                    "Expected dark-teal fill at row " + row.getRowNum() + ", column " + column);
        }
    }

    private static void assertNumericValues(Row row, int firstColumn, double... expected) {
        for (int index = 0; index < expected.length; index++) {
            Cell cell = row.getCell(firstColumn + index);
            assertEquals(CellType.NUMERIC, cell.getCellType(),
                    "Expected non-formula numeric cell at column " + cell.getColumnIndex());
            assertEquals(expected[index], cell.getNumericCellValue(), 0.0000001,
                    "Unexpected stored value at column " + cell.getColumnIndex());
        }
    }

    private static String fillRgb(Cell cell) {
        String argb = ((XSSFCellStyle) cell.getCellStyle()).getFillForegroundXSSFColor().getARGBHex();
        return argb != null && argb.length() == 8 ? argb.substring(2) : argb;
    }
}
