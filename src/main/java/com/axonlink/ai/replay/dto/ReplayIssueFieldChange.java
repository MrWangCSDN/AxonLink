package com.axonlink.ai.replay.dto;

/** One user-facing field difference in a replay issue operation. */
public record ReplayIssueFieldChange(String field, String before, String after) {
}
