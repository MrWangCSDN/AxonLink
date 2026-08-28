package com.axonlink.ai.replay.dto;

import java.util.List;

/** Current weekly-task batch configuration and its derived issue count. */
public record ReplayIssueWeeklyTaskConfig(
        List<String> batchNames,
        List<String> availableBatchNames,
        long issueCount) {
}
