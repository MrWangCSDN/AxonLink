package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareCompiledScope(
        ReplayDbCompareConditionTree conditionTree,
        String whereSql,
        Long compareLimit) {
}
