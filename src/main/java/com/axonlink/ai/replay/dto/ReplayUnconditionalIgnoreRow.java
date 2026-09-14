package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** 无条件忽略配置行（含隐藏 version）。 */
public record ReplayUnconditionalIgnoreRow(long id, String tranCode, String fieldName, int enableFlag,
                                           LocalDateTime createdAt, LocalDateTime updatedAt, int version) {
}
