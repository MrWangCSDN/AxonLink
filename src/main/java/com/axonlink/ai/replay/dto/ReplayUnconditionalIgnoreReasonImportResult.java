package com.axonlink.ai.replay.dto;

/** 无条件忽略原因导入结果。 */
public record ReplayUnconditionalIgnoreReasonImportResult(
        int totalRows,
        int updatedRows,
        int skippedExistingReasonRows,
        int unmatchedRows,
        int invalidRows) {
}
