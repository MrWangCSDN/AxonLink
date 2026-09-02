package com.axonlink.ai.replay.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
        LocalDateTime reviewedAt,
        List<ReplayIssueFieldChange> changes,
        List<ReplayIssueOriginalDataItem> originalData) {

    public ReplayIssueHistoryEntry {
        changes = changes == null ? List.of() : List.copyOf(changes);
        originalData = originalData == null ? List.of() : List.copyOf(originalData);
    }

    public ReplayIssueHistoryEntry(Long id, Long replayIssueId, String issueKey, String operationType,
                                   LocalDateTime operationAt, String operatorUsername, String operatorRealName,
                                   ReplayIssueStatus issueStatus, String issueType, String initialAnalysis,
                                   String finalSolution, String cooperationPersonUsername,
                                   String cooperationPersonRealName, LocalDate importDate, String sourceSheet,
                                   Integer sourceRow, String beforeSnapshot, String afterSnapshot,
                                   String incomingSnapshot, String remark, Long contextRoundId,
                                   String occurrenceBatchName, ReplayIssueReviewStatus reviewStatus,
                                   String reviewerUsername, String reviewerRealName, LocalDateTime reviewedAt) {
        this(id, replayIssueId, issueKey, operationType, operationAt, operatorUsername, operatorRealName,
                issueStatus, issueType, initialAnalysis, finalSolution, cooperationPersonUsername,
                cooperationPersonRealName, importDate, sourceSheet, sourceRow, beforeSnapshot, afterSnapshot,
                incomingSnapshot, remark, contextRoundId, occurrenceBatchName, reviewStatus, reviewerUsername,
                reviewerRealName, reviewedAt, List.of(), List.of());
    }

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
