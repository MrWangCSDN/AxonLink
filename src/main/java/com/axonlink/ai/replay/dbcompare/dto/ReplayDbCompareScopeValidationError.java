package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareScopeValidationError(
        String path,
        String reason) {
}
