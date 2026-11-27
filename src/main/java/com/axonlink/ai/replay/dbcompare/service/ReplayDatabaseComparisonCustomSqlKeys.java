package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseKeyOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selects complete source indexes using only canonical result field names. */
final class ReplayDatabaseComparisonCustomSqlKeys {
    private ReplayDatabaseComparisonCustomSqlKeys() { }

    static List<ReplayBaseKeyOption> availableKeys(List<ReplayDbCompareSqlColumn> resultColumns,
            List<ReplayBaseKeyOption> sourceKeys) {
        Set<String> resultNames = new HashSet<>();
        for (ReplayDbCompareSqlColumn column : resultColumns) {
            String name = canonicalName(column.columnName());
            if (name != null) resultNames.add(name);
        }
        List<ReplayBaseKeyOption> available = new ArrayList<>();
        for (ReplayBaseKeyOption key : sourceKeys) {
            List<String> names = canonicalGroup(key.columnNames());
            if (!names.isEmpty() && resultNames.containsAll(names)) {
                available.add(new ReplayBaseKeyOption(key.indexName(), key.indexType(), names));
            }
        }
        return List.copyOf(available);
    }

    static boolean matchesSourceKey(List<ReplayBaseKeyOption> availableKeys, List<String> selectedResultKeys) {
        List<String> selected = canonicalGroup(selectedResultKeys);
        if (selected.isEmpty()) return false;
        Set<String> selectedNames = new HashSet<>(selected);
        return availableKeys.stream().map(key -> canonicalGroup(key.columnNames()))
                .anyMatch(names -> !names.isEmpty() && selectedNames.equals(new HashSet<>(names)));
    }

    private static List<String> canonicalGroup(List<String> columnNames) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : columnNames) {
            String name = canonicalName(value);
            if (name == null || !seen.add(name)) return List.of();
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static String canonicalName(String value) {
        try {
            return ReplayDatabaseComparisonCustomSqlAnalyzer.canonicalColumnName(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
