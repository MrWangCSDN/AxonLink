package com.axonlink.ai.replay.dto;

/** JSON payload for replay issue list searches with potentially long filters. */
public record ReplayIssueListSearchRequest(
        ReplayIssueQuery query,
        ReplayIssueAffectedTransactionCountOrder affectedTransactionCountOrder,
        ReplayIssueGlobalSerialNoOrder globalSerialNoOrder,
        ReplayIssueSentTransactionCountOrder sentTransactionCountOrder) {

    public ReplayIssueListSearchRequest(ReplayIssueQuery query,
            ReplayIssueAffectedTransactionCountOrder affectedTransactionCountOrder) {
        this(query, affectedTransactionCountOrder, null, null);
    }

    public ReplayIssueListSearchRequest(ReplayIssueQuery query,
            ReplayIssueAffectedTransactionCountOrder affectedTransactionCountOrder,
            ReplayIssueGlobalSerialNoOrder globalSerialNoOrder) {
        this(query, affectedTransactionCountOrder, globalSerialNoOrder, null);
    }

    public ReplayIssueQuery normalizedQuery() {
        return (query == null ? ReplayIssueQuery.empty() : query).forListSearch();
    }
}
