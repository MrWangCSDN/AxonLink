package com.axonlink.ai.replay.dto;

import java.math.BigDecimal;

public record ReplayCoverageSummaryRow(
        String batchNo,
        ReplayDailyRowType rowType,
        String businessDomain,
        Long fullTransactionCount,
        Long sentCount,
        Long unsentCount,
        Long excludedCount,
        Long recentTransactionCount,
        Long pendingAnalysisCount,
        BigDecimal coverageRate,
        int sourceRow,
        String rawJson) {

    public ReplayCoverageSummaryRow {
        coverageRate = coverageRate == null ? null : coverageRate.stripTrailingZeros();
    }
}
