package com.axonlink.ai.replay.dto;

import java.util.List;

/** Distinct values available to filter the active replay issue snapshot. */
public record ReplayIssueFilterOptions(
        List<String> groups,
        List<String> issueLevels,
        List<String> issueTypes,
        List<String> issueStatuses,
        List<String> coverageRounds,
        List<String> reviewStatuses) {

    public ReplayIssueFilterOptions(List<String> groups, List<String> issueLevels, List<String> issueTypes,
                                    List<String> issueStatuses, List<String> coverageRounds) {
        this(groups, issueLevels, issueTypes, issueStatuses, coverageRounds, List.of("待审核", "已审核"));
    }

    public ReplayIssueFilterOptions(List<String> groups, List<String> issueLevels, List<String> issueTypes) {
        this(groups, issueLevels, issueTypes, List.of(), List.of());
    }

    public ReplayIssueFilterOptions {
        groups = List.copyOf(groups);
        issueLevels = List.copyOf(issueLevels);
        issueTypes = List.copyOf(issueTypes);
        issueStatuses = List.copyOf(issueStatuses);
        coverageRounds = List.copyOf(coverageRounds);
        reviewStatuses = List.copyOf(reviewStatuses);
    }
}
