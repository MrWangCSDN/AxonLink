package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Retain JSON numeric types so Jackson cannot silently coerce versions or counts. */
public record ReplayDbComparePartitioningRequest(JsonNode version, JsonNode partitionNum) {
    public Long validatedVersion() {
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
            throw new IllegalArgumentException("登记版本必须为有效的整数");
        }
        return version.longValue();
    }

    public Integer validatedPartitionNum() {
        if (partitionNum == null || !partitionNum.isIntegralNumber() || !partitionNum.canConvertToInt()
                || partitionNum.intValue() < 1 || partitionNum.intValue() > 256) {
            throw new IllegalArgumentException("读取分区数必须为 1 到 256 的整数");
        }
        return partitionNum.intValue();
    }
}
