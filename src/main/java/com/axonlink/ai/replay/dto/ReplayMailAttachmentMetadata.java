package com.axonlink.ai.replay.dto;

public record ReplayMailAttachmentMetadata(
        String fileName,
        long size,
        ReplayMailAttachmentSource source,
        String batchNo) {
}
