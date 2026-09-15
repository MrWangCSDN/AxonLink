package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareHeaderFilterOption(
        String value,
        String label,
        long count,
        ReplayDbCompareMetadataStatus metadataStatus) {

    public ReplayDbCompareHeaderFilterOption(String value, String label, long count) {
        this(value, label, count, null);
    }
}
