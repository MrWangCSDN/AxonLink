package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

public record ReplayMessageSequenceIgnoreRow(
        long id, String serviceCode, String category, String messageSequence, String ignoreReason,
        int reviewStatus, LocalDateTime createdAt, LocalDateTime updatedAt,
        String oldTransactionCode, String developer, String bankOwner,
        boolean canReview, String reviewDisabledReason, String domain) {
}
