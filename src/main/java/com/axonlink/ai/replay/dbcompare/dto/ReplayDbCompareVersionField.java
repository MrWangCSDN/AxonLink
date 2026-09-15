package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareVersionField(
        String columnName,
        String columnComment,
        int ordinalPosition,
        boolean primaryKey,
        int comparisonOrder) {

    public String displayName() {
        return columnComment == null || columnComment.isBlank()
                ? columnName
                : columnName + "(" + columnComment + ")";
    }
}
