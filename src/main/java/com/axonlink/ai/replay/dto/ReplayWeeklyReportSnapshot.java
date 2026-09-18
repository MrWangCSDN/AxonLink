package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

public record ReplayWeeklyReportSnapshot(
        String startBatchNo,
        String endBatchNo,
        String fileName,
        String contentType,
        byte[] content,
        long fileSize,
        String summaryViewJson,
        LocalDateTime generatedAt) {

    public ReplayWeeklyReportSnapshot(String startBatchNo, String endBatchNo, String fileName,
                                      String contentType, byte[] content, long fileSize,
                                      LocalDateTime generatedAt) {
        this(startBatchNo, endBatchNo, fileName, contentType, content, fileSize, null, generatedAt);
    }

    public ReplayWeeklyReportSnapshot {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
