package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareSaveRequest(
        String tableName,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        Long version,
        boolean deleteWhenNoFields) {

    public ReplayDbCompareSaveRequest {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }
}
