package com.axonlink.ai.replay.dto;

import java.util.List;

/** Replaces the complete current weekly-task batch set; an empty list clears it. */
public record ReplayIssueWeeklyTaskUpdateRequest(List<String> batchNames) {
}
