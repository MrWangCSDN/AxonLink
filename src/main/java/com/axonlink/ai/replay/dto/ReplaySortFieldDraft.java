package com.axonlink.ai.replay.dto;

/** 排序字段批量新增草稿：一条 4 位交易码通常展开为三条不同后缀的记录，共用同一条忽略原因。 */
public record ReplaySortFieldDraft(String origTrcd, String origArryName, String origFieldName,
                                   String ignoreReason) {
}
