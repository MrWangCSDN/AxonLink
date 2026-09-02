package com.axonlink.ai.replay.dto;

import java.util.Locale;

/** Batch-family scope used by replay issue lists and statistics. */
public enum ReplayIssueReplayType {
    ALL(null),
    DZ("DZ"),
    QUERY("RPT");

    private final String batchPrefix;

    ReplayIssueReplayType(String batchPrefix) {
        this.batchPrefix = batchPrefix;
    }

    public String batchPrefix() {
        return batchPrefix;
    }

    public static ReplayIssueReplayType parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("回放交易类型不合法");
        }
    }
}
