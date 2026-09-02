package com.axonlink.ai.replay.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Four mutually exclusive completion counts, an unresolved status subset, and the derived rate. */
public record ReplayIssueCompletionCounts(
        long plannedTotal,
        long onTimeFixedCount,
        long lateFixedCount,
        long unfinishedCount,
        long overdueUnfinishedCount,
        BigDecimal completionRate,
        long pendingVerificationCount) {

    public static ReplayIssueCompletionCounts of(long onTimeFixedCount, long lateFixedCount,
                                                  long unfinishedCount, long overdueUnfinishedCount) {
        return of(onTimeFixedCount, lateFixedCount, unfinishedCount, overdueUnfinishedCount, 0);
    }

    public static ReplayIssueCompletionCounts of(long onTimeFixedCount, long lateFixedCount,
                                                  long unfinishedCount, long overdueUnfinishedCount,
                                                  long pendingVerificationCount) {
        long total = onTimeFixedCount + lateFixedCount + unfinishedCount + overdueUnfinishedCount;
        if (pendingVerificationCount < 0
                || pendingVerificationCount > unfinishedCount + overdueUnfinishedCount) {
            throw new IllegalArgumentException("修复待验证数量必须属于未完成问题");
        }
        BigDecimal rate = total == 0 ? null : BigDecimal.valueOf(onTimeFixedCount + lateFixedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new ReplayIssueCompletionCounts(total, onTimeFixedCount, lateFixedCount,
                unfinishedCount, overdueUnfinishedCount, rate, pendingVerificationCount);
    }
}
