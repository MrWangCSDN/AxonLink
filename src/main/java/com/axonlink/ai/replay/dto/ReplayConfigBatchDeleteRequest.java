package com.axonlink.ai.replay.dto;

import java.util.List;

/** 勾选批量删除请求，长度为 1 至 100。 */
public record ReplayConfigBatchDeleteRequest(List<ReplayConfigVersionedId> items) {
}
