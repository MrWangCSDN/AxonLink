package com.axonlink.ai.replay.dto;

/** Real-time formal-status issue counts for one replay group. */
public record ReplayIssueGroupSummary(
        String groupName,
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
