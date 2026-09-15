package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareAuditDetailDraft(
        ReplayDbCompareChangeType changeType,
        String fieldCode,
        String fieldLabel,
        String beforeValue,
        String afterValue) {
}
