package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Review lifecycle for issues whose business status is "无需处理". */
public enum ReplayIssueReviewStatus {
    PENDING("待审核"),
    APPROVED("已审核");

    private final String displayValue;

    ReplayIssueReviewStatus(String displayValue) {
        this.displayValue = displayValue;
    }

    @JsonValue
    public String displayValue() {
        return displayValue;
    }

    @JsonCreator
    public static ReplayIssueReviewStatus fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        for (ReplayIssueReviewStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized) || status.displayValue.equals(normalized)) return status;
        }
        return null;
    }
}
