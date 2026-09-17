package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareConditionTree(
        ReplayDbCompareConditionConnector connector,
        List<ReplayDbCompareConditionGroup> groups) {

    public ReplayDbCompareConditionTree {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
