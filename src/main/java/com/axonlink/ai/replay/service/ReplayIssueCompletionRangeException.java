package com.axonlink.ai.replay.service;

/** Invalid or non-normalizable planned-completion date range. */
public class ReplayIssueCompletionRangeException extends IllegalArgumentException {
    public ReplayIssueCompletionRangeException() {
        super("计划验证日期范围不合法");
    }
}
