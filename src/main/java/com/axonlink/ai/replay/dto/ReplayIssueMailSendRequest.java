package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayIssueMailSendRequest(List<String> recipientEmails) {
    public ReplayIssueMailSendRequest {
        recipientEmails = recipientEmails == null ? List.of() : recipientEmails.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }
}
