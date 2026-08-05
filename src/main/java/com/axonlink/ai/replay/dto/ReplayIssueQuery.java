package com.axonlink.ai.replay.dto;

/** Server-side pagination and filters for the active replay issue snapshot. */
public record ReplayIssueQuery(
        int limit,
        int offset,
        String groupName,
        Boolean sandbox,
        String issueLevel,
        String issueType,
        String keyword,
        String issueStatus,
        String transactionOwner,
        String cooperationPerson) {

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox,
                            String issueLevel, String issueType, String keyword, String issueStatus) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, null, null);
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox,
                            String issueLevel, String issueType, String keyword) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, null, null, null);
    }
}
