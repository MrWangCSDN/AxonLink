package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

public record ReplayDbCompareCustomSqlSaveRequest(
        String displayName,
        String sourceTableName,
        String sql,
        List<String> fieldNames,
        List<String> resultKeyColumns,
        Long version,
        Long compareLimit,
        @JsonDeserialize(using = ReplayDbComparePartitionNumDeserializer.class) Integer partitionNum,
        @JsonDeserialize(using = ReplayDbCompareSampleLimitDeserializer.class) Integer sampleLimit) {
    public ReplayDbCompareCustomSqlSaveRequest {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
        resultKeyColumns = resultKeyColumns == null ? List.of() : List.copyOf(resultKeyColumns);
    }
}
