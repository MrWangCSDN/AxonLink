package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;
import java.util.Comparator;

public record ReplayBaseValidatedTable(
        String schemaName,
        String tableName,
        String tableComment,
        List<ReplayBaseColumnOption> columns,
        List<ReplayBaseColumnOption> currentPrimaryKeys,
        List<ReplayBaseColumnOption> allColumns) {

    public ReplayBaseValidatedTable {
        columns = List.copyOf(columns);
        currentPrimaryKeys = List.copyOf(currentPrimaryKeys);
        allColumns = List.copyOf(allColumns);
    }

    public ReplayBaseValidatedTable(
            String schemaName,
            String tableName,
            String tableComment,
            List<ReplayBaseColumnOption> columns,
            List<ReplayBaseColumnOption> currentPrimaryKeys) {
        this(schemaName, tableName, tableComment, columns, currentPrimaryKeys, columns);
    }

    public ReplayBaseValidatedTable(
            String schemaName,
            String tableName,
            String tableComment,
            List<ReplayBaseColumnOption> columns) {
        this(schemaName, tableName, tableComment, columns,
                columns.stream()
                        .filter(ReplayBaseColumnOption::primaryKey)
                        .sorted(Comparator.comparing(ReplayBaseColumnOption::primaryKeyOrder))
                        .toList(), columns);
    }
}
