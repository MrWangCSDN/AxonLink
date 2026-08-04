package com.axonlink.ai.replay.dto;

import java.util.List;

/** Distinct values available to filter the active replay issue snapshot. */
public record ReplayIssueFilterOptions(
        List<String> groups,
        List<String> issueLevels,
        List<String> issueTypes) {

    public ReplayIssueFilterOptions {
        groups = List.copyOf(groups);
        issueLevels = List.copyOf(issueLevels);
        issueTypes = List.copyOf(issueTypes);
    }
}
