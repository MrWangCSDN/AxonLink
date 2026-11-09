package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayBatchOriginalIssueQuantity;
import com.axonlink.ai.replay.dto.ReplayBatchOriginalIssueQuantity.Presence;
import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Combines today's per-key conclusion with one selected batch's own original transaction facts. */
public final class ReplayWeeklyLatestIssueResolver {
    private static final Set<String> FAILURE_FIELDS = Set.of(
            "528成功ccbs失败", "528失败ccbs成功", "二者都失败响应码不一致");
    private static final Comparator<LocalDateTime> TIME = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<ReplayIssueRow> LATEST = Comparator.comparing(ReplayIssueRow::importedAt, TIME)
            .thenComparing(ReplayIssueRow::reviewedAt, TIME)
            .thenComparing(ReplayIssueRow::id, Comparator.nullsFirst(Comparator.naturalOrder()));
    private final ReplayIssueDao issueDao;

    public ReplayWeeklyLatestIssueResolver(ReplayIssueDao issueDao) {
        this.issueDao = issueDao;
    }

    public Resolution resolve(String batchNo, List<ReplayInterfaceComparisonRow> comparisons,
                              List<ReplayIssueRow> latestIssues) {
        if (batchNo == null || batchNo.isBlank()) throw new IllegalArgumentException("批次号不能为空");
        String batch = batchNo.trim();
        Map<String, String> domains = new LinkedHashMap<>();
        Map<String, List<ReplayInterfaceComparisonRow>> comparisonsByDomain = new LinkedHashMap<>();
        for (ReplayInterfaceComparisonRow row : safe(comparisons)) {
            if (row.rowType() != ReplayDailyRowType.DETAIL || !batch.equals(trim(row.batchNo()))) continue;
            String code = trim(row.transactionCode());
            String domain = domain(row.domain());
            if (domain.isEmpty()) continue;
            comparisonsByDomain.computeIfAbsent(domain, ignored -> new ArrayList<>()).add(row);
            if (code.isEmpty()) continue;
            String previous = domains.putIfAbsent(code, domain);
            if (previous != null && !previous.equals(domain)) {
                throw new IllegalArgumentException("接口比对明细交易码所属领域不唯一：" + batch + " / " + code);
            }
        }

        Map<String, ReplayBatchOriginalIssueQuantity> quantities = new LinkedHashMap<>();
        for (ReplayBatchOriginalIssueQuantity quantity : safe(issueDao.findBatchOriginalReportQuantities(batch))) {
            quantities.putIfAbsent(quantity.issueKey(), quantity);
        }
        Set<String> memberKeys = new LinkedHashSet<>();
        for (ReplayIssueRow member : safe(issueDao.findCurrentIssueMembersByBatch(batch))) {
            if (member != null && member.issueKey() != null) memberKeys.add(member.issueKey());
        }
        Map<String, ReplayIssueRow> unique = new LinkedHashMap<>();
        for (ReplayIssueRow issue : safe(latestIssues)) {
            if (issue == null || issue.issueKey() == null || issue.issueKey().isBlank()) continue;
            unique.merge(issue.issueKey(), issue, (left, right) -> LATEST.compare(left, right) > 0 ? left : right);
        }

        List<ReplayDailyIssueStatisticRow> metrics = new ArrayList<>();
        Set<String> noActionUnknown = new LinkedHashSet<>();
        Set<String> breakdownUnknown = new LinkedHashSet<>();
        Set<String> matchUnknown = new LinkedHashSet<>();
        Set<String> quantityUnknownDomains = new LinkedHashSet<>();
        Set<String> splitUnknownDomains = new LinkedHashSet<>();
        Map<String, List<ReplayIssueRow>> fieldsByTransaction = new LinkedHashMap<>();
        for (ReplayIssueRow issue : unique.values()) {
            String code = trim(issue.transactionCode());
            if (!domains.containsKey(code)) continue;
            String domain = domains.get(code);
            String level = trim(issue.issueLevel());
            boolean transaction = "交易级".equals(level);
            if (transaction && !batch.equals(trim(issue.batchNo())) && !memberKeys.contains(issue.issueKey())
                    && !quantities.containsKey(issue.issueKey())) {
                continue;
            }
            if ("字段级".equals(level)) {
                fieldsByTransaction.computeIfAbsent(code, ignored -> new ArrayList<>()).add(issue);
            }
            Long affected = transaction ? originalCount(batch, issue, quantities.get(issue.issueKey())) : Long.valueOf(0L);
            if (transaction && affected == null) {
                if (approvedNoAction(issue)) {
                    noActionUnknown.add(domain);
                    matchUnknown.add(domain);
                    quantityUnknownDomains.add(domain);
                } else if (issue.issueStatus() != ReplayIssueStatus.NO_ACTION
                        && FAILURE_FIELDS.contains(normalizeField(issue.fieldName()))) {
                    breakdownUnknown.add(domain);
                    quantityUnknownDomains.add(domain);
                }
            }
            boolean sandbox = domain.startsWith("沙箱-");
            metrics.add(new ReplayDailyIssueStatisticRow(issue.id(), sandbox ? domain.substring(3) : domain,
                    sandbox, issue.issueType(), level, issue.fieldName(),
                    issue.issueStatus() == null ? null : issue.issueStatus().displayValue(),
                    affected == null ? 0L : affected, false, issue.reviewStatus(), code, issue.defectRepairDate()));
        }

        fieldsByTransaction.forEach((code, fields) -> {
            if (fields.stream().anyMatch(ReplayWeeklyLatestIssueResolver::approvedNoAction)
                    && fields.stream().allMatch(issue -> issue.defectRepairDate() != null)) {
                String domain = domains.get(code);
                boolean completeSplit = comparisonsByDomain.getOrDefault(domain, List.of()).stream().allMatch(row ->
                        row.bothSuccessNoFieldDifference() != null && row.bothSuccessNoFieldDifference() >= 0
                                && row.bothSuccessWithFieldDifference() != null && row.bothSuccessWithFieldDifference() >= 0);
                if (!completeSplit) {
                    matchUnknown.add(domain);
                    splitUnknownDomains.add(domain);
                }
            }
        });
        List<String> reasons = new ArrayList<>();
        if (!quantityUnknownDomains.isEmpty()) {
            reasons.add(String.join("、", quantityUnknownDomains) + "缺少可靠的本批次原始影响笔数");
        }
        if (!splitUnknownDomains.isEmpty()) {
            reasons.add(String.join("、", splitUnknownDomains) + "的接口比对明细缺少完整字段差异拆分");
        }
        List<String> notices = reasons.isEmpty() ? List.of()
                : List.of(batch + "：" + String.join("；", reasons) + "，相关指标留空。");
        return new Resolution(metrics, noActionUnknown, breakdownUnknown, matchUnknown, notices);
    }

