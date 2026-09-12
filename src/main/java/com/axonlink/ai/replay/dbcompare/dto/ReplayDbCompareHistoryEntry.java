package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDateTime;

public record ReplayDbCompareHistoryEntry(
        long id,
        long registrationId,
        String operation,
        String beforeSnapshot,
        String afterSnapshot,
        String reason,
        String operatorEmpNo,
        String operatorName,
        LocalDateTime operatedAt) {
}
