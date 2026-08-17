package com.axonlink.ai.replay.dto;

/** Real-time non-fixed issue ranking for one developer combination within a replay group. */
public record ReplayIssuePersonRanking(
        int rank,
        String groupName,
        String developer,
        long newCount,
        long openCount,
        long deferredCount,
        long reopenedCount,
        long pendingVerificationCount,
        long totalCount) {
    public ReplayIssuePersonRanking(int rank, String groupName, String developer, long openCount, long deferredCount,
                                    long reopenedCount, long pendingVerificationCount, long totalCount) {
        this(rank, groupName, developer, 0, openCount, deferredCount, reopenedCount, pendingVerificationCount, totalCount);
    }
}
