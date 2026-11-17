package com.axonlink.ai.replay.dto;

/** 无条件忽略原因导入的一行数据。 */
public record ReplayUnconditionalIgnoreReasonImportRow(
        int rowNumber,
        String tranCode,
        String fieldName,
        String ignoreReason,
        boolean validColumnCount) {
}
