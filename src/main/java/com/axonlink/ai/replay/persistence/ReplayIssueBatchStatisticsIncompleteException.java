package com.axonlink.ai.replay.persistence;

/** The selected batch lacks a complete historical issue-statistics snapshot. */
public class ReplayIssueBatchStatisticsIncompleteException extends IllegalStateException {
    public ReplayIssueBatchStatisticsIncompleteException(String batchNo) {
        super("批次历史统计不完整：" + batchNo + "，请补齐该批次历史数据后重新生成报表");
    }
}
