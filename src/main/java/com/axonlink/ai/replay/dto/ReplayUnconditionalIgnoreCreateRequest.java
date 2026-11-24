package com.axonlink.ai.replay.dto;

/** 新增无条件忽略配置。 */
public record ReplayUnconditionalIgnoreCreateRequest(String tranCode, String fieldName, String fieldChineseName,
                                                     String ignoreReason) {

    public ReplayUnconditionalIgnoreCreateRequest(String tranCode, String fieldName, String ignoreReason) {
        this(tranCode, fieldName, "中文名称", ignoreReason);
    }
}
