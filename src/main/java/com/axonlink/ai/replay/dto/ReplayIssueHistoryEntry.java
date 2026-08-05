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
        String incomingSnapshot) {
}
