package com.axonlink.ai.replay.dto;

public record ReplayMailAttachmentMetadata(
        String fileName,
        long size,
        ReplayMailAttachmentSource source,
        String batchNo,
        ReplayReportPeriod period,
        String startBatchNo,
        String endBatchNo) {
    public ReplayMailAttachmentMetadata(String fileName, long size,
                                        ReplayMailAttachmentSource source, String batchNo) {
        this(fileName, size, source, batchNo,
                source == ReplayMailAttachmentSource.LOCAL_EXCEL ? null : ReplayReportPeriod.DAILY,
                null, batchNo);
    }
}
