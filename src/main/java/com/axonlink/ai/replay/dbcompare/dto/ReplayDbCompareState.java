package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareState(
        String tableComment,
        String domainName,
        String groupOwnerEmpNo,
        String groupOwnerName,
        LocalDate registeredDate,
        boolean deleted,
        List<ReplayDbCompareField> fields) {

    public ReplayDbCompareState {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
