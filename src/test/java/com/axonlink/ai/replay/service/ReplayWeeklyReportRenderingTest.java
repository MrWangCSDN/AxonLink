package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.PreviousUnresolved;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayWeeklyReportRenderingTest {

    private static final String START = "RPT20261029-000247-2239";
    private static final String END = "RPT20261105-01";
    private final ReplayDailyReportWorkbookWriter writer = new ReplayDailyReportWorkbookWriter();

    @Test
    void masksOnlyUnknownUpperMetricsAndKeepsBothSelectedBatches() throws Exception {
        CalculatedReport report = report();
        ReplayReportSummaryView view = view(report, false, List.of());

        try (Workbook workbook = workbook(report, view)) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertEquals(3, sheet.getPaneInformation().getHorizontalSplitPosition());
            Row first = dataRow(sheet, START, "公共组");
            Row second = dataRow(sheet, START, "沙箱-存款组");
            Row total = sheet.getRow(second.getRowNum() + 1);

            for (Row row : List.of(first, second, total)) {
                for (int column = 13; column <= 24; column++) assertBlank(row, column);
            }
            for (int column : List.of(9, 11, 12)) assertBlank(first, column);
            assertEquals(3d, first.getCell(4).getNumericCellValue());
            assertEquals(4d, first.getCell(5).getNumericCellValue());
            assertEquals(6d, first.getCell(7).getNumericCellValue());
            for (int column : List.of(4, 5, 7, 12)) assertBlank(second, column);
            assertEquals(9d, second.getCell(9).getNumericCellValue());
            assertEquals(0.125d, second.getCell(11).getNumericCellValue());
            for (int column : List.of(4, 5, 7, 9, 11, 12)) assertBlank(total, column);

            assertEquals(1d, first.getCell(2).getNumericCellValue());
            assertEquals(2d, first.getCell(3).getNumericCellValue());
            assertEquals(5d, first.getCell(6).getNumericCellValue());
            assertEquals(7d, first.getCell(8).getNumericCellValue());
            assertEquals(9d, first.getCell(10).getNumericCellValue());
            assertEquals(START, first.getCell(0).getStringCellValue());
            assertEquals(END, dataRow(sheet, END, "公共组").getCell(0).getStringCellValue());
        }
    }

    @Test
    void writesEveryNullableLowerNumberAsPhysicalBlankAndKeepsKnownZero() throws Exception {
        CalculatedReport report = report();
        ReplayReportSummaryView source = view(report, true, List.of());
        List<ReplayReportSummaryRow> rows = new ArrayList<>(source.rows());
        rows.set(0, withoutNumbers(source.rows().get(0), source));
        LinkedHashMap<String, Object> knownZero = new LinkedHashMap<>(rows.get(1).values());
        knownZero.put("noAction", 0L);
        rows.set(1, new ReplayReportSummaryRow(rows.get(1).domain(), "DETAIL", knownZero));
        ReplayReportSummaryView masked = copy(source, rows, withoutNumbers(source.totalRow(), source));

        try (Workbook workbook = workbook(report, masked)) {
            Sheet sheet = workbook.getSheet("汇总信息");
            Row first = dataRow(sheet, END, "公共组");
            Row second = dataRow(sheet, END, "沙箱-存款组");
            Row total = sheet.getRow(second.getRowNum() + 1);
            for (int column = 2; column <= 20; column++) {
                assertBlank(first, column);
                assertBlank(total, column);
            }
            assertEquals(CellType.NUMERIC, second.getCell(9).getCellType());
            assertEquals(0d, second.getCell(9).getNumericCellValue());
        }
    }

    @Test
    void writesPersistedNoticesBeforeUpperTableWithoutLosingLowerBatchSuffix() throws Exception {
        CalculatedReport report = report();
        List<String> notices = List.of("按全批次最新结论回算，非当时审批", "起始批次缺原量 <9&25>");
        ReplayReportSummaryView view = view(report, true, notices);

        try (Workbook workbook = workbook(report, view)) {
            Sheet sheet = workbook.getSheet("汇总信息");
            int firstNotice = textRow(sheet, notices.get(0));
            int secondNotice = textRow(sheet, notices.get(1));
            int upperTitle = textRow(sheet, "批次号：" + START + "（上批次）");
            int lowerTitle = textRow(sheet, "批次号：" + END + "（本批次）");

            assertTrue(firstNotice < secondNotice && secondNotice < upperTitle);
            assertTrue(upperTitle < lowerTitle);
            assertEquals(0, sheet.getPaneInformation().getHorizontalSplitPosition());
            assertEquals("批次号：" + START + "（上批次）",
                    sheet.getRow(lowerTitle).getCell(14).getStringCellValue());
            assertTrue(sheet.getMergedRegions().stream().anyMatch(range ->
                    range.getFirstRow() == firstNotice && range.getLastRow() == firstNotice
                            && range.getFirstColumn() == 0 && range.getLastColumn() == 24));
            assertEquals(2d, dataRow(sheet, START, "公共组").getCell(3).getNumericCellValue());
        }
    }

    @Test
    void keepsLargePersistedNoticeListsScrollableInsteadOfFreezingThemOverTheData() throws Exception {
        List<String> notices = new ArrayList<>();
        notices.add("历史缺口说明：" + "字段和交易数量待核实；".repeat(80));
        for (int index = 1; index < 100; index++) notices.add("历史缺口说明 " + index);
        CalculatedReport report = report();

        try (Workbook workbook = workbook(report, view(report, true, notices))) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertEquals(0, sheet.getPaneInformation().getHorizontalSplitPosition());
            assertEquals(2, sheet.getPaneInformation().getVerticalSplitPosition());
            assertTrue(textRow(sheet, "批次号：" + START + "（上批次）") >= 100);
            assertEquals(2d, dataRow(sheet, START, "公共组").getCell(3).getNumericCellValue());
            assertEquals(END, dataRow(sheet, END, "公共组").getCell(0).getStringCellValue());
        }
    }

    @Test
    void escapesPersistedCalculationNoticesInMailAndKeepsUnknownCellsEmpty() {
        ReplayReportSummaryView source = view(report(), false,
                List.of("按全批次最新结论回算，非当时审批", "缺量 <script>alert('x')</script>&\"9\""));
        ReplayReportSummaryView masked = copy(source,
                source.rows().stream().map(row -> withoutNumbers(row, source)).toList(),
                withoutNumbers(source.totalRow(), source));
        ReplayReportSummaryCodec codec = new ReplayReportSummaryCodec();

        String html = new ReplayReportMailHtmlRenderer().render("用户说明", List.of(codec.decode(codec.encode(masked))));

        assertTrue(html.contains("按全批次最新结论回算，非当时审批"));
        assertTrue(html.contains("缺量 &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;9&quot;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.indexOf("按全批次最新结论回算") < html.indexOf("<table"));
        assertFalse(html.contains("0.00%"));
    }

    private Workbook workbook(CalculatedReport report, ReplayReportSummaryView view) throws Exception {
        ReplayReportSummaryCodec codec = new ReplayReportSummaryCodec();
        byte[] bytes = writer.write(report, codec.decode(codec.encode(view)), List.of(), List.of(), List.of());
        return WorkbookFactory.create(new ByteArrayInputStream(bytes));
    }

    private static ReplayReportSummaryView view(CalculatedReport report, boolean historicalAvailable,
                                                 List<String> notices) {
        ReplayReportSummaryView base = new ReplayReportSummaryViewFactory().create(
                ReplayReportPeriod.WEEKLY, START, END, report);
        LinkedHashMap<String, Object> total = new LinkedHashMap<>(base.totalRow().values());
        total.put("calculationContext", Map.of(
                "previous", Map.of("issueStatisticsAvailable", historicalAvailable,
                        "noActionUnknownDomains", List.of("公共组"),
                        "breakdownUnknownDomains", List.of("沙箱-存款组"),
                        "matchUnknownDomains", List.of("沙箱-存款组")),
                "current", Map.of("issueStatisticsAvailable", true,
                        "noActionUnknownDomains", List.of(), "breakdownUnknownDomains", List.of(),
                        "matchUnknownDomains", List.of()),
                "notices", notices));
        return copy(base, base.rows(), new ReplayReportSummaryRow("合计", "TOTAL", total));
    }

    private static ReplayReportSummaryView copy(ReplayReportSummaryView source,
                                                 List<ReplayReportSummaryRow> rows,
                                                 ReplayReportSummaryRow total) {
        return new ReplayReportSummaryView(source.schemaVersion(), source.period(), source.family(),
                source.startBatchNo(), source.endBatchNo(), source.reportName(), source.columns(), rows, total);
    }

    private static ReplayReportSummaryRow withoutNumbers(ReplayReportSummaryRow row, ReplayReportSummaryView view) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(row.values());
        view.columns().stream().filter(column -> column.order() >= 2)
                .forEach(column -> values.put(column.key(), null));
        return new ReplayReportSummaryRow(row.domain(), row.rowType(), values);
    }

    private static CalculatedReport report() {
        return new CalculatedReport(List.of(row(START, "公共组", 1), row(START, "沙箱-存款组", 2)),
                row(START, "合计", 10), List.of(row(END, "公共组", 3), row(END, "沙箱-存款组", 4)),
                row(END, "合计", 20), new PreviousUnresolved(1, 2, 3, 4, 5, 15, new BigDecimal("0.5")));
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

    private static Row dataRow(Sheet sheet, String batch, String domain) {
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            Cell second = row.getCell(1);
            if (first != null && second != null && first.getCellType() == CellType.STRING
                    && second.getCellType() == CellType.STRING && batch.equals(first.getStringCellValue())
                    && domain.equals(second.getStringCellValue())) return row;
        }
        throw new AssertionError("Missing selected batch/domain: " + batch + "/" + domain);
    }

    private static int textRow(Sheet sheet, String text) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && text.equals(cell.getStringCellValue())) {
                    return row.getRowNum();
                }
            }
        }
        throw new AssertionError("Missing workbook text: " + text);
    }

    private static void assertBlank(Row row, int column) {
        assertEquals(CellType.BLANK, row.getCell(column).getCellType(),
                "Expected physical blank at row " + row.getRowNum() + ", column " + column);
    }
}
