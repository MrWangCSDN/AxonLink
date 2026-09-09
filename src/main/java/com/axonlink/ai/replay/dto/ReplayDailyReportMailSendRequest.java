package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayDailyReportMailSendRequest(
        String batchNo,
        String subject,
        List<String> toEmails,
        List<String> ccEmails,
        String body) {
}
