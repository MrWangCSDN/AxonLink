package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** 错误码忽略配置行（含隐藏 version）。 */
public record ReplayErrorCodeIgnoreRow(long id, String serviceCode, String oldRespCode, String newRespCode,
                                       int enabled, LocalDateTime createdAt, LocalDateTime updatedAt, int version) {
}
