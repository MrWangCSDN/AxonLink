package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareSqlAnalysis(
        String sql,
        String schemaName,
        String sourceTableName,
        String sourceTableComment,
        List<ReplayDbCompareSqlColumn> resultColumns,
        List<String> candidateKeyColumns,
        List<ReplayBaseKeyOption> keyOptions) {

    public ReplayDbCompareSqlAnalysis {
        resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
        candidateKeyColumns = candidateKeyColumns == null ? List.of() : List.copyOf(candidateKeyColumns);
        keyOptions = keyOptions == null ? List.of() : List.copyOf(keyOptions);
    }

    public ReplayDbCompareSqlAnalysis(String sql, String schemaName, String sourceTableName,
            String sourceTableComment, List<ReplayDbCompareSqlColumn> resultColumns, List<String> candidateKeyColumns) {
        this(sql, schemaName, sourceTableName, sourceTableComment, resultColumns, candidateKeyColumns, List.of());
    }
}
