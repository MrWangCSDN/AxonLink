package com.axonlink.ai.replay.dto;

/** 单个字段的前后值变化。 */
public record ReplayConfigFieldChange(String field, String label, String oldValue, String newValue) {
}
