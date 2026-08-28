package com.axonlink.ai.replay.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One append-only replay issue tracking event. */
public record ReplayIssueHistoryEntry(
        Long id,
        Long replayIssueId,
        String issueKey,
        String operationType,
        LocalDateTime operationAt,
        String operatorUsername,
        String operatorRealName,
        ReplayIssueStatus issueStatus,
        String issueType,
        String initialAnalysis,
        String finalSolution,
        String cooperationPersonUsername,
        String cooperationPersonRealName,
        LocalDate importDate,
        String sourceSheet,
        Integer sourceRow,
        String beforeSnapshot,
        String afterSnapshot,
        String incomingSnapshot,
        String remark,
        Long contextRoundId,
        String occurrenceBatchName,
        ReplayIssueReviewStatus reviewStatus,
        String reviewerUsername,
        String reviewerRealName,
        LocalDateTime reviewedAt) {

    /** Compatibility constructor for tracking entries created before review fields were added. */
    public ReplayIssueHistoryEntry(Long id, Long replayIssueId, String issueKey, String operationType,
                                   LocalDateTime operationAt, String operatorUsername, String operatorRealName,
                                   ReplayIssueStatus issueStatus, String issueType, String initialAnalysis,
                                   String finalSolution, String cooperationPersonUsername,
                                   String cooperationPersonRealName, LocalDate importDate, String sourceSheet,
                                   Integer sourceRow, String beforeSnapshot, String afterSnapshot,
                                   String incomingSnapshot, String remark, Long contextRoundId,
                                   String occurrenceBatchName) {
        this(id, replayIssueId, issueKey, operationType, operationAt, operatorUsername, operatorRealName,
                issueStatus, issueType, initialAnalysis, finalSolution, cooperationPersonUsername,
                cooperationPersonRealName, importDate, sourceSheet, sourceRow, beforeSnapshot, afterSnapshot,
                incomingSnapshot, remark, contextRoundId, occurrenceBatchName, null, null, null, null);
    }

    public ReplayIssueHistoryEntry(Long id, Long replayIssueId, String issueKey, String operationType,
                                   LocalDateTime operationAt, String operatorUsername, String operatorRealName,
                                   ReplayIssueStatus issueStatus, String issueType, String initialAnalysis,
                                   String finalSolution, String cooperationPersonUsername,
                                   String cooperationPersonRealName, LocalDate importDate, String sourceSheet,
                                   Integer sourceRow, String beforeSnapshot, String afterSnapshot,
                                   String incomingSnapshot) {
        this(id, replayIssueId, issueKey, operationType, operationAt, operatorUsername, operatorRealName,
                issueStatus, issueType, initialAnalysis, finalSolution, cooperationPersonUsername,
                cooperationPersonRealName, importDate, sourceSheet, sourceRow, beforeSnapshot, afterSnapshot,
                incomingSnapshot, null, null, null);
    }
}
