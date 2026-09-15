package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareConfigScriptValidationError(
        String tableName,
        String fieldName,
        String reason) {
}
