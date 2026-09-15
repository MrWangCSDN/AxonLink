package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDateTime;

public record ReplayDbCompareVersionSummary(
        String versionNo,
        String generatedBy,
        String generatedName,
        LocalDateTime generatedAt,
        int tableCount,
        int fieldCount,
        boolean latest) {
}
