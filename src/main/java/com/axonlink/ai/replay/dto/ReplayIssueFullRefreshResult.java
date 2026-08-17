package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Summary returned after rebuilding replay issues from a date-batch workbook. */
public record ReplayIssueFullRefreshResult(
        int totalRows,
        Map<String, Integer> rowsBySheet,
        int generatedIdentityRows,
        int sandboxRows,
        int nonSandboxRows,
        LocalDateTime importedAt,
        String coverageRound) {

    public ReplayIssueFullRefreshResult {
        rowsBySheet = Collections.unmodifiableMap(new LinkedHashMap<>(rowsBySheet));
    }

    public ReplayIssueFullRefreshResult(int totalRows, Map<String, Integer> rowsBySheet,
                                        int generatedIdentityRows, int sandboxRows, int nonSandboxRows,
                                        LocalDateTime importedAt) {
        this(totalRows, rowsBySheet, generatedIdentityRows, sandboxRows, nonSandboxRows, importedAt, null);
    }
}
