package com.axonlink.ai.dto;

/**
 * AI 解读模式。
 */
public enum AnalysisMode {
    BUSINESS,
    TECHNICAL,
    FULL;

    public static AnalysisMode fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        return AnalysisMode.valueOf(value.trim().toUpperCase());
    }
}
