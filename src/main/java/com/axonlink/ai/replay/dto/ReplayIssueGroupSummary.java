package com.axonlink.ai.replay.dto;

/** Real-time non-fixed issue counts for one replay group. */
public record ReplayIssueGroupSummary(
        String groupName,
        long newCount,
        long openCount,
        long deferredCount,
        long reopenedCount,
        long pendingVerificationCount,
        long totalCount) {
    public ReplayIssueGroupSummary(String groupName, long openCount, long deferredCount, long reopenedCount,
                                   long pendingVerificationCount, long totalCount) {
        this(groupName, 0, openCount, deferredCount, reopenedCount, pendingVerificationCount, totalCount);
    }
}