    private static Long originalCount(String batch, ReplayIssueRow issue, ReplayBatchOriginalIssueQuantity evidence) {
        if (evidence != null) {
            if (evidence.presence() == Presence.ABSENT) return 0L;
            if (evidence.presence() != Presence.PRESENT
                    || !issue.issueKey().equals(evidence.issueKey())
                    || !trim(issue.transactionCode()).equals(trim(evidence.transactionCode()))
                    || !trim(issue.issueLevel()).equals(trim(evidence.issueLevel()))
                    || !normalizeField(issue.fieldName()).equals(normalizeField(evidence.fieldName()))) return null;
            return evidence.affectedTransactionCount();
        }
        // Manual save/review never changes this base quantity; only the exact owning batch is a safe fallback.
        if (!batch.equals(trim(issue.batchNo()))) return null;
        String count = trim(issue.affectedTransactionCount());
        if (!count.matches("[0-9]+")) return null;
        try { return Long.parseLong(count); }
        catch (NumberFormatException exception) { return null; }
    }

    private static boolean approvedNoAction(ReplayIssueRow issue) {
        return issue.issueStatus() == ReplayIssueStatus.NO_ACTION
                && issue.reviewStatus() == ReplayIssueReviewStatus.APPROVED;
    }

    private static String normalizeField(String field) {
        return trim(field).toLowerCase(Locale.ROOT).replaceAll("[\\s/／]", "");
    }

    private static String domain(String value) {
        String domain = trim(value);
        if (!domain.startsWith("沙箱")) return domain;
        return "沙箱-" + domain.substring(2).replaceFirst("^[-—_]", "").trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    public record Resolution(
            List<ReplayDailyIssueStatisticRow> metricIssues,
            Set<String> noActionUnknownDomains,
            Set<String> breakdownUnknownDomains,
            Set<String> matchUnknownDomains,
            List<String> notices) {
        public Resolution {
            metricIssues = List.copyOf(metricIssues);
            noActionUnknownDomains = Set.copyOf(noActionUnknownDomains);
            breakdownUnknownDomains = Set.copyOf(breakdownUnknownDomains);
            matchUnknownDomains = Set.copyOf(matchUnknownDomains);
            notices = List.copyOf(notices);
        }
    }
}
