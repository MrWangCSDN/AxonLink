package com.axonlink.ai.replay.dto;

/** Identity recorded on replay issue tracking events. */
public record ReplayIssueOperator(String username, String realName) {
    public static ReplayIssueOperator system() {
        return new ReplayIssueOperator("SYSTEM", "系统");
    }
}
