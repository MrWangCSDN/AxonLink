package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareSqlColumn(
        String columnName,
        String columnLabel,
        String dataType,
        int ordinalPosition) {
}
