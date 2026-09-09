package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

public record ReplayWeeklyReportMailView(
        String startBatchNo,
        String endBatchNo,
        String subject,
        List<String> toEmails,
        List<String> ccEmails,
        String body,
        String status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime sentAt,
        String failureMessage) {

    public ReplayWeeklyReportMailView {
        toEmails = toEmails == null ? List.of() : List.copyOf(toEmails);
        ccEmails = ccEmails == null ? List.of() : List.copyOf(ccEmails);
    }
}
