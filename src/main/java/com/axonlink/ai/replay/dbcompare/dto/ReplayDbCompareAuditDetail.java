package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDateTime;

public record ReplayDbCompareAuditDetail(
        Long id,
        long auditEventId,
        int detailOrder,
        ReplayDbCompareChangeType changeType,
        String fieldCode,
        String fieldLabel,
        String beforeValue,
        String afterValue,
        LocalDateTime createdAt) {
}
