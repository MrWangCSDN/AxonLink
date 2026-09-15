package com.axonlink.ai.replay.dto;

/** 修改排序字段配置。 */
public record ReplaySortFieldUpdateRequest(String origTrcd, String origArryName, String origFieldName,
                                           Integer version) {
}
