package com.axonlink.ai.replay.dto;

/**
 * 新增排序字段配置。
 *
 * <p>输入为内部交易码、老/新核心排序字段与忽略原因。后端按交易码对应的完整服务码生成配置，
 * 各条共用同一条忽略原因。排序字段格式为 {@code A.B} 或 {@code A(B,C)}。
 */
public record ReplaySortFieldCreateRequest(String tranCode, String oldSortField,
                                           String oldSortFieldChineseName, String newSortField,
                                           String newSortFieldChineseName, String ignoreReason) {

    public ReplaySortFieldCreateRequest(String tranCode, String oldSortField, String newSortField,
                                        String ignoreReason) {
        this(tranCode, oldSortField, "中文名称", newSortField, "中文名称", ignoreReason);
    }
}
