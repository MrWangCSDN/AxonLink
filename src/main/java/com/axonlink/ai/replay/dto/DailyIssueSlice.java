package com.axonlink.ai.replay.dto;

/**
 * 日报原始数据一行：某问题在某批次出现时的关键字段快照。
 * 用于 {@link DailyReportRow#aggregate} 聚合统计。
 */
public record DailyIssueSlice(
        String groupName,
        boolean sandbox,
        String issueType,
        String issueLevel,
        String lastStatus) {
}
