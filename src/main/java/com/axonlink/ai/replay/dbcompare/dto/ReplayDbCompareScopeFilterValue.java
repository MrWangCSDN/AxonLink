package com.axonlink.ai.replay.dbcompare.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public record ReplayDbCompareScopeFilterValue(
        String tableName,
        String conditionJson,
        Long compareLimit,
        List<String> primaryKeyNames) {

    private static final String PREFIX = "__SCOPE__|";

    public ReplayDbCompareScopeFilterValue {
        primaryKeyNames = primaryKeyNames == null ? List.of() : List.copyOf(primaryKeyNames);
    }

    public String encode() {
        return PREFIX + encoded(tableName) + '|' + encoded(conditionJson) + '|'
                + compareLimit + '|' + encoded(String.join(",", primaryKeyNames));
    }

    public static ReplayDbCompareScopeFilterValue decode(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = value.substring(PREFIX.length()).split("\\|", -1);
        if (parts.length != 4) {
            return null;
        }
        String primaryKeys = decoded(parts[3]);
        return new ReplayDbCompareScopeFilterValue(
                decoded(parts[0]), emptyToNull(decoded(parts[1])), Long.valueOf(parts[2]),
                primaryKeys.isEmpty() ? List.of() : List.of(primaryKeys.split(",")));
    }

    private static String encoded(String value) {
        String source = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
