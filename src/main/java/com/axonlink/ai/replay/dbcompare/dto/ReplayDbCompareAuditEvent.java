package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDateTime;

public record ReplayDbCompareAuditEvent(
        Long id,
        long registrationId,
        String schemaName,
        String tableName,
        ReplayDbCompareAuditOperation operation,
        long registrationVersion,
        int changeCount,
        String reason,
        String operatorEmpNo,
        String operatorUsername,
        String operatorName,
        LocalDateTime operatedAt) {

    public ReplayDbCompareAuditEvent(
            Long id, long registrationId, String schemaName, String tableName,
            ReplayDbCompareAuditOperation operation, long registrationVersion, int changeCount,
            String reason, String operatorEmpNo, String operatorName, LocalDateTime operatedAt) {
        this(id, registrationId, schemaName, tableName, operation, registrationVersion, changeCount,
                reason, operatorEmpNo, null, operatorName, operatedAt);
    }
}
