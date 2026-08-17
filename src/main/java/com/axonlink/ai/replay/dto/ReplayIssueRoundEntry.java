package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** Immutable result for one issue in one formal import batch (legacy type name). */
public record ReplayIssueRoundEntry(
        Long id,
        Long roundId,
        String roundCode,
        LocalDateTime importedAt,
        Long replayIssueId,
        String issueKey,
        boolean appeared,
        ReplayIssueStatus statusBefore,
        ReplayIssueStatus statusAfter,
        String actionType,
        String sourceSheet,
        Integer sourceRow,
        LocalDateTime recordedAt) {
}
