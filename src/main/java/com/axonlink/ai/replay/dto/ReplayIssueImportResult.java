package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;
import java.util.Map;

/** Summary of a completed replay issue workbook import. */
public record ReplayIssueImportResult(
        int totalRows,
        Map<String, Integer> rowsBySheet,
        int sandboxRows,
        int nonSandboxRows,
        LocalDateTime importedAt) {
}
