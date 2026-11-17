package com.axonlink.ai.replay.dto;

/** 修改无条件忽略配置。 */
public record ReplayUnconditionalIgnoreUpdateRequest(String tranCode, String fieldName, String fieldChineseName,
                                                     String ignoreReason, Integer version) {

    public ReplayUnconditionalIgnoreUpdateRequest(String tranCode, String fieldName, String ignoreReason,
                                                  Integer version) {
        this(tranCode, fieldName, "中文名称", ignoreReason, version);
    }
}
