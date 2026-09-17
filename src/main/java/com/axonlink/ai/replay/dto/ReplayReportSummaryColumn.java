package com.axonlink.ai.replay.dto;

public record ReplayReportSummaryColumn(
        String key,
        String label,
        String groupLabel,
        ValueType valueType,
        int order) {

    public enum ValueType {
        TEXT,
        INTEGER,
        PERCENT
    }
}
