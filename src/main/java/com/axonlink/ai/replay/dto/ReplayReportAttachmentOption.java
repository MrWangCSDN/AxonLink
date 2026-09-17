package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record ReplayReportAttachmentOption(
        String batchNo,
        String family,
        String fileName,
        long fileSize,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime generatedAt,
        ReplayReportPeriod period,
        String startBatchNo,
        String endBatchNo,
        boolean summaryViewAvailable,
        String summaryViewSource) {
    public ReplayReportAttachmentOption(String batchNo, String family, String fileName,
                                        long fileSize, LocalDateTime generatedAt) {
        this(batchNo, family, fileName, fileSize, generatedAt,
                ReplayReportPeriod.DAILY, null, batchNo, true, "SNAPSHOT_JSON");
    }

    public String businessKey() {
        return period + "|" + (startBatchNo == null ? "" : startBatchNo) + "|" + endBatchNo;
    }
}
