package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/**
 * 某条忽略配置「最后一次审核」的快照（取操作表里 operation_type=REVIEW 的最新一条）。
 *
 * @param operatorUsername 审核人账号
 * @param operatorRealName 审核人姓名
 * @param reviewedAt       审核时间（即审核通过时间）
 */
public record ReplayConfigReviewSnapshot(String operatorUsername, String operatorRealName,
                                         LocalDateTime reviewedAt) {
}
