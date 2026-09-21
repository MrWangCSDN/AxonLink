package com.axonlink.ai.replay.dto;

/** 新增错误码忽略配置。 */
public record ReplayErrorCodeIgnoreCreateRequest(String serviceCode, String oldRespCode, String newRespCode,
                                                 String ignoreReason) {
}
