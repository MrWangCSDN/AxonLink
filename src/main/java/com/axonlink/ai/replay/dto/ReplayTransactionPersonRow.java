package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

public record ReplayTransactionPersonRow(
        Long id,
        String domain,
        String oldTransactionCode,
        String oldTransactionName,
        String developer,
        String developerUsernames,
        String bankOwner,
        String bankOwnerEmpNos,
        LocalDateTime importedAt) {
}
