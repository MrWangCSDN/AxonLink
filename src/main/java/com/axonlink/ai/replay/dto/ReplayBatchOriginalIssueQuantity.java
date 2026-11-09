package com.axonlink.ai.replay.dto;

/** Original quantity evidence owned by one business batch, independent of today's handling conclusion. */
public record ReplayBatchOriginalIssueQuantity(
        long issueId,
        String issueKey,
        Presence presence,
        String transactionCode,
        String issueLevel,
        String fieldName,
        Long affectedTransactionCount) {

    public enum Presence {
        PRESENT, ABSENT, UNKNOWN
    }
}
