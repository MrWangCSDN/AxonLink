package com.axonlink.ai.replay.dto;

/** 错误码忽略批量新增草稿（已完成校验与归一化）。 */
public record ReplayErrorCodeIgnoreDraft(String serviceCode, String oldRespCode, String newRespCode,
                                         String ignoreReason) {
}
