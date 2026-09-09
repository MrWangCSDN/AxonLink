package com.axonlink.ai.replay.dto;

import java.time.LocalDate;

public record ReplayCoverageDetailRow(
        String batchNo,
        String transactionCode,
        String transactionDescription,
        String businessDomain,
        String sCode,
        String relatedCode,
        String replayRequired,
        LocalDate latestTransactionDate,
        Long sentTransactionCount,
        String coverageStatus,
        String unsentReason,
        String developer,
        String bankOwner,
        int sourceRow,
        String rawJson) {
}
