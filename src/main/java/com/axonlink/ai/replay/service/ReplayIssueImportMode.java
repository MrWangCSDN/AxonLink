package com.axonlink.ai.replay.service;

import java.util.Locale;

/** Formal replay import mode and its batch-prefix normalization rule. */
public enum ReplayIssueImportMode {
    QUERY,
    DZ;

    public static ReplayIssueImportMode fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return QUERY;
        }
        String normalized = value.trim();
        try {
            return valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知回放类型：" + normalized);
        }
    }

    public String normalizeBatch(String batch) {
        if (batch == null || this != DZ || !batch.startsWith("RPT")) {
            return batch;
        }
        return "DZ" + batch.substring(3);
    }
}
