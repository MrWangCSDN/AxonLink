package com.axonlink.ai.replay.dto;

/**
 * 新增排序字段配置。
 *
 * <p>输入为 4 位交易码与老/新核心排序字段；后端按 znzx_service 映射并展开为三条记录。
 * 排序字段格式为 {@code A.B} 或 {@code A(B,C)}。
 */
public record ReplaySortFieldCreateRequest(String tranCode, String oldSortField, String newSortField) {
}
