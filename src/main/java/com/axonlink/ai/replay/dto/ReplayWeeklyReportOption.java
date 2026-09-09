package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record ReplayWeeklyReportOption(
        String startBatchNo,
        String endBatchNo,
        String family,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime generatedAt,
        String mailStatus,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime mailSentAt,
        String mailFailureMessage) {
}
