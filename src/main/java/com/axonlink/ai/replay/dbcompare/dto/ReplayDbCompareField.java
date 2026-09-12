package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbCompareField(String columnName, String columnComment, int ordinalPosition) {

    public String displayName() {
        return columnComment == null || columnComment.isBlank()
                ? columnName
                : columnName + "(" + columnComment + ")";
    }
}
