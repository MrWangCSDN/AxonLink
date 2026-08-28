package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/** One real planned-completion date and its non-zero issue count. */
public record ReplayIssueCompletionDatePoint(
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
        long plannedCount) {
}
