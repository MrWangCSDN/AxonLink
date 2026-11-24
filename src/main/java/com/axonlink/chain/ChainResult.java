package com.axonlink.chain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record ChainResult(String schemaVersion, String code, String message, Map<String, Object> data,
                          Meta meta, List<Map<String, Object>> diagnostics) {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Meta(String requestId, String snapshotAt, String nextCursor, boolean truncated) {}

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    static ChainResult failed(String requestId, String code, String message) {
        return new ChainResult("1.0", code, message, null, new Meta(requestId, null, null, false), List.of());
    }

    static final class Problem extends RuntimeException {
        final String code;
        Problem(String code, String message) { super(message); this.code = code; }
    }

    static final class Notes {
        private final Map<String, Map<String, Object>> byCause = new LinkedHashMap<>();
        boolean truncated;
        void add(String code, String message, String nodeId) {
            String key = code + "/" + nodeId;
            if (byCause.containsKey(key)) return;
            if (byCause.size() >= 100) {
                byCause.putIfAbsent("more", fields("code", "MORE_DIAGNOSTICS", "message", "诊断仅列出前 100 个位置"));
                return;
            }
            Map<String, Object> issue = fields("code", code, "message", message);
            if (nodeId != null) issue.put("nodeId", nodeId);
            byCause.put(key, issue);
        }
        void limit(String nodeId) {
            truncated = true;
            add("LIMIT_REACHED", "达到静态遍历上限，保留已找到的证据", nodeId);
        }
        List<Map<String, Object>> items() { return new ArrayList<>(byCause.values()); }
    }
}
