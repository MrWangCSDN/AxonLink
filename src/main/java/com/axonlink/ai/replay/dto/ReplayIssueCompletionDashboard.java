package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/** Completion dashboard for one normalized inclusive planned-date range. */
public record ReplayIssueCompletionDashboard(
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate effectiveStartDate,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate effectiveEndDate,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate today,
        ReplayIssueCompletionCounts summary,
        List<ReplayIssueCompletionGroupRow> groups) {
}
