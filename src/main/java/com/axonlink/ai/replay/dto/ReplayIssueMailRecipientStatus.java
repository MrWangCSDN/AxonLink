package com.axonlink.ai.replay.dto;

/** Mail state for one resolved replay-issue recipient. */
public record ReplayIssueMailRecipientStatus(
        String displayName,
        String username,
        String email,
        String role,
        String status,
        String sentAt,
        String failureMessage) {
}
