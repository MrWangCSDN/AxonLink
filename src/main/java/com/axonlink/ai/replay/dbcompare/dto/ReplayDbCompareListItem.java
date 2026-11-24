package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql,
        int maxPartitionNum) {

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
        List<String> orderingPrimaryKeyNames,
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition, whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames, partitionNum, sampleLimit, comparisonKey, customSql, 64);
    }


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
            List<String> orderingPrimaryKeyNames,
            int partitionNum,
            int sampleLimit,
            ReplayDbCompareKeySelection comparisonKey) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition,
                whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames,
                partitionNum, sampleLimit, comparisonKey, null);
    }

    public ReplayDbCompareListItem(long id,
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
        int partitionNum,
        int sampleLimit) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition, whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames, partitionNum, sampleLimit, null);
    }

    public ReplayDbCompareListItem withComparisonKey(ReplayDbCompareKeySelection comparisonKey) {
        return new ReplayDbCompareListItem(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition,
                whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames,
                partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum);
    }

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
        List<String> orderingPrimaryKeyNames,
        int partitionNum) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition, whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames, partitionNum, 100);
    }

    public ReplayDbCompareListItem withSampleLimit(int sampleLimit) {
        return new ReplayDbCompareListItem(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition,
                whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames,
                partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum);
    }

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
        return new ReplayDbCompareListItem(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition,
                whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames,
                partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum);
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

    public ReplayDbCompareListItem withCustomSql(ReplayDbCompareCustomSql customSql) {
        return new ReplayDbCompareListItem(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition,
                whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames,
                partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum);
    }

    @JsonProperty("registrationType")
    public String registrationType() {
        return customSql == null ? "TABLE" : "CUSTOM_SQL";
    }

    @JsonProperty("comparisonCode")
    public String comparisonCode() {
        return tableName;
    }

    @JsonProperty("sourceTableName")
    public String sourceTableName() {
        return customSql == null ? tableName : customSql.sourceTableName();
    }

    public ReplayDbCompareListItem withMaxPartitionNum(int maxPartitionNum) {
        return new ReplayDbCompareListItem(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, whereCondition, whereConditionConfigured, compareLimit, metadataValidation, primaryKeyNames, orderingPrimaryKeyNames, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum);
    }
}
