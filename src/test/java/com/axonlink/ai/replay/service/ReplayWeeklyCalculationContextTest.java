package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReplayWeeklyCalculationContextTest {
    private static final String START = "RPT20261029-000247-2239";
    private static final String END = "RPT20261104-194516-0435";

    @Test
    void separatesHistoricalProblemCountsFromLatestConclusionTransactionAmounts() {
        var historical = new ReplayDailyIssueStatisticRow(1, "公共组", false, "代码问题", "字段级", "字段",
                "打开", 999, false, null, "006208", null);
        var metric = new ReplayDailyIssueStatisticRow(1, "旧领域", false, "规则性差异", "交易级", "字段",
                "无需处理", 9, false, ReplayIssueReviewStatus.APPROVED, "006208", null);
        var calculated = new ReplayDailyReportCalculator().calculate(
                List.of(summary(START, "公共组")), List.of(historical), List.of(detail(START)),
                List.of(summary(END, "公共组")), List.of(historical), List.of(detail(END)),
                List.of(metric), List.of(metric));

        assertEquals(1, calculated.currentTotal().issueTotal());
        assertEquals(1, calculated.currentTotal().codeIssueCount());
        assertEquals(9, calculated.currentTotal().noAction());
        assertEquals(new BigDecimal("0.91111111"), calculated.currentTotal().successRate());
    }

    @Test
    void masksUnknownMetricsAndTheirTotalsWithoutHidingOtherDomainsOrStaticFacts() {
        var report = new ReplayDailyReportCalculator().calculate(List.of(summary(START, "公共组")), List.of(),
                List.of(summary(END, "公共组"), summary(END, "存款组")), List.of());
        var context = new ReplayReportCalculationContext(
                new ReplayReportCalculationContext.Side(false, Set.of(), Set.of(), Set.of()),
                new ReplayReportCalculationContext.Side(false, Set.of("公共组"), Set.of("公共组"), Set.of()),
                List.of("按最新审核／修复结论回算", "旧批次数量缺失<待核实>"));
        var view = new ReplayReportSummaryViewFactory().create(ReplayReportPeriod.WEEKLY, START, END, report, context);

        for (String key : List.of("c528SuccessCcbsFail", "c528FailCcbsSuccess", "bothFailDiffCode", "noAction",
                "successRate", "matchPassRate", "issueTotal", "previousUnresolvedTotal", "previousResolutionRate")) {
            assertNull(view.rows().get(0).values().get(key), key);
            assertNull(view.totalRow().values().get(key), key);
        }
        assertEquals(0L, view.rows().get(1).values().get("noAction"));
        assertEquals(100L, view.rows().get(0).values().get("sentTransactionCount"));
        assertEquals(200L, view.totalRow().values().get("sentTransactionCount"));
        var restored = new ReplayReportSummaryCodec().decode(new ReplayReportSummaryCodec().encode(view));
        assertEquals(context, ReplayReportCalculationContext.from(restored));
        assertTrue(ReplayReportCalculationContext.from(restored).current().hideNoAction("公共组", false));
    }

    @Test
    void missingFieldSplitMasksOnlyMatchRateAndDoesNotHideKnownZeroNoAction() {
        var report = new ReplayDailyReportCalculator().calculate(List.of(summary(START, "公共组")), List.of(),
                List.of(summary(END, "公共组")), List.of());
        var context = new ReplayReportCalculationContext(ReplayReportCalculationContext.Side.complete(),
                new ReplayReportCalculationContext.Side(true, Set.of(), Set.of(), Set.of("公共组")), List.of());
        var view = new ReplayReportSummaryViewFactory().create(ReplayReportPeriod.WEEKLY, START, END, report, context);
        assertEquals(0L, view.totalRow().values().get("noAction"));
        assertNotNull(view.totalRow().values().get("successRate"));
        assertNull(view.totalRow().values().get("matchPassRate"));
        assertEquals(0L, view.totalRow().values().get("issueTotal"));
    }

    @Test
    void oldSummaryWithoutContextRemainsCompatible() {
        var report = new ReplayDailyReportCalculator().calculate(List.of(), List.of(),
                List.of(summary(END, "公共组")), List.of());
        var view = new ReplayReportSummaryViewFactory().create(ReplayReportPeriod.DAILY, null, END, report);
        assertEquals(ReplayReportCalculationContext.complete(), ReplayReportCalculationContext.from(view));
    }

    private static ReplayDailySummaryRow summary(String batch, String domain) {
        return new ReplayDailySummaryRow(batch, domain, 1L, 100L, 2L, 80L, 1L,
                new BigDecimal("0.80"), 0L, 1, "{}");
    }

    private static ReplayInterfaceComparisonRow detail(String batch) {
        return new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL, "006208", "S1", "交易", "开发", "行内",
                "公共组", 100L, 0L, 0L, 2L, 0L, 80L, 1L, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}", 60L, 20L);
    }
}
