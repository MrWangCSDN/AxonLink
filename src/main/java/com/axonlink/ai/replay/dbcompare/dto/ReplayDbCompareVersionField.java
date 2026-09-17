package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareVersionField(
        String columnName,
        String columnComment,
        int ordinalPosition,
        boolean primaryKey,
        Integer primaryKeyOrder,
        int comparisonOrder) {

    public ReplayDbCompareVersionField(
            String columnName,
            String columnComment,
            int ordinalPosition,
            boolean primaryKey,
            int comparisonOrder) {
        this(columnName, columnComment, ordinalPosition, primaryKey, null, comparisonOrder);
    }

    public String displayName() {
        return columnComment == null || columnComment.isBlank()
                ? columnName
                : columnName + "(" + columnComment + ")";
    }
}
