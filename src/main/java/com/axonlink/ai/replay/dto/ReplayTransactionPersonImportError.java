package com.axonlink.ai.replay.dto;

public record ReplayTransactionPersonImportError(
        int rowNumber,
        String column,
        String value,
        String reason) {
}
