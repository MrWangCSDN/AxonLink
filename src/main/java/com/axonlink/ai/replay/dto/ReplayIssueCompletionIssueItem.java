package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/** One issue shown in the completion-statistics drill-down drawer. */
public record ReplayIssueCompletionIssueItem(
        long id,
        String issueId,
        String transactionCode,
        String transactionName,
        ReplayIssueStatus issueStatus,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate plannedCompletionDate,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate defectRepairDate,
        String matchedDeveloper,
        String issueKey) {
}
