package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareSaveRequest(
        String tableName,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        Long version,
        boolean deleteWhenNoFields,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbComparePartitionNumDeserializer.class)
        Integer partitionNum,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbCompareSampleLimitDeserializer.class)
        Integer sampleLimit,
        String comparisonKeyName,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbCompareMaxPartitionNumDeserializer.class)
        Integer maxPartitionNum,
        String configurationName,
        Boolean createAdditionalConfiguration) {
    public ReplayDbCompareSaveRequest(
        String tableName,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        Long version,
        boolean deleteWhenNoFields,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbComparePartitionNumDeserializer.class)
        Integer partitionNum,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbCompareSampleLimitDeserializer.class)
        Integer sampleLimit,
        String comparisonKeyName,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbCompareMaxPartitionNumDeserializer.class)
        Integer maxPartitionNum) {
        this(tableName, domainName, groupOwnerEmpNo, fieldNames, version, deleteWhenNoFields, whereCondition, compareLimit, partitionNum, sampleLimit, comparisonKeyName, maxPartitionNum, null, null);
    }


    public ReplayDbCompareSaveRequest(
        String tableName,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        Long version,
        boolean deleteWhenNoFields,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        Integer partitionNum,
        Integer sampleLimit,
        String comparisonKeyName) {
        this(tableName, domainName, groupOwnerEmpNo, fieldNames, version, deleteWhenNoFields, whereCondition, compareLimit, partitionNum, sampleLimit, comparisonKeyName, null);
    }


    public ReplayDbCompareSaveRequest(String tableName,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        Long version,
        boolean deleteWhenNoFields,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        Integer partitionNum,
        Integer sampleLimit) {
        this(tableName, domainName, groupOwnerEmpNo, fieldNames, version, deleteWhenNoFields, whereCondition, compareLimit, partitionNum, sampleLimit, null);
    }

    public ReplayDbCompareSaveRequest(
        String tableName,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        Long version,
        boolean deleteWhenNoFields,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbComparePartitionNumDeserializer.class)
        Integer partitionNum) {
        this(tableName, domainName, groupOwnerEmpNo, fieldNames, version, deleteWhenNoFields, whereCondition, compareLimit, partitionNum, null);
    }

    public ReplayDbCompareSaveRequest(
            String tableName, String domainName, String groupOwnerEmpNo,
            List<String> fieldNames, Long version, boolean deleteWhenNoFields,
            ReplayDbCompareConditionTree whereCondition, Long compareLimit) {
        this(tableName, domainName, groupOwnerEmpNo, fieldNames, version,
                deleteWhenNoFields, whereCondition, compareLimit, null);
    }

    public ReplayDbCompareSaveRequest {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }

    public ReplayDbCompareSaveRequest(
            String tableName,
            String domainName,
            String groupOwnerEmpNo,
            List<String> fieldNames,
            Long version,
            boolean deleteWhenNoFields) {
        this(tableName, domainName, groupOwnerEmpNo, fieldNames, version,
                deleteWhenNoFields, null, null);
    }
}
