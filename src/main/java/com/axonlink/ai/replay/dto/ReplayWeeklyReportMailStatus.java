package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

public record ReplayWeeklyReportMailStatus(
        String startBatchNo,
        String endBatchNo,
        String status,
        String subject,
        String body,
        String senderEmail,
        List<String> toEmails,
        List<String> ccEmails,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime sentAt,
        String failureMessage,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt) {

    public ReplayWeeklyReportMailStatus {
        toEmails = toEmails == null ? List.of() : List.copyOf(toEmails);
        ccEmails = ccEmails == null ? List.of() : List.copyOf(ccEmails);
    }
}
