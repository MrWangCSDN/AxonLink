package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record ReplayDbCompareField(
        String columnName,
        String columnComment,
        int ordinalPosition,
        boolean primaryKey,
        int comparisonOrder,
        @JsonIgnore Integer primaryKeyOrder,
        Boolean existsInBase) {

    public ReplayDbCompareField(
            String columnName,
            String columnComment,
            int ordinalPosition,
            int comparisonOrder) {
        this(columnName, columnComment, ordinalPosition, false, comparisonOrder, null, null);
    }

    public ReplayDbCompareField(
            String columnName,
            String columnComment,
            int ordinalPosition,
            boolean primaryKey,
            int comparisonOrder) {
        this(columnName, columnComment, ordinalPosition, primaryKey, comparisonOrder, null, null);
    }

    public ReplayDbCompareField(
            String columnName,
            String columnComment,
            int ordinalPosition,
            boolean primaryKey,
            int comparisonOrder,
            Boolean existsInBase) {
        this(columnName, columnComment, ordinalPosition, primaryKey,
                comparisonOrder, null, existsInBase);
    }

    public String displayName() {
        return columnComment == null || columnComment.isBlank()
                ? columnName
                : columnName + "(" + columnComment + ")";
    }
}
