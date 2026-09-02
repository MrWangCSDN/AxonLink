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
        LocalDateTime recordedAt,
        String incomingSnapshot,
        String batchName) {

    public ReplayIssueRoundEntry(Long id, Long roundId, String roundCode, LocalDateTime importedAt,
                                 Long replayIssueId, String issueKey, boolean appeared,
                                 ReplayIssueStatus statusBefore, ReplayIssueStatus statusAfter,
                                 String actionType, String sourceSheet, Integer sourceRow,
                                 LocalDateTime recordedAt) {
        this(id, roundId, roundCode, importedAt, replayIssueId, issueKey, appeared, statusBefore,
                statusAfter, actionType, sourceSheet, sourceRow, recordedAt, null, null);
    }

    public ReplayIssueRoundEntry(Long id, Long roundId, String roundCode, LocalDateTime importedAt,
                                 Long replayIssueId, String issueKey, boolean appeared,
                                 ReplayIssueStatus statusBefore, ReplayIssueStatus statusAfter,
                                 String actionType, String sourceSheet, Integer sourceRow,
                                 LocalDateTime recordedAt, String incomingSnapshot) {
        this(id, roundId, roundCode, importedAt, replayIssueId, issueKey, appeared, statusBefore,
                statusAfter, actionType, sourceSheet, sourceRow, recordedAt, incomingSnapshot, null);
    }
}
