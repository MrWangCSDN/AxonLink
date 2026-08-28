package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/** Group completion counts and its developer-combination breakdown. */
public record ReplayIssueCompletionGroupRow(
        String groupName,
        @JsonUnwrapped ReplayIssueCompletionCounts counts,
        List<ReplayIssueCompletionDeveloperRow> developers) {
}
