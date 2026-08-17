package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ReplayIssueMailStatus(String status, LocalDateTime sentAt, String recipientEmail, String failureMessage,
                                    List<ReplayIssueMailRecipientStatus> recipients) {
    public static final String UNSENT = "UNSENT";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";

    public ReplayIssueMailStatus(String status, LocalDateTime sentAt, String recipientEmail, String failureMessage) {
        this(status, sentAt, recipientEmail, failureMessage, List.of());
    }
}
