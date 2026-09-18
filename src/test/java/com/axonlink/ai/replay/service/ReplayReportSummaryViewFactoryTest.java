package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.PreviousUnresolved;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayReportSummaryViewFactoryTest {

    @Test
    void createsCompleteLowerSummaryViewWithoutRecalculatingValues() {
        ReplayDailySummaryCalculatedRow current = row("RPT20260916-090000", "公共组", 2);
        ReplayDailySummaryCalculatedRow total = row("RPT20260916-090000", "合计", 20);
        CalculatedReport report = new CalculatedReport(
                List.of(row("RPT20260915-090000", "公共组", 1)),
                row("RPT20260915-090000", "合计", 10),
                List.of(current), total,
                new PreviousUnresolved(1, 2, 3, 4, 5, 15, new BigDecimal("0.75")));

        var view = new ReplayReportSummaryViewFactory().create(
                ReplayReportPeriod.DAILY, null, "RPT20260916-090000", report);

        assertEquals(1, view.schemaVersion());
        assertEquals("RPT", view.family());
        assertEquals("查询日报-20260916", view.reportName());
        assertEquals(21, view.columns().size());
        assertEquals("上一批次未解决问题分类统计", view.columns().get(16).groupLabel());
        assertEquals(current.previousResolutionRate(),
                view.rows().get(0).values().get("previousResolutionRate"));
        assertEquals(current.noAction(), view.rows().get(0).values().get("noAction"));
        assertEquals("TOTAL", view.totalRow().rowType());
        assertEquals("合计", view.totalRow().domain());
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
