package com.axonlink.ai.replay.dto;

/** Minimal collaborator option returned by fuzzy user lookup. */
public record ReplayIssueUserOption(String username, String realName, String displayName) {

    public ReplayIssueUserOption(String username, String realName) {
        this(username, realName, formatDisplayName(username, realName));
    }

    public ReplayIssueUserOption {
        username = username == null ? "" : username;
        realName = realName == null ? "" : realName;
        displayName = displayName == null || displayName.isBlank()
                ? formatDisplayName(username, realName) : displayName;
    }

    private static String formatDisplayName(String username, String realName) {
        return realName + "(" + username + ")";
    }
}
