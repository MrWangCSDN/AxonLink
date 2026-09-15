package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareReregisterRequest(
        long version,
        String domainName,
        String groupOwnerEmpNo,
        List<String> fieldNames,
        String reason) {

    public ReplayDbCompareReregisterRequest {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }
}
