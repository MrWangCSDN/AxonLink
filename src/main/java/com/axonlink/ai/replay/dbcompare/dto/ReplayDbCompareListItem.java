package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareListItem(
        long id,
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
        long version,
        int fieldCount,
        List<String> fieldPreview,
        ReplayDbCompareConditionTree whereCondition,
        boolean whereConditionConfigured,
        Long compareLimit,
        ReplayDbCompareMetadataValidation metadataValidation,
        List<String> primaryKeyNames,
        List<String> orderingPrimaryKeyNames,
        int partitionNum) {

    public ReplayDbCompareListItem(
        long id,
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
        long version,
        int fieldCount,
        List<String> fieldPreview,
        ReplayDbCompareConditionTree whereCondition,
        boolean whereConditionConfigured,
        Long compareLimit,
        ReplayDbCompareMetadataValidation metadataValidation,
        List<String> primaryKeyNames,
        List<String> orderingPrimaryKeyNames) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition,
                whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames,
                orderingPrimaryKeyNames, 1);
    }

    public ReplayDbCompareListItem withPartitionNum(int partitionNum) {
        return new ReplayDbCompareListItem(id, schemaName, tableName, tableComment, domainName, reviserEmpNo,
                reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount,
                fieldPreview, whereCondition, whereConditionConfigured, compareLimit, metadataValidation,
                primaryKeyNames, orderingPrimaryKeyNames, partitionNum);
    }

    public ReplayDbCompareListItem {
        fieldPreview = fieldPreview == null ? List.of() : List.copyOf(fieldPreview);
        primaryKeyNames = primaryKeyNames == null ? List.of() : List.copyOf(primaryKeyNames);
        orderingPrimaryKeyNames = orderingPrimaryKeyNames == null
                ? List.of() : List.copyOf(orderingPrimaryKeyNames);
    }

    public ReplayDbCompareListItem(
            long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            long version, int fieldCount, List<String> fieldPreview) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version,
                fieldCount, fieldPreview, null, false, null, null, List.of(), List.of());
    }

    public ReplayDbCompareListItem(
            long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserName, String groupOwnerEmpNo, String groupOwnerName,
            LocalDate registeredDate, long version, int fieldCount, List<String> fieldPreview) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, null, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, null);
    }

    public ReplayDbCompareListItem(
            long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            long version, int fieldCount, List<String> fieldPreview,
            ReplayDbCompareMetadataValidation metadataValidation) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version,
                fieldCount, fieldPreview, null, false, null, metadataValidation, List.of(), List.of());
    }

    public ReplayDbCompareListItem(
            long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            long version, int fieldCount, List<String> fieldPreview,
            ReplayDbCompareConditionTree whereCondition, boolean whereConditionConfigured,
            Long compareLimit, ReplayDbCompareMetadataValidation metadataValidation,
            List<String> primaryKeyNames) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version,
                fieldCount, fieldPreview, whereCondition, whereConditionConfigured, compareLimit,
                metadataValidation, primaryKeyNames, List.of());
    }
}
