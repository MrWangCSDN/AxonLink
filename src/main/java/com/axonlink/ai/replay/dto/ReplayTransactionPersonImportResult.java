package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayTransactionPersonImportResult(
        boolean imported,
        int totalRows,
        int insertedRows,
        int errorRows,
        List<ReplayTransactionPersonImportError> errors) {
    public ReplayTransactionPersonImportResult {
        errors = List.copyOf(errors);
    }
}
