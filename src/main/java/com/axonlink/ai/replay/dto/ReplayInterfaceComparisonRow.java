package com.axonlink.ai.replay.dto;

import java.math.BigDecimal;

public record ReplayInterfaceComparisonRow(
        String batchNo,
        ReplayDailyRowType rowType,
        String transactionCode,
        String sCode,
        String transactionDescription,
        String developer,
        String bankOwner,
        String domain,
        Long sentTransactionCount,
        Long c528SuccessCcbsFail,
        Long c528FailCcbsSuccess,
        Long bothFailSameCode,
        Long bothFailDiffCode,
        Long bothSuccess,
        Long codeIgnored,
        BigDecimal transactionSuccessRate,
        BigDecimal matchPassRate,
        BigDecimal c528AvgDuration,
        BigDecimal ccbsAvgDuration,
        int sourceRow,
        String rawJson) {

    public ReplayInterfaceComparisonRow {
        transactionSuccessRate = normalize(transactionSuccessRate);
        matchPassRate = normalize(matchPassRate);
        c528AvgDuration = normalize(c528AvgDuration);
        ccbsAvgDuration = normalize(ccbsAvgDuration);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
