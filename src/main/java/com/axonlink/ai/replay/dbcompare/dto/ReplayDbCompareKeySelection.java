package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

/** Ordered, immutable snapshot of the complete index used to match comparison rows. */
public record ReplayDbCompareKeySelection(String indexName, String indexType, List<String> columnNames) {
    public ReplayDbCompareKeySelection {
        columnNames = columnNames == null ? List.of() : List.copyOf(columnNames);
    }
}
