package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** 排序字段配置行（含隐藏 version、审核状态与人员映射展示字段）。 */
public record ReplaySortFieldRow(long id, String origTrcd, String origArryName, String origFieldName,
                                 String ignoreReason, int tranMode, LocalDateTime createdAt,
                                 LocalDateTime updatedAt, int version, int reviewStatus,
                                 String oldTransactionCode, String developer, String bankOwner,
                                 boolean canReview, String reviewDisabledReason, String domain) {
}
