package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        List<String> orderingPrimaryKeyNames,
        @JsonIgnore String compiledWhereSql,
        ReplayDbCompareMetadataValidation metadataValidation,
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql,
        int maxPartitionNum,
        @com.fasterxml.jackson.annotation.JsonIgnore ReplayDbCompareConfigurationIdentity configurationIdentity) {
    public ReplayDbCompareRegistration(
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
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        List<String> orderingPrimaryKeyNames,
        String compiledWhereSql,
        ReplayDbCompareMetadataValidation metadataValidation,
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql,
        int maxPartitionNum) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum, null);
    }


    public ReplayDbCompareRegistration(
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
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        List<String> orderingPrimaryKeyNames,
        String compiledWhereSql,
        ReplayDbCompareMetadataValidation metadataValidation,
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit, comparisonKey, customSql, 64);
    }


    public ReplayDbCompareRegistration(
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
            ReplayDbCompareConditionTree whereCondition,
            Long compareLimit,
            List<String> orderingPrimaryKeyNames,
            String compiledWhereSql,
            ReplayDbCompareMetadataValidation metadataValidation,
            int partitionNum,
            int sampleLimit,
            ReplayDbCompareKeySelection comparisonKey) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version,
                createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition,
                compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit,
                comparisonKey, null);
    }

    public ReplayDbCompareRegistration(Long id,
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
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        List<String> orderingPrimaryKeyNames,
        String compiledWhereSql,
        ReplayDbCompareMetadataValidation metadataValidation,
        int partitionNum,
        int sampleLimit) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit, null);
    }

    public ReplayDbCompareRegistration withComparisonKey(ReplayDbCompareKeySelection comparisonKey) {
        return new ReplayDbCompareRegistration(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version,
                createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition,
                compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit,
                comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    public ReplayDbCompareRegistration(
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
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        List<String> orderingPrimaryKeyNames,
        String compiledWhereSql,
        ReplayDbCompareMetadataValidation metadataValidation,
        int partitionNum) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, 100);
    }

    public ReplayDbCompareRegistration withSampleLimit(int sampleLimit) {
        return new ReplayDbCompareRegistration(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version,
                createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition,
                compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit,
                comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    public ReplayDbCompareRegistration(
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
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        List<String> orderingPrimaryKeyNames,
        String compiledWhereSql,
        ReplayDbCompareMetadataValidation metadataValidation) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt,
                version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields,
                whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, 1);
    }

    public ReplayDbCompareRegistration withPartitionNum(int partitionNum) {
        return new ReplayDbCompareRegistration(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version,
                createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition,
                compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit,
                comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    public ReplayDbCompareRegistration {
        fields = fields == null ? List.of() : List.copyOf(fields);
        orderingPrimaryKeyNames = orderingPrimaryKeyNames == null
                ? List.of() : List.copyOf(orderingPrimaryKeyNames);
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
                updatedBy, updatedName, updatedAt, fields, null, null, List.of(), null, null);
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

    public ReplayDbCompareRegistration(
            Long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            boolean deleted, String deletedReason, String deletedBy, LocalDateTime deletedAt,
            long version, String createdBy, String createdName, LocalDateTime createdAt,
            String updatedBy, String updatedName, LocalDateTime updatedAt,
            List<ReplayDbCompareField> fields,
            ReplayDbCompareMetadataValidation metadataValidation) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted,
                deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt,
                updatedBy, updatedName, updatedAt, fields, null, null, List.of(), null, metadataValidation);
    }

    public ReplayDbCompareRegistration(
            Long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            boolean deleted, String deletedReason, String deletedBy, LocalDateTime deletedAt,
            long version, String createdBy, String createdName, LocalDateTime createdAt,
            String updatedBy, String updatedName, LocalDateTime updatedAt,
            List<ReplayDbCompareField> fields, ReplayDbCompareConditionTree whereCondition,
            Long compareLimit, ReplayDbCompareMetadataValidation metadataValidation) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted,
                deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt,
                updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit,
                List.of(), null, metadataValidation);
    }

    public ReplayDbCompareRegistration(
            Long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            boolean deleted, String deletedReason, String deletedBy, LocalDateTime deletedAt,
            long version, String createdBy, String createdName, LocalDateTime createdAt,
            String updatedBy, String updatedName, LocalDateTime updatedAt,
            List<ReplayDbCompareField> fields, ReplayDbCompareConditionTree whereCondition,
            Long compareLimit, List<String> orderingPrimaryKeyNames,
            ReplayDbCompareMetadataValidation metadataValidation) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted,
                deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt,
                updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit,
                orderingPrimaryKeyNames, null, metadataValidation);
    }

    public ReplayDbCompareRegistration(
            Long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            boolean deleted, String deletedReason, String deletedBy, LocalDateTime deletedAt,
            long version, String createdBy, String createdName, LocalDateTime createdAt,
            String updatedBy, String updatedName, LocalDateTime updatedAt,
            List<ReplayDbCompareField> fields, ReplayDbCompareConditionTree whereCondition,
            Long compareLimit, String compiledWhereSql,
            ReplayDbCompareMetadataValidation metadataValidation) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted,
                deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt,
                updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit,
                List.of(), compiledWhereSql, metadataValidation);
    }

    public ReplayDbCompareRegistration withCustomSql(ReplayDbCompareCustomSql customSql) {
        return new ReplayDbCompareRegistration(
                id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version,
                createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition,
                compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit,
                comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
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
        return configurationIdentity != null ? configurationIdentity.sourceTableName() : customSql == null ? tableName : customSql.sourceTableName();
    }

    public ReplayDbCompareRegistration withMaxPartitionNum(int maxPartitionNum) {
        return new ReplayDbCompareRegistration(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    @JsonProperty("configurationName")
    public String configurationName() {
        return configurationIdentity == null || configurationIdentity.configurationName().isBlank()
                ? "默认配置" : configurationIdentity.configurationName();
    }

    public ReplayDbCompareRegistration withConfigurationIdentity(ReplayDbCompareConfigurationIdentity identity) {
        return new ReplayDbCompareRegistration(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum, identity);
    }

    public ReplayDbCompareRegistration withComparisonCode(String code) {
        return new ReplayDbCompareRegistration(id, schemaName, code, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, deleted, deletedReason, deletedBy, deletedAt, version, createdBy, createdName, createdAt, updatedBy, updatedName, updatedAt, fields, whereCondition, compareLimit, orderingPrimaryKeyNames, compiledWhereSql, metadataValidation, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum, configurationIdentity);
    }
}
