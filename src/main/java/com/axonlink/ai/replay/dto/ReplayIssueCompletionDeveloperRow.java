package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** Completion counts for one unsplit page developer label within a group. */
public record ReplayIssueCompletionDeveloperRow(
        String matchedDeveloper,
        @JsonUnwrapped ReplayIssueCompletionCounts counts) {
}
