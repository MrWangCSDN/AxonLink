package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public final class ReplayDailyReportCalculator {

    private static final int RATE_SCALE = 8;
    private static final String TRANSACTION_LEVEL = "交易级";
    private static final String NO_ACTION = "无需处理";
    private static final String NEW = "新建";

    private static final String FIELD_528_SUCCESS_CCBS_FAIL = "528成功ccbs失败";
    private static final String FIELD_528_FAIL_CCBS_SUCCESS = "528失败ccbs成功";
    private static final String FIELD_BOTH_FAIL_DIFF_CODE = "二者都失败响应码不一致";

    private static final List<String> ISSUE_TYPES = List.of(
            "代码问题", "参数问题", "合理差异", "外围问题", "平台问题",
            "新核心下线", "规则性差异问题", "迁移问题", "防腐问题", "其他问题");

    public CalculatedReport calculate(List<ReplayDailySummaryRow> previousSummaries,
                                      List<ReplayDailyIssueStatisticRow> previousIssues,
                                      List<ReplayDailySummaryRow> currentSummaries,
                                      List<ReplayDailyIssueStatisticRow> currentIssues) {
        return calculate(previousSummaries, previousIssues, List.of(), currentSummaries, currentIssues, List.of());
    }

    public CalculatedReport calculate(List<ReplayDailySummaryRow> previousSummaries,
                                      List<ReplayDailyIssueStatisticRow> previousIssues,
                                      List<ReplayInterfaceComparisonRow> previousComparisons,
                                      List<ReplayDailySummaryRow> currentSummaries,
                                      List<ReplayDailyIssueStatisticRow> currentIssues,
                                      List<ReplayInterfaceComparisonRow> currentComparisons) {
        return calculate(previousSummaries, previousIssues, previousComparisons,
                currentSummaries, currentIssues, currentComparisons, previousIssues, currentIssues);
    }

    /** Weekly replay may use latest conclusions while retaining historical problem statistics. */
    public CalculatedReport calculate(List<ReplayDailySummaryRow> previousSummaries,
                                      List<ReplayDailyIssueStatisticRow> previousIssues,
                                      List<ReplayInterfaceComparisonRow> previousComparisons,
                                      List<ReplayDailySummaryRow> currentSummaries,
                                      List<ReplayDailyIssueStatisticRow> currentIssues,
                                      List<ReplayInterfaceComparisonRow> currentComparisons,
                                      List<ReplayDailyIssueStatisticRow> previousMetricIssues,
                                      List<ReplayDailyIssueStatisticRow> currentMetricIssues) {
        return calculate(previousSummaries, previousIssues, previousComparisons, currentSummaries, currentIssues,
                currentComparisons, previousMetricIssues, currentMetricIssues, null);
    }

    public CalculatedReport calculate(List<ReplayDailySummaryRow> previousSummaries,
                                      List<ReplayDailyIssueStatisticRow> previousIssues,
                                      List<ReplayInterfaceComparisonRow> previousComparisons,
                                      List<ReplayDailySummaryRow> currentSummaries,
                                      List<ReplayDailyIssueStatisticRow> currentIssues,
                                      List<ReplayInterfaceComparisonRow> currentComparisons,
                                      List<ReplayDailyIssueStatisticRow> previousMetricIssues,
                                      List<ReplayDailyIssueStatisticRow> currentMetricIssues,
                                      ReplayReportCalculationContext context) {
        List<ReplayDailySummaryRow> safePreviousSummaries = copy(previousSummaries);
        List<ReplayDailyIssueStatisticRow> safePreviousIssues = copy(previousIssues);
        List<ReplayDailySummaryRow> safeCurrentSummaries = copy(currentSummaries);
        List<ReplayDailyIssueStatisticRow> safeCurrentIssues = copy(currentIssues);

        Map<String, List<ReplayDailyIssueStatisticRow>> previousByTransaction = indexTransactions(copy(previousMetricIssues));
        Map<String, List<ReplayDailyIssueStatisticRow>> currentByTransaction = indexTransactions(copy(currentMetricIssues));
        Map<String, List<ReplayDailyIssueStatisticRow>> reportablePreviousByDomain =
                indexIssues(reportableIssues(safePreviousIssues));
        Map<String, List<ReplayDailyIssueStatisticRow>> reportableCurrentByDomain =
                indexIssues(reportableIssues(safeCurrentIssues));
        Map<String, PreviousUnresolved> unresolvedByDomain = unresolvedByDomain(
                safePreviousSummaries, reportablePreviousByDomain);
        PreviousUnresolved previousUnresolved = totalPreviousUnresolved(
                safePreviousSummaries, reportablePreviousByDomain, unresolvedByDomain);

        List<RowCalculation> previousCalculations = calculateRows(
                safePreviousSummaries, reportablePreviousByDomain, unresolvedByDomain,
                indexComparisons(copy(previousComparisons)), previousByTransaction,
                context == null ? ReplayReportCalculationContext.Side.complete() : context.previous());
        List<RowCalculation> currentCalculations = calculateRows(
                safeCurrentSummaries, reportableCurrentByDomain, unresolvedByDomain,
                indexComparisons(copy(currentComparisons)), currentByTransaction,
                context == null ? ReplayReportCalculationContext.Side.complete() : context.current());

        List<ReplayDailySummaryCalculatedRow> previousRows = rows(previousCalculations);
        List<ReplayDailySummaryCalculatedRow> currentRows = rows(currentCalculations);
        ReplayDailySummaryCalculatedRow previousTotal = total(previousCalculations, previousUnresolved);
        ReplayDailySummaryCalculatedRow currentTotal = total(currentCalculations, previousUnresolved);
        return new CalculatedReport(previousRows, previousTotal, currentRows, currentTotal, previousUnresolved);
    }

    private List<RowCalculation> calculateRows(
            List<ReplayDailySummaryRow> summaries,
            Map<String, List<ReplayDailyIssueStatisticRow>> reportableIssuesByDomain,
            Map<String, PreviousUnresolved> unresolvedByDomain,
            Map<ComparisonDomain, List<ReplayInterfaceComparisonRow>> comparisonsByDomain,
            Map<String, List<ReplayDailyIssueStatisticRow>> issuesByTransaction,
            ReplayReportCalculationContext.Side availability) {
        List<RowCalculation> result = new ArrayList<>(summaries.size());
        for (ReplayDailySummaryRow summary : summaries) {
            String domain = displayDomain(summary.domain());
            List<ReplayDailyIssueStatisticRow> reportableIssues =
                    reportableIssuesByDomain.getOrDefault(domain, List.of());
            long issueTotal = reportableIssues.size();
            IssueStatistics statistics = issueStatistics(reportableIssues, issueTotal);
            PreviousUnresolved unresolved = unresolvedByDomain.getOrDefault(domain, PreviousUnresolved.empty());
            List<ReplayInterfaceComparisonRow> comparisons = comparisonsByDomain.getOrDefault(
                    new ComparisonDomain(normalizeText(summary.batchNo()), domain), List.of());
            Set<String> codes = new LinkedHashSet<>();
            comparisons.forEach(row -> {
                String code = normalizeText(row.transactionCode());
                if (!code.isEmpty()) codes.add(code);
            });
            List<ReplayDailyIssueStatisticRow> matchedIssues = new ArrayList<>();
            codes.forEach(code -> matchedIssues.addAll(issuesByTransaction.getOrDefault(code, List.of())));
            result.add(calculateRow(summary, domain, matchedIssues, comparisons, issueTotal, statistics, unresolved, availability));
        }
        return List.copyOf(result);
    }

    private RowCalculation calculateRow(ReplayDailySummaryRow summary,
                                        String domain,
                                        List<ReplayDailyIssueStatisticRow> issues,
                                        List<ReplayInterfaceComparisonRow> comparisons,
                                        long issueTotal,
                                        IssueStatistics statistics,
                                        PreviousUnresolved unresolved,
                                        ReplayReportCalculationContext.Side availability) {
        long c528SuccessCcbsFail = affectedCount(issues, FIELD_528_SUCCESS_CCBS_FAIL);
        long c528FailCcbsSuccess = affectedCount(issues, FIELD_528_FAIL_CCBS_SUCCESS);
        long bothFailDiffCode = affectedCount(issues, FIELD_BOTH_FAIL_DIFF_CODE);
        long noAction = issues.stream()
                .filter(ReplayDailyReportCalculator::isTransactionLevel)
                .filter(issue -> NO_ACTION.equals(normalizeText(issue.issueStatus())))
                .filter(issue -> issue.reviewStatus() == ReplayIssueReviewStatus.APPROVED)
                .mapToLong(ReplayDailyIssueStatisticRow::affectedTransactionCount)
                .sum();

        long sent = value(summary.sentTransactionCount());
        long sameFail = value(summary.bothFailSameCode());
        long bothSuccess = value(summary.bothSuccess());
        long ignored = value(summary.codeIgnored());
        boolean legacy = availability.useLegacyRate(domain, false);
        // Unknown amounts are represented as zero in metric inputs; retain every confirmed approved amount.
        long adjustedDenominator = sent - ignored - noAction;
        long inferred = inferNoFieldDifference(summary.matchPassRate(), sent, ignored, sameFail, bothSuccess);
        FieldDifferenceCounts fieldCounts = legacy ? new FieldDifferenceCounts(inferred, 0L)
                : fieldDifferenceCounts(issues, comparisons, inferred);
        long noFieldDifference = fieldCounts.noFieldDifference();

        ReplayDailySummaryCalculatedRow row = new ReplayDailySummaryCalculatedRow(
                summary.batchNo(), domain, summary.coveredInterfaceCount(), summary.sentTransactionCount(),
                c528SuccessCcbsFail, c528FailCcbsSuccess, summary.bothFailSameCode(), bothFailDiffCode,
                summary.bothSuccess(), noAction, summary.codeIgnored(), noFieldDifference,
                rate(sameFail + bothSuccess, adjustedDenominator),
                rate(Math.addExact(Math.addExact(noFieldDifference, sameFail), fieldCounts.accepted()),
                        adjustedDenominator), issueTotal,
                statistics.codeIssueCount(), statistics.parameterIssueCount(),
                statistics.reasonableDifferenceIssueCount(), statistics.peripheralIssueCount(),
                statistics.platformIssueCount(), statistics.newCoreOfflineIssueCount(),
                statistics.ruleDifferenceIssueCount(), statistics.migrationIssueCount(),
                statistics.antiCorrosionIssueCount(), statistics.otherIssueCount(),
                statistics.investigationProgress(), unresolved.unanalyzed(),
                unresolved.analyzedPendingFix(), unresolved.notFullyFixed(),
                unresolved.dataMigrationIssue(), unresolved.codeNotReleased(), unresolved.total(),
                unresolved.resolutionRate(), summary.sourceRow(), summary.rawJson());
        return new RowCalculation(row, statistics.analyzedCount(), fieldCounts.accepted(), adjustedDenominator);
    }

    private Map<ComparisonDomain, List<ReplayInterfaceComparisonRow>> indexComparisons(
            List<ReplayInterfaceComparisonRow> comparisons) {
        Map<ComparisonDomain, List<ReplayInterfaceComparisonRow>> result = new LinkedHashMap<>();
        Map<ComparisonTransaction, String> domainsByTransaction = new LinkedHashMap<>();
        for (ReplayInterfaceComparisonRow row : comparisons) {
            if (row.rowType() != ReplayDailyRowType.DETAIL || normalizeText(row.domain()).isEmpty()) {
                continue;
            }
            ComparisonDomain key = new ComparisonDomain(normalizeText(row.batchNo()), displayDomain(row.domain()));
            String code = normalizeText(row.transactionCode());
            if (!code.isEmpty()) {
                String previousDomain = domainsByTransaction.putIfAbsent(
                        new ComparisonTransaction(key.batchNo(), code), key.domain());
                if (previousDomain != null && !previousDomain.equals(key.domain())) {
                    throw new IllegalArgumentException("接口比对明细交易码所属领域不唯一：" + key.batchNo() + " / " + code);
                }
            }
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private Map<String, List<ReplayDailyIssueStatisticRow>> indexTransactions(List<ReplayDailyIssueStatisticRow> issues) {
        Map<Long, ReplayDailyIssueStatisticRow> unique = new LinkedHashMap<>();
        issues.forEach(issue -> unique.putIfAbsent(issue.issueId(), issue));
        Map<String, List<ReplayDailyIssueStatisticRow>> result = new LinkedHashMap<>();
        unique.values().forEach(issue -> {
            String code = normalizeText(issue.transactionCode());
            if (!code.isEmpty()) result.computeIfAbsent(code, ignored -> new ArrayList<>()).add(issue);
        });
        return result;
    }

    private FieldDifferenceCounts fieldDifferenceCounts(List<ReplayDailyIssueStatisticRow> issues,
                                                       List<ReplayInterfaceComparisonRow> comparisons,
                                                       long legacyNoFieldDifference) {
        boolean complete = !comparisons.isEmpty() && comparisons.stream().allMatch(row ->
                row.bothSuccessNoFieldDifference() != null && row.bothSuccessNoFieldDifference() >= 0
                        && row.bothSuccessWithFieldDifference() != null && row.bothSuccessWithFieldDifference() >= 0);
        if (!complete) {
            return new FieldDifferenceCounts(legacyNoFieldDifference, 0L);
        }

        Map<String, List<ReplayDailyIssueStatisticRow>> fieldsByTransaction = new LinkedHashMap<>();
        for (ReplayDailyIssueStatisticRow issue : issues) {
            String code = normalizeText(issue.transactionCode());
            if ("字段级".equals(normalizeText(issue.issueLevel())) && !code.isEmpty()) {
                fieldsByTransaction.computeIfAbsent(code, ignored -> new ArrayList<>()).add(issue);
            }
        }
        Set<String> acceptedTransactions = new LinkedHashSet<>();
        fieldsByTransaction.forEach((code, fields) -> {
            boolean hasApprovedNoAction = fields.stream().anyMatch(issue ->
                    NO_ACTION.equals(normalizeText(issue.issueStatus()))
                            && issue.reviewStatus() == ReplayIssueReviewStatus.APPROVED);
            if (hasApprovedNoAction && fields.stream().allMatch(issue -> issue.defectRepairDate() != null)) {
                acceptedTransactions.add(code);
            }
        });

        long noFieldDifference = 0L;
        long accepted = 0L;
        for (ReplayInterfaceComparisonRow row : comparisons) {
            noFieldDifference = Math.addExact(noFieldDifference, row.bothSuccessNoFieldDifference());
            if (acceptedTransactions.contains(normalizeText(row.transactionCode()))) {
                accepted = Math.addExact(accepted, row.bothSuccessWithFieldDifference());
            }
        }
        return new FieldDifferenceCounts(noFieldDifference, accepted);
    }

    private long affectedCount(List<ReplayDailyIssueStatisticRow> issues, String expectedField) {
        return issues.stream()
                .filter(ReplayDailyReportCalculator::isTransactionLevel)
                .filter(issue -> !NO_ACTION.equals(normalizeText(issue.issueStatus())))
                .filter(issue -> expectedField.equals(normalizeField(issue.fieldName())))
                .mapToLong(ReplayDailyIssueStatisticRow::affectedTransactionCount)
                .sum();
    }

    private Map<String, List<ReplayDailyIssueStatisticRow>> indexIssues(
            List<ReplayDailyIssueStatisticRow> issues) {
        Map<String, Map<Long, ReplayDailyIssueStatisticRow>> uniqueByDomain = new LinkedHashMap<>();
        for (ReplayDailyIssueStatisticRow issue : issues) {
            uniqueByDomain.computeIfAbsent(
                            displayDomain(issue.groupName(), issue.sandbox()), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(issue.issueId(), issue);
        }
        Map<String, List<ReplayDailyIssueStatisticRow>> result = new LinkedHashMap<>();
        uniqueByDomain.forEach((domain, uniqueIssues) ->
                result.put(domain, List.copyOf(uniqueIssues.values())));
        return result;
    }

    private List<ReplayDailyIssueStatisticRow> reportableIssues(
            List<ReplayDailyIssueStatisticRow> issues) {
        return issues.stream()
                .filter(issue -> !NO_ACTION.equals(normalizeText(issue.issueStatus())))
                .toList();
    }

    private Map<String, PreviousUnresolved> unresolvedByDomain(
            List<ReplayDailySummaryRow> previousSummaries,
            Map<String, List<ReplayDailyIssueStatisticRow>> previousByDomain) {
        Map<String, PreviousUnresolved> result = new LinkedHashMap<>();
        for (ReplayDailySummaryRow summary : previousSummaries) {
            String domain = displayDomain(summary.domain());
            List<ReplayDailyIssueStatisticRow> issues = previousByDomain.getOrDefault(domain, List.of());
            result.put(domain, previousUnresolved(
                    issues, issues.size()));
        }
        return result;
    }

    private PreviousUnresolved totalPreviousUnresolved(
            List<ReplayDailySummaryRow> previousSummaries,
            Map<String, List<ReplayDailyIssueStatisticRow>> previousByDomain,
            Map<String, PreviousUnresolved> unresolvedByDomain) {
        long issueTotal = 0L;
        long unanalyzed = 0L;
        long analyzedPendingFix = 0L;
        long notFullyFixed = 0L;
        long dataMigrationIssue = 0L;
        long codeNotReleased = 0L;
        for (ReplayDailySummaryRow summary : previousSummaries) {
            String domain = displayDomain(summary.domain());
            PreviousUnresolved unresolved = unresolvedByDomain.getOrDefault(domain, PreviousUnresolved.empty());
            issueTotal += previousByDomain.getOrDefault(domain, List.of()).size();
            unanalyzed += unresolved.unanalyzed();
            analyzedPendingFix += unresolved.analyzedPendingFix();
            notFullyFixed += unresolved.notFullyFixed();
            dataMigrationIssue += unresolved.dataMigrationIssue();
            codeNotReleased += unresolved.codeNotReleased();
        }
        return PreviousUnresolved.of(issueTotal, unanalyzed, analyzedPendingFix, notFullyFixed,
                dataMigrationIssue, codeNotReleased);
    }

    private PreviousUnresolved previousUnresolved(List<ReplayDailyIssueStatisticRow> issues, long issueTotal) {
        long unanalyzed = 0L;
        long analyzedPendingFix = 0L;
        long notFullyFixed = 0L;
        long dataMigrationIssue = 0L;
        long codeNotReleased = 0L;
        for (ReplayDailyIssueStatisticRow issue : issues) {
            String status = normalizeText(issue.issueStatus());
            if (NEW.equals(status)) {
                if (issue.reopenedAfterFixed()) {
                    notFullyFixed++;
                } else {
                    unanalyzed++;
                }
            } else if ("打开".equals(status)) {
                analyzedPendingFix++;
            } else if ("重新打开".equals(status)) {
                notFullyFixed++;
            } else if ("延后修复".equals(status)) {
                dataMigrationIssue++;
            } else if ("修复待验证".equals(status)) {
                codeNotReleased++;
            }
        }
        return PreviousUnresolved.of(issueTotal, unanalyzed, analyzedPendingFix, notFullyFixed,
                dataMigrationIssue, codeNotReleased);
    }

    private IssueStatistics issueStatistics(List<ReplayDailyIssueStatisticRow> issues, long issueTotal) {
        long[] counts = new long[ISSUE_TYPES.size()];
        long analyzed = 0L;
        for (ReplayDailyIssueStatisticRow issue : issues) {
            if (isAnalyzed(issue)) {
                analyzed++;
                counts[issueTypeIndex(issue.issueType())]++;
            }
        }
        return new IssueStatistics(
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7],
                counts[8], counts[9], analyzed, rate(analyzed, issueTotal));
    }

    private ReplayDailySummaryCalculatedRow total(List<RowCalculation> calculations,
                                                  PreviousUnresolved previousUnresolved) {
        long coveredInterfaceCount = 0L;
        long sentTransactionCount = 0L;
        long c528SuccessCcbsFail = 0L;
        long c528FailCcbsSuccess = 0L;
        long bothFailSameCode = 0L;
        long bothFailDiffCode = 0L;
        long bothSuccess = 0L;
        long noAction = 0L;
        long codeIgnored = 0L;
        long noFieldDifference = 0L;
        long acceptedFieldDifference = 0L;
        long denominator = 0L;
        long issueTotal = 0L;
        long codeIssueCount = 0L;
        long parameterIssueCount = 0L;
        long reasonableDifferenceIssueCount = 0L;
        long peripheralIssueCount = 0L;
        long platformIssueCount = 0L;
        long newCoreOfflineIssueCount = 0L;
        long ruleDifferenceIssueCount = 0L;
        long migrationIssueCount = 0L;
        long antiCorrosionIssueCount = 0L;
        long otherIssueCount = 0L;
        long analyzedCount = 0L;
        String batchNo = null;

        for (RowCalculation calculation : calculations) {
            ReplayDailySummaryCalculatedRow row = calculation.row();
            if (batchNo == null) {
                batchNo = row.batchNo();
            }
            coveredInterfaceCount += value(row.coveredInterfaceCount());
            sentTransactionCount += value(row.sentTransactionCount());
            c528SuccessCcbsFail += row.c528SuccessCcbsFail();
            c528FailCcbsSuccess += row.c528FailCcbsSuccess();
            bothFailSameCode += value(row.bothFailSameCode());
            bothFailDiffCode += row.bothFailDiffCode();
            bothSuccess += value(row.bothSuccess());
            noAction += row.noAction();
            codeIgnored += value(row.codeIgnored());
            noFieldDifference = Math.addExact(noFieldDifference, row.bothSuccessNoFieldDifference());
            acceptedFieldDifference = Math.addExact(acceptedFieldDifference, calculation.acceptedFieldDifference());
            denominator = Math.addExact(denominator, calculation.rateDenominator());
            issueTotal += value(row.issueTotal());
            codeIssueCount += row.codeIssueCount();
            parameterIssueCount += row.parameterIssueCount();
            reasonableDifferenceIssueCount += row.reasonableDifferenceIssueCount();
            peripheralIssueCount += row.peripheralIssueCount();
            platformIssueCount += row.platformIssueCount();
            newCoreOfflineIssueCount += row.newCoreOfflineIssueCount();
            ruleDifferenceIssueCount += row.ruleDifferenceIssueCount();
            migrationIssueCount += row.migrationIssueCount();
            antiCorrosionIssueCount += row.antiCorrosionIssueCount();
            otherIssueCount += row.otherIssueCount();
            analyzedCount += calculation.analyzedCount();
        }

        return new ReplayDailySummaryCalculatedRow(
                batchNo, "合计", coveredInterfaceCount, sentTransactionCount,
                c528SuccessCcbsFail, c528FailCcbsSuccess, bothFailSameCode, bothFailDiffCode,
                bothSuccess, noAction, codeIgnored, noFieldDifference,
                rate(bothFailSameCode + bothSuccess, denominator),
                rate(Math.addExact(Math.addExact(noFieldDifference, bothFailSameCode), acceptedFieldDifference),
                        denominator), issueTotal,
                codeIssueCount, parameterIssueCount, reasonableDifferenceIssueCount,
                peripheralIssueCount, platformIssueCount, newCoreOfflineIssueCount,
                ruleDifferenceIssueCount, migrationIssueCount, antiCorrosionIssueCount,
                otherIssueCount, rate(analyzedCount, issueTotal), previousUnresolved.unanalyzed(),
                previousUnresolved.analyzedPendingFix(), previousUnresolved.notFullyFixed(),
                previousUnresolved.dataMigrationIssue(), previousUnresolved.codeNotReleased(),
                previousUnresolved.total(), previousUnresolved.resolutionRate(), 0, null);
    }

    private static boolean isAnalyzed(ReplayDailyIssueStatisticRow issue) {
        return !NEW.equals(normalizeText(issue.issueStatus())) || issue.reopenedAfterFixed();
    }

    private static int issueTypeIndex(String issueType) {
        String normalized = normalizeText(issueType);
        int index = ISSUE_TYPES.indexOf(normalized);
        return index < 0 ? ISSUE_TYPES.size() - 1 : index;
    }

    private static boolean isTransactionLevel(ReplayDailyIssueStatisticRow issue) {
        return TRANSACTION_LEVEL.equals(normalizeText(issue.issueLevel()));
    }

    private static long inferNoFieldDifference(BigDecimal sourceMatchRate, long sent, long ignored,
                                               long sameFail, long bothSuccess) {
        long sourceDenominator = sent - ignored;
        if (sourceMatchRate == null || sourceDenominator <= 0) {
            return 0L;
        }
        BigDecimal inferred = sourceMatchRate.multiply(BigDecimal.valueOf(sourceDenominator))
                .subtract(BigDecimal.valueOf(sameFail))
                .setScale(0, RoundingMode.HALF_UP);
        if (inferred.signum() <= 0) {
            return 0L;
        }
        return inferred.compareTo(BigDecimal.valueOf(bothSuccess)) > 0
                ? bothSuccess : inferred.longValueExact();
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static String displayDomain(String groupName, boolean sandbox) {
        String normalized = normalizeText(groupName);
        return sandbox ? "沙箱-" + normalized : normalized;
    }

    private static String displayDomain(String domain) {
        String normalized = normalizeText(domain);
        if (!normalized.startsWith("沙箱")) {
            return normalized;
        }
        String groupName = normalized.substring("沙箱".length()).replaceFirst("^[-—_]", "").trim();
        return "沙箱-" + groupName;
    }

    private static String normalizeField(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT).replaceAll("[\\s/／]", "");
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<ReplayDailySummaryCalculatedRow> rows(List<RowCalculation> calculations) {
        return calculations.stream().map(RowCalculation::row).toList();
    }

    public record PreviousUnresolved(
            long unanalyzed,
            long analyzedPendingFix,
            long notFullyFixed,
            long dataMigrationIssue,
            long codeNotReleased,
            long total,
            BigDecimal resolutionRate) {

        private static PreviousUnresolved of(long issueTotal, long unanalyzed, long analyzedPendingFix,
                                             long notFullyFixed, long dataMigrationIssue,
                                             long codeNotReleased) {
            long total = unanalyzed + analyzedPendingFix + notFullyFixed + dataMigrationIssue + codeNotReleased;
            return new PreviousUnresolved(unanalyzed, analyzedPendingFix, notFullyFixed,
                    dataMigrationIssue, codeNotReleased, total, rate(issueTotal - total, issueTotal));
        }

        private static PreviousUnresolved empty() {
            return of(0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    public record CalculatedReport(
            List<ReplayDailySummaryCalculatedRow> previousRows,
            ReplayDailySummaryCalculatedRow previousTotal,
            List<ReplayDailySummaryCalculatedRow> currentRows,
            ReplayDailySummaryCalculatedRow currentTotal,
            PreviousUnresolved previousUnresolved) {

        public CalculatedReport {
            previousRows = List.copyOf(previousRows);
            currentRows = List.copyOf(currentRows);
        }
    }

    private record RowCalculation(ReplayDailySummaryCalculatedRow row, long analyzedCount,
                                  long acceptedFieldDifference, long rateDenominator) {
    }

    private record ComparisonDomain(String batchNo, String domain) {
    }

    private record ComparisonTransaction(String batchNo, String transactionCode) {
    }

    private record FieldDifferenceCounts(long noFieldDifference, long accepted) {
    }

    private record IssueStatistics(
            long codeIssueCount,
            long parameterIssueCount,
            long reasonableDifferenceIssueCount,
            long peripheralIssueCount,
            long platformIssueCount,
            long newCoreOfflineIssueCount,
            long ruleDifferenceIssueCount,
            long migrationIssueCount,
            long antiCorrosionIssueCount,
            long otherIssueCount,
            long analyzedCount,
            BigDecimal investigationProgress) {
    }
}
