package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayWeeklyReportMailSendRequest(
        String startBatchNo,
        String endBatchNo,
        String subject,
        List<String> toEmails,
        List<String> ccEmails,
        String body,
        List<String> reportBatchNos) {
    public ReplayWeeklyReportMailSendRequest(String startBatchNo, String endBatchNo, String subject,
                                             List<String> toEmails, List<String> ccEmails, String body) {
        this(startBatchNo, endBatchNo, subject, toEmails, ccEmails, body, List.of());
    }
}
