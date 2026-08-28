package com.axonlink.ai.replay.dto;

/** Real-time formal-status issue ranking for one developer combination within a replay group. */
public record ReplayIssuePersonRanking(
        int rank,
        String groupName,
        String developer,
        long newCount,
        long openCount,
        long reopenedCount,
        long deferredCount,
        long pendingVerificationCount,
        long pendingTotalCount,
        long noActionCount,
        long fixedCount,
        long fixedTotalCount,
        long totalCount) {
}
