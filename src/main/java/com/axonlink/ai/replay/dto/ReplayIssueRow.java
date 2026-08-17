package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;
import java.time.LocalDate;

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
        LocalDateTime importedAt,
        ReplayIssueStatus issueStatus,
        LocalDate importDate,
        LocalDate defectRepairDate,
        String cooperationPersonUsername,
        String cooperationPersonRealName,
        String globalSerialNo) {

    /** Compatibility constructor for callers that do not provide the optional global serial number. */
    public ReplayIssueRow(Long id, String sourceSheet, String groupName, boolean sandbox, int rowOrder,
                          String domain, String sequenceNo, String batchNo, String transactionCode,
                          String transactionName, String issueLevel, String registeredDate, String fieldName,
                          String issueDescription, String transactionOwner, String issueType,
                          String initialAnalysis, String finalSolution, String resolvedDate,
                          String cooperationGroup, String resolver, String serialNo, String dataRepairDate,
                          String remark, String affectedTransactionCount, String issueId, String issueKey,
                          String historicalOccurrenceCount, String firstOccurrenceDate,
                          String lastOccurrenceDate, LocalDateTime importedAt, ReplayIssueStatus issueStatus,
                          LocalDate importDate, LocalDate defectRepairDate, String cooperationPersonUsername,
                          String cooperationPersonRealName) {
        this(id, sourceSheet, groupName, sandbox, rowOrder, domain, sequenceNo, batchNo, transactionCode,
                transactionName, issueLevel, registeredDate, fieldName, issueDescription, transactionOwner,
                issueType, initialAnalysis, finalSolution, resolvedDate, cooperationGroup, resolver, serialNo,
                dataRepairDate, remark, affectedTransactionCount, issueId, issueKey, historicalOccurrenceCount,
                firstOccurrenceDate, lastOccurrenceDate, importedAt, issueStatus, importDate, defectRepairDate,
                cooperationPersonUsername, cooperationPersonRealName, null);
    }

    /** Compatibility constructor for rows parsed before lifecycle fields were added. */
    public ReplayIssueRow(Long id, String sourceSheet, String groupName, boolean sandbox, int rowOrder,
                          String domain, String sequenceNo, String batchNo, String transactionCode,
                          String transactionName, String issueLevel, String registeredDate, String fieldName,
                          String issueDescription, String transactionOwner, String issueType,
                          String initialAnalysis, String finalSolution, String resolvedDate,
                          String cooperationGroup, String resolver, String serialNo, String dataRepairDate,
                          String remark, String affectedTransactionCount, String issueId, String issueKey,
                          String historicalOccurrenceCount, String firstOccurrenceDate,
                          String lastOccurrenceDate, LocalDateTime importedAt) {
        this(id, sourceSheet, groupName, sandbox, rowOrder, domain, sequenceNo, batchNo, transactionCode,
                transactionName, issueLevel, registeredDate, fieldName, issueDescription, transactionOwner,
                issueType, initialAnalysis, finalSolution, resolvedDate, cooperationGroup, resolver, serialNo,
                dataRepairDate, remark, affectedTransactionCount, issueId, issueKey,
                historicalOccurrenceCount, firstOccurrenceDate, lastOccurrenceDate, importedAt,
                ReplayIssueStatus.OPEN, importedAt == null ? null : importedAt.toLocalDate(), null, null, null, null);
    }
}
