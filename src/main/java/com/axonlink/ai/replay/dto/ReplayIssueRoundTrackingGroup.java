package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Import result and subsequent manual edits grouped under one occurrence batch.
 * roundId/roundCode remain wire-compatible legacy names; their business meaning is batch. */
public record ReplayIssueRoundTrackingGroup(
        Long roundId,
        String roundCode,
        LocalDateTime importedAt,
        Boolean appeared,
        ReplayIssueStatus statusBefore,
        ReplayIssueStatus statusAfter,
        String actionType,
        String sourceSheet,
        Integer sourceRow,
        int manualChangeCount,
        ReplayIssueStatus finalStatus,
        List<ReplayIssueHistoryEntry> inheritedEvents,
        List<ReplayIssueHistoryEntry> manualEvents) {

    public ReplayIssueRoundTrackingGroup {
        inheritedEvents = List.copyOf(inheritedEvents);
        manualEvents = List.copyOf(manualEvents);
    }
}
