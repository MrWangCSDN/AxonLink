package com.axonlink.ai.replay.dto;

/** 无条件忽略批量新增草稿（已完成校验与归一化）。 */
public record ReplayUnconditionalIgnoreDraft(String tranCode, String fieldName) {
}
