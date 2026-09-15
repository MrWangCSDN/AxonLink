package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareVersionGateError(
        String schemaName,
        String tableName,
        String tableComment,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerName,
        ReplayDbCompareMetadataStatus status,
        List<String> missingFieldNames,
        String reason) {

    public ReplayDbCompareVersionGateError {
        missingFieldNames = missingFieldNames == null ? List.of() : List.copyOf(missingFieldNames);
    }
}
