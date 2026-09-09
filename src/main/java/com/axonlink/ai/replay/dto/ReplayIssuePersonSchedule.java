package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayIssuePersonSchedule(
        String groupName,
        String developer,
        long scheduleTotalCount,
        long schedulePlannedCount,
        long scheduleUnplannedCount,
        List<ReplayIssueScheduleDateCount> dateCounts) {
}
