package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

public record ReplayDailyReportSnapshot(
        String batchNo,
        String fileName,
        String contentType,
        byte[] content,
        long fileSize,
        LocalDateTime generatedAt) {

    public ReplayDailyReportSnapshot {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
