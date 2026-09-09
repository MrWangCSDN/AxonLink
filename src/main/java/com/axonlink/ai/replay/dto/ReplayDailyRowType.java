package com.axonlink.ai.replay.dto;

public enum ReplayDailyRowType {
    DETAIL,
    TOTAL,
    DOMAIN_DETAIL,
    DOMAIN_TOTAL,
    GROUP_DETAIL,
    GROUP_TOTAL;

    public boolean isTotal() {
        return this == TOTAL || this == DOMAIN_TOTAL || this == GROUP_TOTAL;
    }

    public boolean isGroupSummary() {
        return this == GROUP_DETAIL || this == GROUP_TOTAL;
    }
}
