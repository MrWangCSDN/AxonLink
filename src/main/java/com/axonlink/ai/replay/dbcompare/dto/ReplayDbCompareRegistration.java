package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ReplayDbCompareRegistration(
        Long id,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String ownerEmpNo,
        String ownerName,
        String groupName,
        LocalDate registeredDate,
        String remark,
        boolean deleted,
        String deletedReason,
        String deletedBy,
        LocalDateTime deletedAt,
        long version,
        String createdBy,
        String createdName,
        LocalDateTime createdAt,
        String updatedBy,
        String updatedName,
        LocalDateTime updatedAt,
        List<ReplayDbCompareField> fields) {

    public ReplayDbCompareRegistration {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
