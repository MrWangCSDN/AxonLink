package com.axonlink.ai.replay.dto;

/** 修改无条件忽略配置。 */
public record ReplayUnconditionalIgnoreUpdateRequest(String tranCode, String fieldName, String ignoreReason,
                                                     Integer version) {
}
