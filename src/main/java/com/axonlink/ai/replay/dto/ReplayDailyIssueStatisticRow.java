package com.axonlink.ai.replay.dto;

import java.time.LocalDate;

public record ReplayDailyIssueStatisticRow(
        long issueId,
        String groupName,
        boolean sandbox,
        String issueType,
        String issueLevel,
        String fieldName,
        String issueStatus,
        long affectedTransactionCount,
        boolean reopenedAfterFixed,
        ReplayIssueReviewStatus reviewStatus,
        String transactionCode,
        LocalDate defectRepairDate) {
}
