package com.axonlink.ai.replay.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日报 · 一行（按领域 + 是否沙箱分组的统计单元）。
 *
 * <p>上半部分使用上一批次聚合结果，展示问题总数、已解决问题分类和排查进度。
 * 下半部分会同时使用两组聚合结果：问题总数来自本批次，本记录提供的未解决数量、状态分类、
 * 上轮解决率和解决进度用于上一批次指标。
 */
public record DailyReportRow(
        String groupName,
        boolean sandbox,
        long totalCount,
        long fixedCount,
        long unresolvedCount,
        /** 交易级且状态为“无需处理”的问题数，对应日报交易核对分类中的“合理差异”。 */
        long reasonableDifferenceCount,
        /** issue_type → 该 group+sandbox 全部问题按 issue_type 的分布，用于发现动态分类列。 */
        Map<String, Long> allByIssueType,
        /** issue_type → 上一批次该 group+sandbox 中已修复问题按 issue_type 的分布。 */
        Map<String, Long> fixedByIssueType,
        /** 上一批次该 group+sandbox 中非已修复问题按 status 的分布（限 5 个状态）。 */
        Map<String, Long> unresolvedByStatus) {

    /** 计算"上轮问题解决率" = 已修复数 / 问题总数，保留两位小数。 */
    public double fixRate() {
        return totalCount == 0 ? 0.0 : roundPercent(fixedCount * 100.0 / totalCount);
    }

    /** 计算"问题排查进度" = (已修复+延后修复+修复待验证) / 问题总数，保留两位小数。 */
    public double inspectionProgress() {
        return resolutionProgress();
    }

    /**
     * 计算"问题解决进度" = (延后修复+修复待验证+已修复) / 问题总数，保留两位小数。
     * 分子含已修复（已修复数来自 fixedByIssueType 之外另存；这里直接用 fixedCount）。
     */
    public double resolutionProgress() {
        if (totalCount == 0) {
            return 0.0;
        }
        return roundPercent(progressCount() * 100.0 / totalCount);
    }

    /** 已进入解决进度的问题数量。 */
    public long progressCount() {
        return fixedCount
                + unresolvedByStatus.getOrDefault("延后修复", 0L)
                + unresolvedByStatus.getOrDefault("修复待验证", 0L);
    }

    private static double roundPercent(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 构造聚合器：从原始 (issue_type, status) 行累加。 */
    public static DailyReportRow aggregate(String groupName, boolean sandbox,
                                          java.util.List<DailyIssueSlice> slices) {
        long totalCount = slices.size();
        long fixedCount = 0L;
        long reasonableDifferenceCount = 0L;
        Map<String, Long> allByIssueType = new LinkedHashMap<>();
        Map<String, Long> fixedByIssueType = new LinkedHashMap<>();
        Map<String, Long> unresolvedByStatus = new LinkedHashMap<>();
        for (DailyIssueSlice slice : slices) {
            String type = emptyToOther(slice.issueType());
            allByIssueType.merge(type, 1L, Long::sum);
            String status = slice.lastStatus();
            if ("交易级".equals(trim(slice.issueLevel())) && "无需处理".equals(status)) {
                reasonableDifferenceCount++;
            }
            if ("已修复".equals(status)) {
                fixedCount++;
                fixedByIssueType.merge(type, 1L, Long::sum);
            } else if ("新建".equals(status) || "打开".equals(status)
                    || "重新打开".equals(status) || "延后修复".equals(status) || "修复待验证".equals(status)) {
                unresolvedByStatus.merge(status, 1L, Long::sum);
            }
        }
        long unresolvedCount = totalCount - fixedCount;
        return new DailyReportRow(groupName, sandbox, totalCount, fixedCount, unresolvedCount,
                reasonableDifferenceCount,
                allByIssueType, fixedByIssueType, unresolvedByStatus);
    }

    private static String emptyToOther(String issueType) {
        return (issueType == null || issueType.isBlank()) ? "其他问题" : issueType.trim();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
