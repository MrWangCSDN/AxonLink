package com.axonlink.ai.replay.dto;

/** Mutually exclusive completion bucket for one issue with a planned completion date. */
public enum ReplayIssueCompletionCategory {
    ON_TIME_FIXED,
    LATE_FIXED,
    UNFINISHED,
    OVERDUE_UNFINISHED
}
