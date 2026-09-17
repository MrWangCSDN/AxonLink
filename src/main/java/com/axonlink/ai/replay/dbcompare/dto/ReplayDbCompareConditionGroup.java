package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareConditionGroup(
        ReplayDbCompareConditionConnector connector,
        List<ReplayDbCompareCondition> conditions) {

    public ReplayDbCompareConditionGroup {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
}
