package com.axonlink.ai.replay.dto;

/** 新增有条件忽略配置；fieldFileIndex 由后端生成，不接受提交。 */
public record ReplayConditionalRmoveCreateRequest(String origTrcd, String fieldRmoveName, Integer fieldFileFlag,
                                                  String origFieldCond, String destFieldCond) {
}
