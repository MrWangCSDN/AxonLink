package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueBatchStatisticsIncompleteException;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads one report endpoint inside the caller's result-database snapshot transaction. */
public final class ReplayReportCalculationDataLoader {
    private final ReplayDailyDataDao dailyDataDao;
    private final ReplayIssueDao issueDao;
    private final ReplayWeeklyLatestIssueResolver latestIssueResolver;

    public ReplayReportCalculationDataLoader(ReplayDailyDataDao dailyDataDao, ReplayIssueDao issueDao) {
        this.dailyDataDao = dailyDataDao;
        this.issueDao = issueDao;
        this.latestIssueResolver = new ReplayWeeklyLatestIssueResolver(issueDao);
    }

    /** Prefer frozen history; legacy batches retain their original current-conclusion statistics rule. */
    public SideData loadHistorical(String batchNo) {
        String batch = batch(batchNo);
        List<ReplayDailySummaryRow> summaries = dailyDataDao.findSummaries(batch);
        List<ReplayInterfaceComparisonRow> comparisons = dailyDataDao.findComparisons(batch);
        HistoricalIssues historical = historical(batch, summaries);
        if (!historical.legacy()) {
            return sideData(batch, summaries, comparisons, historical, historical.rows(), Set.of(), Set.of(), Set.of());
        }
        var metrics = latestIssueResolver.resolve(batch, comparisons, historical.members());
        Set<String> unknown = historical.members().isEmpty() ? sendingDomains(summaries) : Set.of();
        return sideData(batch, summaries, comparisons, historical, metrics.metricIssues(),
                union(metrics.noActionUnknownDomains(), unknown), union(metrics.breakdownUnknownDomains(), unknown),
                union(metrics.matchUnknownDomains(), unknown));
    }

    /** Weekly reports keep historical issue statistics separate from latest-conclusion transaction metrics. */
    public SideData loadLatest(String batchNo, List<ReplayIssueRow> latestIssues) {
        String batch = batch(batchNo);
        List<ReplayDailySummaryRow> summaries = dailyDataDao.findSummaries(batch);
        List<ReplayInterfaceComparisonRow> comparisons = dailyDataDao.findComparisons(batch);
        HistoricalIssues historical = historical(batch, summaries);
        var metrics = latestIssueResolver.resolve(batch, comparisons, latestIssues);
        return sideData(batch, summaries, comparisons, historical, metrics.metricIssues(),
                metrics.noActionUnknownDomains(), metrics.breakdownUnknownDomains(), metrics.matchUnknownDomains());
    }

