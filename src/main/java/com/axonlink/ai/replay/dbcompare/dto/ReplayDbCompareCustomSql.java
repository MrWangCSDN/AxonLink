package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareCustomSql(
        String sql,
        String sourceTableName,
        List<ReplayDbCompareSqlColumn> resultColumns) {

    public ReplayDbCompareCustomSql {
        resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
    }
}
