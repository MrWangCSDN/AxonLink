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
        String rawJson,
        Long bothSuccessNoFieldDifference,
        Long bothSuccessWithFieldDifference) {

    /** Legacy rows contain a success total but cannot recover its field-difference split. */
    public ReplayInterfaceComparisonRow(String batchNo, ReplayDailyRowType rowType, String transactionCode,
                                        String sCode, String transactionDescription, String developer,
                                        String bankOwner, String domain, Long sentTransactionCount,
                                        Long c528SuccessCcbsFail, Long c528FailCcbsSuccess,
                                        Long bothFailSameCode, Long bothFailDiffCode, Long bothSuccess,
                                        Long codeIgnored, BigDecimal transactionSuccessRate,
                                        BigDecimal matchPassRate, BigDecimal c528AvgDuration,
                                        BigDecimal ccbsAvgDuration, int sourceRow, String rawJson) {
        this(batchNo, rowType, transactionCode, sCode, transactionDescription, developer, bankOwner,
                domain, sentTransactionCount, c528SuccessCcbsFail, c528FailCcbsSuccess, bothFailSameCode,
                bothFailDiffCode, bothSuccess, codeIgnored, transactionSuccessRate, matchPassRate,
                c528AvgDuration, ccbsAvgDuration, sourceRow, rawJson, null, null);
    }

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