    private HistoricalIssues historical(String batch, List<ReplayDailySummaryRow> summaries) {
        try {
            return new HistoricalIssues(issueDao.findBatchReportIssueStatistics(batch), true, false, List.of());
        } catch (ReplayIssueBatchStatisticsIncompleteException exception) {
            var rows = issueDao.findLegacyReportIssueStatistics(batch);
            var members = issueDao.findCurrentIssueMembersByBatch(batch);
            boolean knownZero = !summaries.isEmpty() && summaries.stream().allMatch(row ->
                    row.issueTotal() != null && row.issueTotal() == 0L);
            return new HistoricalIssues(rows, !rows.isEmpty() || knownZero, true, members);
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return result;
    }

    private SideData sideData(String batch,
                              List<ReplayDailySummaryRow> summaries,
                              List<ReplayInterfaceComparisonRow> comparisons,
                              HistoricalIssues historical,
                              List<ReplayDailyIssueStatisticRow> metrics,
                              Set<String> noActionUnknown,
                              Set<String> breakdownUnknown,
                              Set<String> matchUnknown) {
        Set<String> sendingDomains = sendingDomains(summaries);
        Map<String, List<ReplayInterfaceComparisonRow>> detailsByDomain = new LinkedHashMap<>();
        for (ReplayInterfaceComparisonRow comparison : comparisons) {
            if (comparison.rowType() == ReplayDailyRowType.DETAIL && batch.equals(trim(comparison.batchNo()))
                    && !domain(comparison.domain()).isEmpty()) {
                detailsByDomain.computeIfAbsent(domain(comparison.domain()), ignored -> new ArrayList<>()).add(comparison);
            }
        }
        Set<String> missingScope = new LinkedHashSet<>();
        Set<String> legacyDomains = new LinkedHashSet<>();
        for (ReplayDailySummaryRow summary : summaries) {
            String domain = domain(summary.domain());
            List<ReplayInterfaceComparisonRow> details = detailsByDomain.getOrDefault(domain, List.of());
            boolean validScope = details.stream().anyMatch(row -> validCode(batch, row));
            boolean completeSplit = !details.isEmpty() && details.stream().allMatch(row ->
                    nonnegative(row.bothSuccessNoFieldDifference())
                            && nonnegative(row.bothSuccessWithFieldDifference()));
            if (sendingDomains.contains(domain) && !validScope) missingScope.add(domain);
            if (!completeSplit) legacyDomains.add(domain);
        }
        Set<String> unknownNoAction = relevantUnknown(noActionUnknown, sendingDomains);
        Set<String> unknownBreakdown = relevantUnknown(breakdownUnknown, sendingDomains);
        Set<String> unknownMatch = relevantUnknown(matchUnknown, sendingDomains);
        unknownNoAction.addAll(missingScope);
        unknownBreakdown.addAll(missingScope);
        unknownMatch.addAll(missingScope);
        var availability = new ReplayReportCalculationContext.Side(historical.available(), unknownNoAction,
                unknownBreakdown, unknownMatch, legacyDomains);
        return new SideData(summaries, comparisons, historical.rows(), metrics, availability,
                notices(batch, historical, missingScope, legacyDomains, unknownNoAction, unknownBreakdown));
    }

    private static List<String> notices(String batch, HistoricalIssues historical, Set<String> missingScope,
                                        Set<String> legacyDomains, Set<String> noActionUnknown,
                                        Set<String> breakdownUnknown) {
        List<String> reasons = new ArrayList<>();
        if (historical.legacy() && historical.available()) {
            reasons.add(historical.rows().isEmpty()
                    ? "历史快照缺失，原始汇总问题总数明确为0，按旧规则确认零问题统计"
                    : "历史快照缺失，问题总数、分类及上一批未解决数量、解决率按该批问题记录与当前处理结论统计（旧规则，非当时快照）");
        } else if (!historical.available()) {
            reasons.add("历史问题统计未建立且未找到该批问题记录，无法恢复的问题总数、分类及上一批问题解决指标留空");
        }
        if (!missingScope.isEmpty()) {
            reasons.add(String.join("、", missingScope) + "缺少含有效交易码的本批次接口明细");
        }
        if (!noActionUnknown.isEmpty()) {
            reasons.add(String.join("、", noActionUnknown) + "的已审核无需处理原始影响笔数无法确认，"
                    + "无需处理数量留空；已确认审核通过的本批笔数参与扣除，无法确认部分不参与调整");
        }
        if (!breakdownUnknown.isEmpty()) {
            reasons.add(String.join("、", breakdownUnknown) + "的交易问题影响笔数无法确认，对应数量留空");
        }
        if (!legacyDomains.isEmpty()) {
            reasons.add(String.join("、", legacyDomains) + "的成功率、比对通过率沿用原算法，"
                    + "不补加字段差异数量；可确认的本批次已审核无需处理数量仍按规则扣除");
        }
        return reasons.isEmpty() ? List.of() : List.of(batch + "：" + String.join("；", reasons) + "。");
    }

    private static Set<String> relevantUnknown(Set<String> values, Set<String> sendingDomains) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String domain = domain(value);
            if (sendingDomains.contains(domain)) result.add(domain);
        }
        return result;
    }

    private static Set<String> sendingDomains(List<ReplayDailySummaryRow> summaries) {
        Set<String> result = new LinkedHashSet<>();
        for (ReplayDailySummaryRow summary : summaries) {
            if (summary.sentTransactionCount() != null && summary.sentTransactionCount() > 0) {
                result.add(domain(summary.domain()));
            }
        }
        return result;
    }

    private static boolean validCode(String batch, ReplayInterfaceComparisonRow row) {
        return batch.equals(trim(row.batchNo())) && !trim(row.transactionCode()).isEmpty();
    }

    private static boolean nonnegative(Long value) {
        return value != null && value >= 0;
    }

    private static String batch(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) throw new IllegalArgumentException("批次号不能为空");
        return batchNo.trim();
    }

    private static String domain(String value) {
        String domain = trim(value);
        return domain.startsWith("沙箱")
                ? "沙箱-" + domain.substring(2).replaceFirst("^[-—_]", "").trim() : domain;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record HistoricalIssues(List<ReplayDailyIssueStatisticRow> rows, boolean available,
                                    boolean legacy, List<ReplayIssueRow> members) {
        private HistoricalIssues {
            rows = List.copyOf(rows);
            members = List.copyOf(members);
        }
    }

    public record SideData(
            List<ReplayDailySummaryRow> summaries,
            List<ReplayInterfaceComparisonRow> comparisons,
            List<ReplayDailyIssueStatisticRow> historicalIssues,
            List<ReplayDailyIssueStatisticRow> metricIssues,
            ReplayReportCalculationContext.Side availability,
            List<String> notices) {
        public SideData {
            summaries = List.copyOf(summaries);
            comparisons = List.copyOf(comparisons);
            historicalIssues = List.copyOf(historicalIssues);
            metricIssues = List.copyOf(metricIssues);
            notices = List.copyOf(notices);
        }
    }
}
