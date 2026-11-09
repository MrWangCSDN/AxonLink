package com.axonlink.ai.replay.reopen;

import java.time.LocalDateTime;
import java.util.List;

/** Stable public types for permanent reopen facts and attribution. */
public final class ReplayReopenDtos {
    private ReplayReopenDtos() {}

    public record Filter(
            String issueKey,
            String batchName,
            String issueDomain,
            String replayType,
            String ownerUsername,
            Long eventId,
            boolean onlyMine,
            int page,
            int pageSize,
            Long sourceRoundId) {
        public Filter(String issueKey, String batchName, String issueDomain, String replayType,
                      String ownerUsername, Long eventId, boolean onlyMine, int page, int pageSize) {
            this(issueKey, batchName, issueDomain, replayType, ownerUsername, eventId, onlyMine,
                    page, pageSize, null);
        }
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) {}

    public record EventRow(
            long id,
            String issueKey,
            long occurrenceNo,
            Long replayIssueId,
            long sourceRoundId,
            String replayType,
            String issueDomain,
            String groupName,
            String batchName,
            String transactionCode,
            String issueDescription,
            LocalDateTime occurredAt,
            LocalDateTime releaseAt,
            String defaultOwnerUsername,
            String defaultOwnerName,
            String currentOwnerUsername,
            String currentOwnerName,
            String ownerResolution,
            String developerSnapshot,
            long version,
            Long pendingAppealId) {}

    public record EventItem(
            EventRow event,
            boolean canAppeal,
            boolean requiresRecheck,
            boolean canAssign,
            AppealSummary latestAppeal) {}

    public record AppealSummary(
            long id,
            String kind,
            String status,
            String suggestedOwnerUsername,
            String finalOwnerUsername,
            String leaderUsername,
            LocalDateTime submittedAt) {}

    public record UserOption(String username, String realName, String displayName) {}

    public record PersonStat(
            String username,
            String realName,
            long defaultCount,
            long adjustedIn,
            long adjustedOut,
            long currentCount,
            long pendingCount,
            long issueCount) {}

    public record BatchStat(String batchName, long totalEvents, List<PersonStat> people) {}

    public record Statistics(
            long totalEvents,
            long distinctIssues,
            long pendingEvents,
            List<PersonStat> people,
            List<BatchStat> batches) {}

    public record AuditEntry(
            long id,
            Long eventId,
            Long appealId,
            String action,
            String operatorUsername,
            String operatorName,
            LocalDateTime occurredAt,
            String reason,
            String evidence,
            String fromOwnerUsername,
            String fromOwnerName,
            String toOwnerUsername,
            String toOwnerName) {}

    public record Policy(
            String issueDomain,
            List<String> leaderUsernames,
            String primaryLeaderUsername,
            long version) {}

    public record BackfillResult(
            int scanned, int inserted, long nextRoundEntryId, boolean hasMore) {}
}
