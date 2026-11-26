package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ReplayDbCompareVersionTableItem(
        long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields,
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql,
        int maxPartitionNum,
        @com.fasterxml.jackson.annotation.JsonIgnore ReplayDbCompareConfigurationIdentity configurationIdentity) {
    public ReplayDbCompareVersionTableItem(
        long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields,
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql,
        int maxPartitionNum) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName, whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum, null);
    }


    public ReplayDbCompareVersionTableItem(
        long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields,
        int partitionNum,
        int sampleLimit,
        ReplayDbCompareKeySelection comparisonKey,
        ReplayDbCompareCustomSql customSql) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName, whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit, comparisonKey, customSql, 64);
    }


    public ReplayDbCompareVersionTableItem(
            long sourceRegistrationId,
            long sourceRegistrationVersion,
            String schemaName,
            String tableName,
            String tableComment,
            String domainName,
            String reviserEmpNo,
            String reviserUsername,
            String reviserName,
            String groupOwnerEmpNo,
            String groupOwnerUsername,
            String groupOwnerName,
            ReplayDbCompareConditionTree whereCondition,
            String whereSql,
            Long compareLimit,
            LocalDate registeredDate,
            int fieldCount,
            List<ReplayDbCompareVersionField> fields,
            int partitionNum,
            int sampleLimit,
            ReplayDbCompareKeySelection comparisonKey) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName,
                reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName,
                whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit,
                comparisonKey, null);
    }

    public ReplayDbCompareVersionTableItem(long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields,
        int partitionNum,
        int sampleLimit) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName, whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit, null);
    }

    public ReplayDbCompareVersionTableItem withComparisonKey(ReplayDbCompareKeySelection comparisonKey) {
        return new ReplayDbCompareVersionTableItem(
                sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName,
                reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName,
                whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit,
                comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    public ReplayDbCompareVersionTableItem(
        long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields,
        int partitionNum) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName, whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, 100);
    }

    public ReplayDbCompareVersionTableItem withSampleLimit(int sampleLimit) {
        return new ReplayDbCompareVersionTableItem(
                sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName,
                reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName,
                whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit,
                comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    public ReplayDbCompareVersionTableItem(
        long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName,
                reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName,
                whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, 1);
    }

    public ReplayDbCompareVersionTableItem withPartitionNum(int partitionNum) {
        return new ReplayDbCompareVersionTableItem(
                sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName,
                reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName,
                whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit,
                comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    public ReplayDbCompareVersionTableItem {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public ReplayDbCompareVersionTableItem(
            long sourceRegistrationId, long sourceRegistrationVersion, String schemaName,
            String tableName, String tableComment, String domainName, String reviserEmpNo,
            String reviserUsername, String reviserName, String groupOwnerEmpNo,
            String groupOwnerName, LocalDate registeredDate, int fieldCount,
            List<ReplayDbCompareVersionField> fields) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment,
                domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, null,
                groupOwnerName, null, null, null, registeredDate, fieldCount, fields);
    }

    public ReplayDbCompareVersionTableItem(
            long sourceRegistrationId, long sourceRegistrationVersion, String schemaName,
            String tableName, String tableComment, String domainName, String reviserEmpNo,
            String reviserUsername, String reviserName, String groupOwnerEmpNo,
            String groupOwnerUsername, String groupOwnerName, LocalDate registeredDate,
            int fieldCount, List<ReplayDbCompareVersionField> fields) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment,
                domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo,
                groupOwnerUsername, groupOwnerName, null, null, null,
                registeredDate, fieldCount, fields);
    }

    public ReplayDbCompareVersionTableItem withCustomSql(ReplayDbCompareCustomSql customSql) {
        return new ReplayDbCompareVersionTableItem(
                sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName,
                reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName,
                whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit,
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

    public ReplayDbCompareVersionTableItem withMaxPartitionNum(int maxPartitionNum) {
        return new ReplayDbCompareVersionTableItem(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName, whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum).withConfigurationIdentity(configurationIdentity);
    }

    @JsonProperty("configurationName")
    public String configurationName() {
        return configurationIdentity == null || configurationIdentity.configurationName().isBlank()
                ? "默认配置" : configurationIdentity.configurationName();
    }

    public ReplayDbCompareVersionTableItem withConfigurationIdentity(ReplayDbCompareConfigurationIdentity identity) {
        return new ReplayDbCompareVersionTableItem(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName, whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, partitionNum, sampleLimit, comparisonKey, customSql, maxPartitionNum, identity);
    }
}
