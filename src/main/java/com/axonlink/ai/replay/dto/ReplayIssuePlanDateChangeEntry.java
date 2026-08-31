package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ReplayIssuePlanDateChangeEntry(
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate plannedCompletionDate,
        String operatorUsername,
        String operatorRealName,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime changedAt) {
}
