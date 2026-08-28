package com.axonlink.ai.replay.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Four mutually exclusive completion counts and the derived completion rate. */
public record ReplayIssueCompletionCounts(
        long plannedTotal,
        long onTimeFixedCount,
        long lateFixedCount,
        long unfinishedCount,
        long overdueUnfinishedCount,
        BigDecimal completionRate) {

    public static ReplayIssueCompletionCounts of(long onTimeFixedCount, long lateFixedCount,
                                                  long unfinishedCount, long overdueUnfinishedCount) {
        long total = onTimeFixedCount + lateFixedCount + unfinishedCount + overdueUnfinishedCount;
        BigDecimal rate = total == 0 ? null : BigDecimal.valueOf(onTimeFixedCount + lateFixedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new ReplayIssueCompletionCounts(total, onTimeFixedCount, lateFixedCount,
                unfinishedCount, overdueUnfinishedCount, rate);
    }
}
