package com.axonlink.ai.replay.dbcompare.dto;

import java.util.Comparator;
import java.util.List;

public record ReplayBaseKeyOption(String indexName, String indexType, List<String> columnNames) {

    public ReplayBaseKeyOption {
        columnNames = columnNames == null ? List.of() : List.copyOf(columnNames);
    }

    static List<ReplayBaseKeyOption> fromPrimaryKeys(List<ReplayBaseColumnOption> columns) {
        if (columns == null) {
            return List.of();
        }
        List<String> names = columns.stream()
                .filter(ReplayBaseColumnOption::primaryKey)
                .sorted(Comparator.comparing(ReplayBaseColumnOption::primaryKeyOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ReplayBaseColumnOption::columnName)
                .toList();
        return names.isEmpty() ? List.of()
                : List.of(new ReplayBaseKeyOption("PRIMARY", "PRIMARY_KEY", names));
    }
}
