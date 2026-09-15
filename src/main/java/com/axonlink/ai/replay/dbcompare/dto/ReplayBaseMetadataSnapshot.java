package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayBaseMetadataSnapshot(
        boolean tableExists,
        String tableName,
        String tableComment,
        List<ReplayBaseColumnOption> columns) {

    public ReplayBaseMetadataSnapshot {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
