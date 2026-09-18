package com.axonlink.ai.replay.dto;

import java.util.List;

/** 批量新增请求：1 至 3 条，逐条独立填写，任一条重复则整批失败。 */
public record ReplayConfigBatchCreateRequest<T>(List<T> items) {
}
