package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareImportResult(
        boolean success,
        int tableCount,
        int createdCount,
        int updatedCount,
        int unchangedCount,
        List<ReplayDbCompareImportError> errors) {

    public ReplayDbCompareImportResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
