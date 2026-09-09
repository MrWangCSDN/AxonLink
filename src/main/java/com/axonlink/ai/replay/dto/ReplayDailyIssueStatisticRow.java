package com.axonlink.ai.replay.dto;

public record ReplayDailyIssueStatisticRow(
        long issueId,
        String groupName,
        boolean sandbox,
        String issueType,
        String issueLevel,
        String fieldName,
        String issueStatus,
        long affectedTransactionCount,
        boolean reopenedAfterFixed) {
}
