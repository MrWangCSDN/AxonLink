package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDateTime;

public record ReplayDbCompareConfigScriptStatus(
        boolean generated,
        String fileName,
        Long scriptSize,
        String sha256,
        String generatedBy,
        String generatedName,
        LocalDateTime generatedAt) {
}
