package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareReregisterRequest(
        long version,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        String reason,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbComparePartitionNumDeserializer.class)
        Integer partitionNum,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbCompareSampleLimitDeserializer.class)
        Integer sampleLimit) {

    public ReplayDbCompareReregisterRequest(
        long version,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        String reason,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReplayDbComparePartitionNumDeserializer.class)
        Integer partitionNum) {
        this(version, domainName, groupOwnerEmpNo, fieldNames, reason, whereCondition, compareLimit, partitionNum, null);
    }

    public ReplayDbCompareReregisterRequest(
            long version, String domainName, String groupOwnerEmpNo,
            List<String> fieldNames, String reason,
            ReplayDbCompareConditionTree whereCondition, Long compareLimit) {
        this(version, domainName, groupOwnerEmpNo, fieldNames, reason, whereCondition, compareLimit, null);
    }

    public ReplayDbCompareReregisterRequest {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }

    public ReplayDbCompareReregisterRequest(
            long version,
            String domainName,
            String groupOwnerEmpNo,
            List<String> fieldNames,
            String reason) {
        this(version, domainName, groupOwnerEmpNo, fieldNames, reason, null, null);
    }
}
