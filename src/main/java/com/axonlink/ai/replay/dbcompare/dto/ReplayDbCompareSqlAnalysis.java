package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareSqlAnalysis(
        String sql,
        String schemaName,
        String sourceTableName,
        String sourceTableComment,
        List<ReplayDbCompareSqlColumn> resultColumns,
        List<String> candidateKeyColumns) {

    public ReplayDbCompareSqlAnalysis {
        resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
        candidateKeyColumns = candidateKeyColumns == null ? List.of() : List.copyOf(candidateKeyColumns);
    }
}
