package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReplayReportRateCompatibilityTest {
    private static final String BATCH = "RPT20261104-194516-0435";

    @Test
    void missingAdjustmentEvidenceStillOutputsTheExactOriginalRoundedAlgorithmInJsonAndExcel() throws Exception {
        var side = new ReplayReportCalculationContext.Side(false, Set.of("公共组"), Set.of("公共组"),
                Set.of("公共组"), Set.of("公共组"));
        var context = new ReplayReportCalculationContext(side, side, List.of("历史数据沿用原算法"));
        var rows = List.of(summary("公共组"));
        var report = new ReplayDailyReportCalculator().calculate(rows, List.of(), List.of(), rows, List.of(),
                List.of(), List.of(), List.of(), context);
        assertEquals(new BigDecimal("0.82828283"), report.currentTotal().successRate());
        assertEquals(new BigDecimal("0.79797980"), report.currentTotal().matchPassRate());
        var view = new ReplayReportSummaryViewFactory().create(ReplayReportPeriod.WEEKLY,
                "RPT20261029-000247-2239", BATCH, report, context);
        assertNull(view.totalRow().values().get("noAction"));
        assertNotNull(view.totalRow().values().get("successRate"));
        assertNotNull(view.totalRow().values().get("matchPassRate"));
        assertEquals(context, ReplayReportCalculationContext.from(new ReplayReportSummaryCodec()
                .decode(new ReplayReportSummaryCodec().encode(view))));
        byte[] bytes = new ReplayDailyReportWorkbookWriter().write(report, view, List.of(), List.of(), List.of());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            int found = 0;
            for (var row : workbook.getSheet("汇总信息")) {
                if (row.getCell(0) != null && BATCH.equals(row.getCell(0).toString())) {
                    assertEquals(CellType.BLANK, row.getCell(9).getCellType());
                    assertEquals(CellType.NUMERIC, row.getCell(11).getCellType());
                    assertEquals(0.79797980, row.getCell(12).getNumericCellValue(), 1e-9);
                    found++;
                }
            }
            assertEquals(2, found);
        }
    }

    @Test
    void mixedOldAndNewDomainsSumTheirActualNumeratorsAndDenominatorsBeforeRounding() {
        var summaries = List.of(summary("旧数据领域"), summary("新数据领域"));
        var metrics = List.of(issue(1L, "OLD", "交易级", 0L), issue(2L, "NEW", "交易级", 9L),
                issue(3L, "NEW", "字段级", 0L));
        var side = new ReplayReportCalculationContext.Side(true, Set.of("旧数据领域"), Set.of(), Set.of(), Set.of("旧数据领域"));
        var context = new ReplayReportCalculationContext(ReplayReportCalculationContext.Side.complete(), side, List.of());
        var report = new ReplayDailyReportCalculator().calculate(List.of(), List.of(), List.of(), summaries, List.of(),
                List.of(detail("旧数据领域", "OLD", null, null), detail("新数据领域", "NEW", 60L, 20L)),
                List.of(), metrics, context);
        assertEquals(new BigDecimal("0.79797980"), report.currentRows().get(0).matchPassRate());
        assertEquals(new BigDecimal("0.91111111"), report.currentRows().get(1).matchPassRate());
        assertEquals(new BigDecimal("0.85185185"), report.currentTotal().matchPassRate());
        assertEquals(new BigDecimal("0.86772487"), report.currentTotal().successRate());
    }

    @Test
    void completeNewFieldsRemainNewAndVisibleWhenSomeAdjustmentEvidenceIsUnknown() {
        var side = new ReplayReportCalculationContext.Side(true, Set.of("公共组"), Set.of(), Set.of("公共组"), Set.of());
        var context = new ReplayReportCalculationContext(ReplayReportCalculationContext.Side.complete(), side, List.of());
        var report = new ReplayDailyReportCalculator().calculate(List.of(), List.of(), List.of(),
                List.of(summary("公共组")), List.of(), List.of(detail("公共组", "A", 60L, 20L)),
                List.of(), List.of(issue(1L, "A", "交易级", 9L)), context);
        var view = new ReplayReportSummaryViewFactory().create(ReplayReportPeriod.WEEKLY, "RPT20261029-000247-2239", BATCH, report, context);
        assertNull(view.totalRow().values().get("noAction"));
        assertEquals(new BigDecimal("0.68888889"), view.totalRow().values().get("matchPassRate"),
                "使用新字段60+同码失败2，分母仅扣除已确认的9笔");
        assertEquals(new BigDecimal("0.91111111"), view.totalRow().values().get("successRate"));
    }

    @Test
    void legacyFieldsStillDeductKnownApprovedAmountsWhenOtherAmountsAreUnknown() {
        var side = new ReplayReportCalculationContext.Side(true, Set.of("公共组"), Set.of(), Set.of(), Set.of("公共组"));
        var context = new ReplayReportCalculationContext(ReplayReportCalculationContext.Side.complete(), side, List.of());
        var report = new ReplayDailyReportCalculator().calculate(List.of(), List.of(), List.of(),
                List.of(summary("公共组")), List.of(), List.of(detail("公共组", "A", null, null)),
                List.of(), List.of(issue(1L, "A", "交易级", 9L)), context);
        assertEquals(new BigDecimal("0.87777778"), report.currentTotal().matchPassRate());
        assertEquals(new BigDecimal("0.91111111"), report.currentTotal().successRate());
    }

    @Test
    void originalAlgorithmStillDeductsReliableApprovedNoActionAmounts() {
        var side = new ReplayReportCalculationContext.Side(true, Set.of(), Set.of(), Set.of(), Set.of("公共组"));
        var context = new ReplayReportCalculationContext(ReplayReportCalculationContext.Side.complete(), side, List.of());
        var report = new ReplayDailyReportCalculator().calculate(List.of(), List.of(), List.of(),
                List.of(summary("公共组")), List.of(), List.of(detail("公共组", "A", null, null)),
                List.of(), List.of(issue(1L, "A", "交易级", 9L)), context);
        assertEquals(new BigDecimal("0.87777778"), report.currentTotal().matchPassRate());
        assertEquals(new BigDecimal("0.91111111"), report.currentTotal().successRate());
    }

    private static ReplayDailySummaryRow summary(String domain) {
        return new ReplayDailySummaryRow(BATCH, domain, 1L, 100L, 2L, 80L, 1L, new BigDecimal("0.8"), 0L, 1, "{}");
    }

    private static ReplayDailyIssueStatisticRow issue(long id, String code, String level, long amount) {
        return new ReplayDailyIssueStatisticRow(id, "问题领域不参与率归属", false, "规则性差异", level, "字段",
                "无需处理", amount, false, ReplayIssueReviewStatus.APPROVED, code, LocalDate.of(2026, 11, 4));
    }

    private static ReplayInterfaceComparisonRow detail(String domain, String code, Long noDiff, Long withDiff) {
        return new ReplayInterfaceComparisonRow(BATCH, ReplayDailyRowType.DETAIL, code, "S1", "交易", "开发", "行内",
                domain, 100L, 0L, 0L, 2L, 0L, 80L, 1L, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}", noDiff, withDiff);
    }
}
