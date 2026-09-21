package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Retain JSON numeric type so Jackson cannot silently truncate fractional counts. */
public record ReplayDbComparePartitioningRequest(Long version, JsonNode partitionNum) {
    public Integer validatedPartitionNum() {
        if (partitionNum == null || !partitionNum.isIntegralNumber() || !partitionNum.canConvertToInt()
                || partitionNum.intValue() < 1 || partitionNum.intValue() > 256) {
            throw new IllegalArgumentException("读取分区数必须为 1 到 256 的整数");
        }
        return partitionNum.intValue();
    }
}
