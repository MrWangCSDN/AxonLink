package com.axonlink.ai.replay.dbcompare.service;

import java.util.Locale;
import java.util.Set;

/** Download target only; the saved version script remains immutable. */
public enum ConfigScriptTarget {
    LEGACY, NEW;

    private static final Set<String> TABLES = Set.of(
            "tss_bcomp_conf", "tss_bcomp_field", "tss_bcomp_table_sql");

    String convert(String sql) {
        StringBuilder statements = new StringBuilder(sql.length());
        StringBuilder output = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            int start = i;
            char c = sql.charAt(i);
            if (c == '\'') {
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == '\'') {
                        if (i < sql.length() && sql.charAt(i) == '\'') i++;
                        else break;
                    }
                }
            } else if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                        || sql.charAt(i) == '_')) i++;
                String token = sql.substring(start, i);
                output.append(token);
                if (this == NEW && TABLES.contains(token.toLowerCase(Locale.ROOT))) {
                    output.append(token.equals(token.toUpperCase(Locale.ROOT)) ? "_NEW" : "_new");
                }
                continue;
            } else {
                i++;
            }
            output.append(sql, start, i);
            if (c == ';') {
                statements.append(this == LEGACY ? legacyStatement(output.toString()) : output);
                output.setLength(0);
            }
        }
        return statements.append(output).toString();
    }

    private String legacyStatement(String sql) {
        var header = java.util.regex.Pattern.compile(
                "(?is)^(\\s*INSERT\\s+INTO\\s+tss_bcomp_conf\\s*\\([^)]*?),\\s*bcomp_partition_num\\s*,\\s*bcomp_shard_strategy(?:\\s*,\\s*bcomp_sample_limit)?(\\s*\\)\\s*VALUES\\s*)(.*)$")
                .matcher(sql);
        if (!header.matches()) return sql;
        String values = header.group(3);
        StringBuilder rows = new StringBuilder();
        boolean quoted = false;
        int commas = 0;
        for (int i = 0; i < values.length(); i++) {
            char c = values.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < values.length() && values.charAt(i + 1) == '\'') {
                    if (commas < 8) rows.append("''");
                    i++;
                    continue;
                }
                quoted = !quoted;
            }
            if (!quoted && c == '(') commas = 0;
            if (!quoted && c == ')') { rows.append(c); commas = 0; continue; }
            if (!quoted && c == ',') commas++;
            if (commas < 8) rows.append(c);
        }
        return header.group(1) + header.group(2) + rows;
    }

    public ReplayDatabaseComparisonConfigScriptService.ScriptFile file(
            ReplayDatabaseComparisonConfigScriptService.ScriptFile original) {
        String sql = new String(original.content(), java.nio.charset.StandardCharsets.UTF_8);
        String converted = convert(sql);
        if (this == LEGACY && sql.equals(converted)) return original;
        byte[] bytes = converted.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            String hash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            String name = this == NEW ? original.fileName().replaceFirst("\\.sql$", "-new.sql") : original.fileName();
            return new ReplayDatabaseComparisonConfigScriptService.ScriptFile(name, hash, bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
