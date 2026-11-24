package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Completion counts with additive delay totals and derived rates/averages. */
public record ReplayIssueCompletionCounts(
        long plannedTotal,
        long onTimeFixedCount,
        long lateFixedCount,
        long unfinishedCount,
        long overdueUnfinishedCount,
        BigDecimal completionRate,
        long pendingVerificationCount,
        @JsonIgnore long lateFixedDaysTotal,
        @JsonIgnore long overdueUnfinishedDaysTotal) {

    public static ReplayIssueCompletionCounts of(long onTimeFixedCount, long lateFixedCount,
                                                  long unfinishedCount, long overdueUnfinishedCount) {
        return of(onTimeFixedCount, lateFixedCount, unfinishedCount, overdueUnfinishedCount, 0);
    }

    public static ReplayIssueCompletionCounts of(long onTimeFixedCount, long lateFixedCount,
                                                  long unfinishedCount, long overdueUnfinishedCount,
                                                  long pendingVerificationCount) {
        return of(onTimeFixedCount, lateFixedCount, unfinishedCount, overdueUnfinishedCount,
                pendingVerificationCount, 0, 0);
    }

    public static ReplayIssueCompletionCounts of(long onTimeFixedCount, long lateFixedCount,
                                                  long unfinishedCount, long overdueUnfinishedCount,
                                                  long pendingVerificationCount, long lateFixedDaysTotal,
                                                  long overdueUnfinishedDaysTotal) {
        long total = onTimeFixedCount + lateFixedCount + unfinishedCount + overdueUnfinishedCount;
        if (pendingVerificationCount < 0
                || pendingVerificationCount > unfinishedCount + overdueUnfinishedCount) {
            throw new IllegalArgumentException("修复待验证数量必须属于未完成问题");
        }
        BigDecimal rate = total == 0 ? null : BigDecimal.valueOf(onTimeFixedCount + lateFixedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new ReplayIssueCompletionCounts(total, onTimeFixedCount, lateFixedCount,
                unfinishedCount, overdueUnfinishedCount, rate, pendingVerificationCount,
                lateFixedDaysTotal, overdueUnfinishedDaysTotal);
    }

    @JsonProperty("averageLateFixedDays")
    public BigDecimal averageLateFixedDays() {
        return average(lateFixedDaysTotal, lateFixedCount);
    }

    @JsonProperty("averageOverdueUnfinishedDays")
    public BigDecimal averageOverdueUnfinishedDays() {
        return average(overdueUnfinishedDaysTotal, overdueUnfinishedCount);
    }

    private static BigDecimal average(long days, long count) {
        return count == 0 ? null : BigDecimal.valueOf(days)
                .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
}
