package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

public record ReplayIssuePlanDateUpdateResult(
        long id,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate plannedCompletionDate,
        long changeCount) {
}
