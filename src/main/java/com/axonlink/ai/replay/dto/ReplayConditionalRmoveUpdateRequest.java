package com.axonlink.ai.replay.dto;

/** 修改有条件忽略配置；fieldFileIndex 由后端维护，不接受提交。 */
public record ReplayConditionalRmoveUpdateRequest(String origTrcd, String fieldRmoveName,
                                                  String fieldChineseName, Integer fieldFileFlag,
                                                  String origFieldCond, String destFieldCond,
                                                  String ignoreReason, Integer version) {

    public ReplayConditionalRmoveUpdateRequest(String origTrcd, String fieldRmoveName, Integer fieldFileFlag,
                                               String origFieldCond, String destFieldCond,
                                               String ignoreReason, Integer version) {
        this(origTrcd, fieldRmoveName, "中文名称", fieldFileFlag, origFieldCond, destFieldCond,
                ignoreReason, version);
    }
}
