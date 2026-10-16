package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayBaseMetadataSnapshot(
        boolean tableExists,
        String tableName,
        String tableComment,
        List<ReplayBaseColumnOption> columns,
        List<ReplayBaseKeyOption> keyOptions) {

    public ReplayBaseMetadataSnapshot {
        columns = columns == null ? List.of() : List.copyOf(columns);
        keyOptions = keyOptions == null ? List.of() : List.copyOf(keyOptions);
    }

    public ReplayBaseMetadataSnapshot(
            boolean tableExists,
            String tableName,
            String tableComment,
            List<ReplayBaseColumnOption> columns) {
        this(tableExists, tableName, tableComment, columns, ReplayBaseKeyOption.fromPrimaryKeys(columns));
    }
}
