package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayWeeklyReportOptions(
        List<ReplayDailyBatch> dailyBatches,
        List<ReplayWeeklyReportOption> weeklyReports) {

    public ReplayWeeklyReportOptions {
        dailyBatches = dailyBatches == null ? List.of() : List.copyOf(dailyBatches);
        weeklyReports = weeklyReports == null ? List.of() : List.copyOf(weeklyReports);
    }
}
