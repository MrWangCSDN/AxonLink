package com.axonlink.ai.replay.dto;

/** 修改排序字段配置。 */
public record ReplaySortFieldUpdateRequest(String origTrcd, String origArryName, String origFieldName,
                                           String fieldChineseName, String ignoreReason, Integer version) {

    public ReplaySortFieldUpdateRequest(String origTrcd, String origArryName, String origFieldName,
                                        String ignoreReason, Integer version) {
        this(origTrcd, origArryName, origFieldName, "中文名称", ignoreReason, version);
    }
}
