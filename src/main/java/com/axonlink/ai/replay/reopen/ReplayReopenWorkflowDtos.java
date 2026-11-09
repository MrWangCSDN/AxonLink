package com.axonlink.ai.replay.reopen;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.axonlink.ai.replay.reopen.ReplayReopenDtos.Policy;
import com.axonlink.ai.replay.reopen.ReplayReopenDtos.UserOption;

/** Request, stored row and public view contracts for responsibility adjustments. */
public final class ReplayReopenWorkflowDtos {
    private ReplayReopenWorkflowDtos() {}
    public record Permissions(boolean enabled, UserOption currentUser, boolean canManagePermissions,
                              List<String> reviewableDomains, List<Policy> policies,
                              List<String> issueDomains, boolean canTransferCounts) {
        public Permissions(boolean enabled, UserOption currentUser, boolean canManagePermissions,
                           List<String> reviewableDomains, List<Policy> policies, List<String> issueDomains) {
            this(enabled, currentUser, canManagePermissions, reviewableDomains, policies, issueDomains, false);
        }
    }
    public record CountTransferRequest(String requestId, String replayType, List<String> batchNames,
                                       String sourceOwnerUsername, String targetOwnerUsername,
                                       BigDecimal count, String reason) {}
    public record CountTransferResult(String requestId, int count, String sourceOwnerUsername,
                                      String targetOwnerUsername, LocalDateTime occurredAt) {}
    public record CountTransferRow(String requestId, String requestFingerprint, String operatorUsername,
                                   String sourceOwnerUsername, String targetOwnerUsername,
                                   int count, LocalDateTime occurredAt) {
        public CountTransferResult result() {
            return new CountTransferResult(requestId, count, sourceOwnerUsername, targetOwnerUsername, occurredAt);
        }
    }
    public record CreateAppealRequest(String requestId, List<Long> eventIds, Map<String,Long> expectedVersions, String kind, String suggestedOwnerUsername, String reason, String evidence) {}
    public record DecisionRequest(Long expectedVersion, String decision, String finalOwnerUsername, String reason) {}
    public record WithdrawRequest(Long expectedVersion, String reason) {}
    public record ReassignRequest(Long expectedVersion, String leaderUsername, String reason) {}
    public record AssignRequest(Long expectedVersion, String targetOwnerUsername, String reason) {}
    public record PolicyRequest(Long expectedVersion, List<String> leaderUsernames, String primaryLeaderUsername, String reason) {}
    public record AppealItem(long eventId, String issueKey, long occurrenceNo, String batchName, long expectedVersion, String beforeOwnerUsername, String beforeOwnerName) {}
    public record AppealRow(long id, long version, String requestId, String requestFingerprint, String kind, String status, String issueDomain, String applicantUsername, String applicantName, String suggestedOwnerUsername, String suggestedOwnerName, String finalOwnerUsername, String finalOwnerName, String leaderUsername, String leaderName, String reason, String evidence, String evidenceHash, LocalDateTime submittedAt, LocalDateTime decidedAt, String decisionReason, LocalDateTime closedAt, String closeReason) {}
    public record AppealView(long id, long version, String kind, String status, String issueDomain, List<AppealItem> items, String applicantUsername, String applicantName, String suggestedOwnerUsername, String suggestedOwnerName, String finalOwnerUsername, String finalOwnerName, String leaderUsername, String leaderName, String reason, String evidence, LocalDateTime submittedAt, LocalDateTime decidedAt, String decisionReason, LocalDateTime closedAt, String closeReason, boolean canDecide, boolean canWithdraw, boolean canReassign) {}
}
