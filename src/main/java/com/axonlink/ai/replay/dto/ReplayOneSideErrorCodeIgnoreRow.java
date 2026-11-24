package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

public record ReplayOneSideErrorCodeIgnoreRow(
        long id, String serviceCode, String category, String ownership, String errorCode, String ignoreReason,
        int reviewStatus, LocalDateTime createdAt, LocalDateTime updatedAt,
        String oldTransactionCode, String developer, String bankOwner,
        boolean canReview, String reviewDisabledReason) {
}
