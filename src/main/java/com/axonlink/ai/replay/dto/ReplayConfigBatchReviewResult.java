package com.axonlink.ai.replay.dto;

/** 批量审核结果：通过条数与跳过条数。 */
public record ReplayConfigBatchReviewResult(int approvedCount, int skippedCount) {
}
