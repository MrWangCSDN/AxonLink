package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseKeyOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared key selection and snapshot codec; never silently replaces a saved index. */
public final class ReplayDatabaseComparisonKeys {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ReplayDatabaseComparisonKeys() { }

    public static ReplayDbCompareKeySelection choose(String table, List<ReplayBaseKeyOption> options,
            String requested, ReplayDbCompareKeySelection saved) {
        if (options == null || options.isEmpty()) {
            if (saved != null || requested != null && !requested.isBlank()) {
                throw new IllegalArgumentException("比对键索引已失效：" + (requested == null ? saved.indexName() : requested));
            }
            throw new ReplayBasePrimaryKeyMissingException(table);
        }
        String name = requested == null || requested.isBlank() ? saved == null ? null : saved.indexName() : requested.trim();
        ReplayBaseKeyOption selected = name == null ? options.stream().sorted(
                Comparator.comparing((ReplayBaseKeyOption key) -> !"PRIMARY_KEY".equals(key.indexType()))
                        .thenComparing(ReplayBaseKeyOption::indexName)).findFirst().orElseThrow()
                : options.stream().filter(key -> key.indexName().equals(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("比对键索引已失效：" + name));
        ReplayDbCompareKeySelection result = new ReplayDbCompareKeySelection(selected.indexName(), selected.indexType(), selected.columnNames());
        if ((requested == null || requested.isBlank()) && saved != null && !saved.equals(result)) {
            throw new IllegalArgumentException("比对键索引列或顺序已变更，请重新选择索引：" + name);
        }
        return result;
    }

    public static void requireFields(String table, ReplayDbCompareKeySelection key, List<String> fields) {
        List<String> normalized = fields.stream().map(ReplayDatabaseComparisonKeys::normalize).toList();
        List<String> missing = key.columnNames().stream().filter(name -> !normalized.contains(normalize(name))).toList();
        if (!missing.isEmpty()) {
            if ("PRIMARY_KEY".equals(key.indexType())) throw new ReplayBasePrimaryKeysRequiredException(table, missing);
            throw new IllegalArgumentException("比对字段必须包含索引 " + key.indexName() + " 全部列，缺少：" + String.join("、", missing));
        }
    }

    public static String encode(ReplayDbCompareKeySelection key) {
        if (key == null) return null;
        try { return JSON.writeValueAsString(key); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("比对键快照编码失败", exception); }
    }
    public static ReplayDbCompareKeySelection decode(String value) {
        if (value == null || value.isBlank()) return null;
        try { return JSON.readValue(value, ReplayDbCompareKeySelection.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("比对键快照损坏", exception); }
    }
    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
}
