package com.axonlink.ai.replay.dto;

/** JSON payload for counted header-filter searches with potentially long filters. */
public record ReplayIssueHeaderFilterSearchRequest(
        String field,
        String keyword,
        ReplayIssueQuery query) {

    public ReplayIssueQuery normalizedQuery() {
        return (query == null ? ReplayIssueQuery.empty() : query).forHeaderFilterSearch();
    }
}
