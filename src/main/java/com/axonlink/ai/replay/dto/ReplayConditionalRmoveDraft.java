package com.axonlink.ai.replay.dto;

/** 有条件忽略批量新增草稿（已完成校验与归一化，索引由后端分配）。 */
public record ReplayConditionalRmoveDraft(String origTrcd, String fieldRmoveName, int fieldFileFlag,
                                          String origFieldCond, String destFieldCond, String ignoreReason) {
}
