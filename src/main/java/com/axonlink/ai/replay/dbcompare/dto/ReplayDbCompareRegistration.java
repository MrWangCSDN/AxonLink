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
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerName,
        LocalDate registeredDate,
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
        List<ReplayDbCompareField> fields,
        ReplayDbCompareMetadataValidation metadataValidation) {

    public ReplayDbCompareRegistration {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public ReplayDbCompareRegistration(
            Long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            boolean deleted, String deletedReason, String deletedBy, LocalDateTime deletedAt,
            long version, String createdBy, String createdName, LocalDateTime createdAt,
            String updatedBy, String updatedName, LocalDateTime updatedAt,
            List<ReplayDbCompareField> fields) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted,
                deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt,
                updatedBy, updatedName, updatedAt, fields, null);
    }

    public ReplayDbCompareRegistration(
            Long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserName, String groupOwnerEmpNo, String groupOwnerName,
            LocalDate registeredDate, boolean deleted, String deletedReason, String deletedBy,
            LocalDateTime deletedAt, long version, String createdBy, String createdName,
            LocalDateTime createdAt, String updatedBy, String updatedName, LocalDateTime updatedAt,
            List<ReplayDbCompareField> fields) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, null, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy,
                deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields);
    }
}
