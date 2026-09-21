package com.axonlink.ai.replay.dto;

/**
 * 新增排序字段配置。
 *
 * <p>输入为 4 位交易码、老/新核心排序字段与忽略原因；后端按 znzx_service 映射并展开为三条记录，
 * 三条共用同一条忽略原因。排序字段格式为 {@code A.B} 或 {@code A(B,C)}。
 */
public record ReplaySortFieldCreateRequest(String tranCode, String oldSortField, String newSortField,
                                           String ignoreReason) {
}
