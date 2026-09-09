package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayDailyReportCalculatorTest {

    private final ReplayDailyReportCalculator calculator = new ReplayDailyReportCalculator();

    @Test
    void recalculatesIssueTotalsFromUniqueIssuesInsteadOfImportedSummary() {
        ReplayDailySummaryRow previous = summary("RPT-PREVIOUS", "沙箱-存款组",
                20L, 1L, 10L, 0L, "0.55", 168L);
        ReplayDailyIssueStatisticRow newIssue = issue(1L, "存款组", true, "代码问题",
                "交易级", "任意", "新建", 1L, false);
        ReplayDailyIssueStatisticRow openIssue = issue(2L, "存款组", true, "参数问题",
                "交易级", "任意", "打开", 1L, false);

        CalculatedReport report = calculator.calculate(
                List.of(previous), List.of(newIssue, openIssue, openIssue),
                List.of(summary("RPT-CURRENT", "沙箱-存款组", 20L, 1L, 10L, 0L, "0.55", 999L)),
                List.of(issue(3L, "存款组", true, "代码问题",
                        "交易级", "任意", "打开", 1L, false)));

        assertEquals(2L, report.previousRows().get(0).issueTotal());
        assertEquals(2L, report.previousTotal().issueTotal());
        assertEquals(new BigDecimal("0.50000000"), report.previousRows().get(0).investigationProgress());
        assertEquals(2L, report.previousUnresolved().total());
        assertEquals(BigDecimal.ZERO.setScale(8), report.previousUnresolved().resolutionRate());
        assertEquals(1L, report.currentRows().get(0).issueTotal());
        assertEquals(1L, report.currentTotal().issueTotal());
    }

    @Test
    void recalculatesTransactionColumnsByAffectedCountAndExcludesNoAction() {
        ReplayDailySummaryRow currentSummary = summary("RPT-CURRENT", "沙箱-存款组",
                111L, 10L, 80L, 0L, "0.83", 6L);
        List<ReplayDailyIssueStatisticRow> currentIssues = List.of(
                issue(1L, "存款组", true, "代码问题", "交易级", "528成功/CCBS失败", "打开", 7L, false),
                issue(2L, "存款组", true, "参数问题", "交易级", "528失败ccbs成功", "打开", 5L, false),
                issue(3L, "存款组", true, "外围问题", "交易级", "二者都失败响应码不一致", "打开", 3L, false),
                issue(4L, "存款组", true, "代码问题", "交易级", "528成功ccbs失败", "无需处理", 11L, false),
                issue(5L, "存款组", true, "代码问题", "字段级", "528成功ccbs失败", "打开", 100L, false),
                issue(6L, "存款组", false, "代码问题", "交易级", "528成功ccbs失败", "打开", 13L, false));

        CalculatedReport report = calculator.calculate(
                List.of(summary("RPT-PREVIOUS", "存款组", 1L, 0L, 1L, 0L, "1", 0L)),
                List.of(), List.of(currentSummary), currentIssues);
        ReplayDailySummaryCalculatedRow current = report.currentRows().get(0);

        assertEquals(7L, current.c528SuccessCcbsFail());
        assertEquals(5L, current.c528FailCcbsSuccess());
        assertEquals(3L, current.bothFailDiffCode());
        assertEquals(11L, current.noAction());
        assertEquals(new BigDecimal("0.90000000"), current.successRate());
        assertEquals(new BigDecimal("0.90000000"), current.matchPassRate());
        assertEquals(80L, current.bothSuccessNoFieldDifference());
    }

    @Test
    void reversesOldMatchRateWithHalfUpIntegerRoundingAndClampsToBothSuccess() {
        List<ReplayDailySummaryRow> current = List.of(
                summary("RPT-CURRENT", "四舍五入", 10L, 2L, 5L, 0L, "0.46", 0L),
                summary("RPT-CURRENT", "上限", 10L, 2L, 5L, 0L, "1", 0L),
                summary("RPT-CURRENT", "下限", 10L, 2L, 5L, 0L, "0.10", 0L));

        CalculatedReport report = calculator.calculate(
                List.of(summary("RPT-PREVIOUS", "四舍五入", 1L, 0L, 1L, 0L, "1", 0L)),
                List.of(), current, List.of());

        assertEquals(3L, report.currentRows().get(0).bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.50000000"), report.currentRows().get(0).matchPassRate());
        assertEquals(5L, report.currentRows().get(1).bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.70000000"), report.currentRows().get(1).matchPassRate());
        assertEquals(0L, report.currentRows().get(2).bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.20000000"), report.currentRows().get(2).matchPassRate());
    }

    @Test
    void returnsZeroRatesWhenAdjustedDenominatorIsNotPositive() {
        ReplayDailySummaryRow current = summary("RPT-CURRENT", "公共组",
                10L, 2L, 5L, 4L, "0.75", 0L);
        ReplayDailyIssueStatisticRow noAction = issue(1L, "公共组", false, "合理差异", "交易级",
                "其他字段", "无需处理", 6L, false);

        ReplayDailySummaryCalculatedRow row = calculator.calculate(
                List.of(summary("RPT-PREVIOUS", "公共组", 1L, 0L, 1L, 0L, "1", 0L)),
                List.of(), List.of(current), List.of(noAction)).currentRows().get(0);

        assertEquals(BigDecimal.ZERO, row.successRate());
        assertEquals(BigDecimal.ZERO, row.matchPassRate());
    }

    @Test
    void classifiesAllFixedIssueTypesAndFallsBackToOther() {
        ReplayDailySummaryRow previous = summary("RPT-PREVIOUS", "公共组",
                20L, 1L, 10L, 0L, "0.55", 12L);
        List<ReplayDailyIssueStatisticRow> issues = List.of(
                analyzedIssue(1L, "代码问题"),
                analyzedIssue(2L, "参数问题"),
                analyzedIssue(3L, "合理差异"),
                analyzedIssue(4L, "外围问题"),
                analyzedIssue(5L, "平台问题"),
                analyzedIssue(6L, "新核心下线"),
                analyzedIssue(7L, "规则性差异问题"),
                analyzedIssue(8L, "迁移问题"),
                analyzedIssue(9L, "防腐问题"),
                analyzedIssue(10L, ""),
                analyzedIssue(11L, "未识别类型"),
                analyzedIssue(12L, "规则差异问题"));

        ReplayDailySummaryCalculatedRow row = calculator.calculate(
                List.of(previous), issues,
                List.of(summary("RPT-CURRENT", "公共组", 1L, 0L, 1L, 0L, "1", 0L)), List.of())
                .previousRows().get(0);

        assertEquals(1L, row.codeIssueCount());
        assertEquals(1L, row.parameterIssueCount());
        assertEquals(1L, row.reasonableDifferenceIssueCount());
        assertEquals(1L, row.peripheralIssueCount());
        assertEquals(1L, row.platformIssueCount());
        assertEquals(1L, row.newCoreOfflineIssueCount());
        assertEquals(1L, row.ruleDifferenceIssueCount());
        assertEquals(1L, row.migrationIssueCount());
        assertEquals(1L, row.antiCorrosionIssueCount());
        assertEquals(3L, row.otherIssueCount());
        assertEquals(new BigDecimal("1.00000000"), row.investigationProgress());
    }

    @Test
    void fixedToNewCountsAsAnalyzedAndNotFullyFixed() {
        ReplayDailySummaryRow previous = summary("RPT-PREVIOUS", "公共组",
                1L, 0L, 1L, 0L, "1", 1L);
        List<ReplayDailyIssueStatisticRow> previousIssues = List.of(
                issue(1L, "公共组", false, "代码问题", "交易级", "任意", "新建", 1L, true));

        CalculatedReport report = calculator.calculate(
                List.of(previous), previousIssues,
                List.of(summary("RPT-CURRENT", "公共组", 2L, 0L, 2L, 0L, "1", 0L)), List.of());

        assertEquals(1L, report.previousRows().get(0).codeIssueCount());
        assertEquals(new BigDecimal("1.00000000"), report.previousRows().get(0).investigationProgress());
        assertEquals(1L, report.previousUnresolved().notFullyFixed());
        assertEquals(0L, report.previousUnresolved().unanalyzed());
    }

    @Test
    void mapsFivePreviousUnresolvedStatusesAndCalculatesResolutionRate() {
        ReplayDailySummaryRow previous = summary("RPT-PREVIOUS", "公共组",
                10L, 0L, 10L, 0L, "1", 10L);
        List<ReplayDailyIssueStatisticRow> previousIssues = List.of(
                unresolvedIssue(1L, "新建"),
                unresolvedIssue(2L, "打开"),
                unresolvedIssue(3L, "重新打开"),
                unresolvedIssue(4L, "延后修复"),
                unresolvedIssue(5L, "修复待验证"),
                unresolvedIssue(6L, "已修复"));

        CalculatedReport report = calculator.calculate(
                List.of(previous), previousIssues,
                List.of(summary("RPT-CURRENT", "公共组", 10L, 0L, 10L, 0L, "1", 0L)), List.of());
        ReplayDailyReportCalculator.PreviousUnresolved unresolved = report.previousUnresolved();

        assertEquals(1L, unresolved.unanalyzed());
        assertEquals(1L, unresolved.analyzedPendingFix());
        assertEquals(1L, unresolved.notFullyFixed());
        assertEquals(1L, unresolved.dataMigrationIssue());
        assertEquals(1L, unresolved.codeNotReleased());
        assertEquals(5L, unresolved.total());
        assertEquals(new BigDecimal("0.16666667"), unresolved.resolutionRate());
        assertEquals(unresolved.notFullyFixed(), report.currentRows().get(0).notFullyFixed());
        assertEquals(unresolved.total(), report.currentRows().get(0).previousUnresolvedTotal());
        assertEquals(unresolved.resolutionRate(), report.currentRows().get(0).previousResolutionRate());
    }

    @Test
    void calculatesTotalsFromCountsInsteadOfAveragingDomainRates() {
        List<ReplayDailySummaryRow> current = List.of(
                summary("RPT-CURRENT", "甲", 10L, 0L, 10L, 0L, "1", 0L),
                summary("RPT-CURRENT", "乙", 30L, 0L, 0L, 0L, "0", 0L));

        CalculatedReport report = calculator.calculate(
                List.of(summary("RPT-PREVIOUS", "甲", 1L, 0L, 1L, 0L, "1", 0L)),
                List.of(), current, List.of());

        assertEquals(new BigDecimal("0.25000000"), report.currentTotal().successRate());
        assertEquals(new BigDecimal("0.25000000"), report.currentTotal().matchPassRate());
    }

    @Test
    void zeroIssueTotalProducesZeroProgressAndResolutionRate() {
        CalculatedReport report = calculator.calculate(
                List.of(summary("RPT-PREVIOUS", "公共组", 1L, 0L, 1L, 0L, "1", 0L)),
                List.of(),
                List.of(summary("RPT-CURRENT", "公共组", 1L, 0L, 1L, 0L, "1", 0L)),
                List.of());

        assertEquals(BigDecimal.ZERO, report.previousRows().get(0).investigationProgress());
        assertEquals(BigDecimal.ZERO, report.previousUnresolved().resolutionRate());
    }

    private static ReplayDailySummaryRow summary(String batch, String domain, long sent, long sameFail,
                                                  long bothSuccess, long ignored, String matchRate,
                                                  long issueTotal) {
        return new ReplayDailySummaryRow(batch, domain, 3L, sent, sameFail, bothSuccess, ignored,
                new BigDecimal(matchRate), issueTotal, 1, "{}");
    }

    private static ReplayDailyIssueStatisticRow analyzedIssue(long issueId, String issueType) {
        return issue(issueId, "公共组", false, issueType, "字段级", "任意", "已修复", 1L, false);
    }

    private static ReplayDailyIssueStatisticRow unresolvedIssue(long issueId, String status) {
        return issue(issueId, "公共组", false, "代码问题", "字段级", "任意", status, 1L, false);
    }

    private static ReplayDailyIssueStatisticRow issue(long issueId, String groupName, boolean sandbox,
                                                       String issueType, String issueLevel, String fieldName,
                                                       String status, long affectedCount, boolean reopened) {
        return new ReplayDailyIssueStatisticRow(issueId, groupName, sandbox, issueType, issueLevel,
                fieldName, status, affectedCount, reopened);
    }
}
