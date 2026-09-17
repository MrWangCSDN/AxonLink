package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareCondition(
        String columnName,
        ReplayDbCompareConditionOperator operator,
        List<String> values) {

    public ReplayDbCompareCondition {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
