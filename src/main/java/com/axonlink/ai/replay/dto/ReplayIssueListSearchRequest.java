package com.axonlink.ai.replay.dto;

/** JSON payload for replay issue list searches with potentially long filters. */
public record ReplayIssueListSearchRequest(
        ReplayIssueQuery query,
        ReplayIssueAffectedTransactionCountOrder affectedTransactionCountOrder) {

    public ReplayIssueQuery normalizedQuery() {
        return (query == null ? ReplayIssueQuery.empty() : query).forListSearch();
    }
}
