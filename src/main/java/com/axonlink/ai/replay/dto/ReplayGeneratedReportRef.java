package com.axonlink.ai.replay.dto;

import java.util.Objects;

public record ReplayGeneratedReportRef(
        ReplayReportPeriod period,
        String startBatchNo,
        String endBatchNo) {

    public ReplayGeneratedReportRef {
        period = Objects.requireNonNull(period, "报告周期不能为空");
        startBatchNo = normalize(startBatchNo);
        endBatchNo = normalize(endBatchNo);
        if (endBatchNo == null) throw new IllegalArgumentException("报告结束批次不能为空");
        if (period == ReplayReportPeriod.WEEKLY && startBatchNo == null) {
            throw new IllegalArgumentException("周报开始批次不能为空");
        }
        if (period == ReplayReportPeriod.DAILY) startBatchNo = null;
    }

    public String businessKey() {
        return period + "|" + Objects.toString(startBatchNo, "") + "|" + endBatchNo;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
