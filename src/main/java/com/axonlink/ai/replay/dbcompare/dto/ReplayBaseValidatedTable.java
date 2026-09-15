package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;
import java.util.Comparator;

public record ReplayBaseValidatedTable(
        String schemaName,
        String tableName,
        String tableComment,
        List<ReplayBaseColumnOption> columns,
        List<ReplayBaseColumnOption> currentPrimaryKeys) {

    public ReplayBaseValidatedTable {
        columns = List.copyOf(columns);
        currentPrimaryKeys = List.copyOf(currentPrimaryKeys);
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
                        .toList());
    }
}
