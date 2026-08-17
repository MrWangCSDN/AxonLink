package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** One successfully completed formal replay issue import batch. */
public record ReplayImportRound(
        Long id,
        String roundCode,
        LocalDateTime importedAt,
        String operatorUsername,
        String operatorRealName,
        int inputRows,
        int createdRows,
        int updatedRows,
        int ignoredRows,
        int autoRepairedRows) {
}
