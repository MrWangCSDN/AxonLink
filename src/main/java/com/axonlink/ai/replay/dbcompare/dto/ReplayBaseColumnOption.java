package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayBaseColumnOption(
        String columnName,
        String columnComment,
        String dataType,
        int ordinalPosition,
        boolean primaryKey,
        Integer primaryKeyOrder) {

    public ReplayBaseColumnOption(
            String columnName,
            String columnComment,
            int ordinalPosition,
            boolean primaryKey,
            Integer primaryKeyOrder) {
        this(columnName, columnComment, null, ordinalPosition, primaryKey, primaryKeyOrder);
    }

    public ReplayBaseColumnOption(
            String columnName,
            String columnComment,
            int ordinalPosition,
            boolean primaryKey) {
        this(columnName, columnComment, null, ordinalPosition,
                primaryKey, primaryKey ? ordinalPosition : null);
    }
}
