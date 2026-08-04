package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** One parsed replay issue row and its derived sheet metadata. */
public record ReplayIssueRow(
        Long id,
        String sourceSheet,
        String groupName,
        boolean sandbox,
        int rowOrder,
        String domain,
        String sequenceNo,
        String batchNo,
        String transactionCode,
        String transactionName,
        String issueLevel,
        String registeredDate,
        String fieldName,
        String issueDescription,
        String transactionOwner,
        String issueType,
        String initialAnalysis,
        String finalSolution,
        String resolvedDate,
        String cooperationGroup,
        String resolver,
        String serialNo,
        String dataRepairDate,
        String remark,
        String affectedTransactionCount,
        String issueId,
        String issueKey,
        String historicalOccurrenceCount,
        String firstOccurrenceDate,
        String lastOccurrenceDate,
        LocalDateTime importedAt) {
}
