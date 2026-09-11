package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayBaseValidatedTable(
        String schemaName,
        String tableName,
        String tableComment,
        List<ReplayBaseColumnOption> columns) {

    public ReplayBaseValidatedTable {
        columns = List.copyOf(columns);
    }
}
