package com.axonlink.ai.replay.dbcompare.dto;

/** An active bank employee eligible for new technology-owner assignments. */
public record ReplayDbCompareTechnologyOwnerOption(
        String username, String realName, String empNo, String displayName) {
    public ReplayDbCompareTechnologyOwnerOption(String username, String realName, String empNo) {
        this(username, realName, empNo, realName + "(" + empNo + ")");
    }
}
