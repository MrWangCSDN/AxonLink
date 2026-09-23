package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** 无条件忽略配置行（含隐藏 version、审核状态与人员映射展示字段）。 */
public record ReplayUnconditionalIgnoreRow(long id, String tranCode, String fieldName, String ignoreReason,
                                           int enableFlag, LocalDateTime createdAt, LocalDateTime updatedAt,
                                           int version, int reviewStatus, String oldTransactionCode,
                                           String developer, String bankOwner, boolean canReview,
                                           String reviewDisabledReason, String domain) {
}
