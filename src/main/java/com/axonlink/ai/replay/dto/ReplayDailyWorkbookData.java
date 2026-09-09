package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayDailyWorkbookData(
        String batchNo,
        List<ReplayDailySummaryRow> summaries,
        List<ReplayInterfaceComparisonRow> comparisons,
        List<ReplayCoverageSummaryRow> coverageSummaries,
        List<ReplayCoverageDetailRow> coverageDetails) {

    public ReplayDailyWorkbookData {
        summaries = List.copyOf(summaries);
        comparisons = List.copyOf(comparisons);
        coverageSummaries = List.copyOf(coverageSummaries);
        coverageDetails = List.copyOf(coverageDetails);
    }
}
