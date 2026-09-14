package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

/** 有条件忽略配置行（含隐藏 version）。 */
public record ReplayConditionalRmoveRow(long id, String origTrcd, String fieldRmoveName, int fieldFielState,
                                        int fieldFileIndx, int fieldFileFlag, String origFieldCond,
                                        String destFieldCond, LocalDateTime createdAt, LocalDateTime updatedAt,
                                        int version) {
}
