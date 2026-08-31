package com.axonlink.ai.replay.dto;

import java.util.List;

/** Counted header-filter candidates, capped at the UI's 500-item working limit. */
public record ReplayIssueHeaderFilterOptionResult(
        int candidateCount,
        long matchedIssueCount,
        boolean truncated,
        List<ReplayIssueHeaderFilterOption> items) {

    public ReplayIssueHeaderFilterOptionResult {
        items = List.copyOf(items);
    }
}
