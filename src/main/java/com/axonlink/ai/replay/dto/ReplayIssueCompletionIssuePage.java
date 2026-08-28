package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/** Stable paged result for a completion-category drill-down. */
public record ReplayIssueCompletionIssuePage(
        long total,
        List<ReplayIssueCompletionIssueItem> items,
        int limit,
        int offset,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate today) {
}
