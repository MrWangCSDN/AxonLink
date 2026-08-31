package com.axonlink.ai.replay.dto;

/** One normalized header-filter candidate and its distinct current issue count. */
public record ReplayIssueHeaderFilterOption(String value, long count) {
}
