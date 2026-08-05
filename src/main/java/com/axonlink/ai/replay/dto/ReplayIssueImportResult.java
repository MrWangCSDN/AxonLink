package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;
import java.util.Map;

/** Summary of a completed replay issue workbook import. */
public record ReplayIssueImportResult(
        int totalRows,
        Map<String, Integer> rowsBySheet,
        int sandboxRows,
        int nonSandboxRows,
        LocalDateTime importedAt,
        int createdRows,
        int updatedRows,
        int ignoredRows,
        int autoRepairedRows,
        int rejectedRows) {

    public ReplayIssueImportResult(int totalRows, Map<String, Integer> rowsBySheet,
                                   int sandboxRows, int nonSandboxRows, LocalDateTime importedAt) {
        this(totalRows, rowsBySheet, sandboxRows, nonSandboxRows, importedAt,
                totalRows, 0, 0, 0, 0);
    }
}
