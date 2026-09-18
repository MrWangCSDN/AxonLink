package com.axonlink.ai.replay.dto;

import java.util.List;

/** 批量审核请求，最多 100 条；能审核的通过，其余跳过。 */
public record ReplayConfigBatchReviewRequest(List<ReplayConfigVersionedId> items) {
}
