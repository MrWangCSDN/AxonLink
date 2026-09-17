package com.axonlink.ai.replay.dto;

/** 审核请求：仅需乐观锁版本号。 */
public record ReplayConfigReviewRequest(Integer version) {
}
