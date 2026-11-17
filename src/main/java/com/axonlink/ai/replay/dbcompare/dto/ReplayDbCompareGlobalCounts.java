package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareGlobalCounts(
        long tableCount,
        long fieldCount,
        long customSqlCount) {

    public ReplayDbCompareGlobalCounts(long tableCount, long fieldCount) {
        this(tableCount, fieldCount, 0);
    }
}
