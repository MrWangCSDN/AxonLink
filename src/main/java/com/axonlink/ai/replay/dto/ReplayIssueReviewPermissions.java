package com.axonlink.ai.replay.dto;

import java.util.List;
import java.util.Map;

/** Current user's reviewable groups and configured active reviewer contacts. */
public record ReplayIssueReviewPermissions(
        List<String> reviewableGroups,
        Map<String, List<String>> reviewersByGroup,
        List<String> reviewableTransactionCodes) {
}
