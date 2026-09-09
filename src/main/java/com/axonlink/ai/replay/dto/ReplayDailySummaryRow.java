package com.axonlink.ai.replay.dto;

import java.math.BigDecimal;

public record ReplayDailySummaryRow(
        String batchNo,
        String domain,
        Long coveredInterfaceCount,
        Long sentTransactionCount,
        Long bothFailSameCode,
        Long bothSuccess,
        Long codeIgnored,
        BigDecimal matchPassRate,
        Long issueTotal,
        int sourceRow,
        String rawJson) {

    public ReplayDailySummaryRow {
        matchPassRate = normalize(matchPassRate);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
