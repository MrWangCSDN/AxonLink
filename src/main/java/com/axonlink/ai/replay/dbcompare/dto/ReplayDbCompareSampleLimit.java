package com.axonlink.ai.replay.dbcompare.dto;

/** Limit on retained difference details within the comparison's existing source scope. */
public final class ReplayDbCompareSampleLimit {
    public static final int ALL = -1;
    public static final String RANGE_ERROR = "样本数必须为 -1（全量）或 0 到 10000 的整数";

    private ReplayDbCompareSampleLimit() {
    }

    public static boolean isValid(int value) {
        return value >= ALL && value <= 10_000;
    }
}
