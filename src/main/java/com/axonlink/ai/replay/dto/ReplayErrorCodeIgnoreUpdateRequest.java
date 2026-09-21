package com.axonlink.ai.replay.dto;

/** 修改错误码忽略配置。 */
public record ReplayErrorCodeIgnoreUpdateRequest(String serviceCode, String oldRespCode, String newRespCode,
                                                 String ignoreReason, Integer version) {
}
