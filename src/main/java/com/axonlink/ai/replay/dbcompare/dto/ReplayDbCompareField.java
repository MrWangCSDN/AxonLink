package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareField(
        String columnName,
        String columnComment,
        int ordinalPosition,
        boolean primaryKey,
        int comparisonOrder,
        Boolean existsInBase) {

    public ReplayDbCompareField(
            String columnName,
            String columnComment,
            int ordinalPosition,
            int comparisonOrder) {
        this(columnName, columnComment, ordinalPosition, false, comparisonOrder, null);
    }

    public ReplayDbCompareField(
            String columnName,
            String columnComment,
            int ordinalPosition,
            boolean primaryKey,
            int comparisonOrder) {
        this(columnName, columnComment, ordinalPosition, primaryKey, comparisonOrder, null);
    }

    public String displayName() {
        return columnComment == null || columnComment.isBlank()
                ? columnName
                : columnName + "(" + columnComment + ")";
    }
}
