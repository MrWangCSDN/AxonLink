package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/** Full discrete date axis plus the default latest-three effective range. */
public record ReplayIssueCompletionDatePointsResponse(
        List<ReplayIssueCompletionDatePoint> datePoints,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate defaultStartDate,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate defaultEndDate) {
}
