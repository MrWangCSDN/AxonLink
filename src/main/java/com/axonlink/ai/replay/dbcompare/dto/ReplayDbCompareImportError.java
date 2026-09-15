package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareImportError(
        String sheetName,
        Integer rowNumber,
        String tableName,
        String fieldName,
        String reviserInput,
        String reason) {
}
