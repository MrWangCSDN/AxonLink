package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayFieldDifferenceAcceptanceTest {

    private static final String PREVIOUS = "RPT20260901-01";
    private static final String CURRENT = "RPT20260902-01";
    private static final LocalDate REPAIRED = LocalDate.of(2026, 9, 2);
    private final ReplayDailyReportCalculator calculator = new ReplayDailyReportCalculator();

    @Test
    void creditsAnAcceptedTransactionOnceAcrossFieldsAndSCodesWithoutChangingRawNoDifferenceCount() {
        var accepted = issue(1L, "公共组", false, " 006208 ", "字段级", "无需处理",
                ReplayIssueReviewStatus.APPROVED, REPAIRED);
        var fixed = issue(2L, "公共组", false, "006208", "字段级", "已修复", null, REPAIRED);
        List<ReplayInterfaceComparisonRow> comparisons = List.of(
                detail(CURRENT, "公共组", "006208", "S1", 30L, 10L),
                detail(CURRENT, "公共组", "006208", "S2", 20L, 5L),
                detail(CURRENT, "公共组", "6208", "S3", 10L, 5L),
                total(CURRENT, 999L, 999L));

        var report = current(List.of(summary(CURRENT, "公共组", 100L, 5L, 80L, "0.90")),
                List.of(accepted, fixed, accepted), comparisons);
        var row = report.currentRows().get(0);

        assertEquals(60L, row.bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.80000000"), row.matchPassRate());
        assertEquals(new BigDecimal("0.85000000"), row.successRate());
        assertEquals(0L, row.noAction());
        assertEquals(60L, report.currentTotal().bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.80000000"), report.currentTotal().matchPassRate());
    }

    @Test
    void oneUnrepairedFieldBlocksTheWholeTransactionBonus() {
        var row = current(List.of(summary(CURRENT, "公共组", 100L, 5L, 80L, "0.50")),
                List.of(
                        issue(1L, "公共组", false, "A", "字段级", "无需处理",
                                ReplayIssueReviewStatus.APPROVED, REPAIRED),
                        issue(2L, "公共组", false, "A", "字段级", "打开", null, null)),
                List.of(detail(CURRENT, "公共组", "A", "S1", 60L, 20L)))
                .currentRows().get(0);

        assertEquals(60L, row.bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.65000000"), row.matchPassRate());
    }

    @Test
    void requiresAnApprovedNoActionFieldAndItsRepairDateInsteadOfAnyClosedIssue() {
        var row = current(List.of(summary(CURRENT, "公共组", 100L, 0L, 75L, "0.10")),
                List.of(
                        issue(1L, "公共组", false, "PENDING", "字段级", "无需处理",
                                ReplayIssueReviewStatus.PENDING, REPAIRED),
                        issue(2L, "公共组", false, "NO-DATE", "字段级", "无需处理",
                                ReplayIssueReviewStatus.APPROVED, null),
                        issue(3L, "公共组", false, "FIXED", "字段级", "已修复", null, REPAIRED),
                        issue(4L, "公共组", false, "TRANSACTION", "交易级", "无需处理",
                                ReplayIssueReviewStatus.APPROVED, REPAIRED),
                        issue(5L, "公共组", false, "NO-REVIEW", "字段级", "无需处理", null, REPAIRED)),
                List.of(
                        detail(CURRENT, "公共组", "PENDING", "S1", 10L, 5L),
                        detail(CURRENT, "公共组", "NO-DATE", "S2", 10L, 5L),
                        detail(CURRENT, "公共组", "FIXED", "S3", 10L, 5L),
                        detail(CURRENT, "公共组", "TRANSACTION", "S4", 10L, 5L),
                        detail(CURRENT, "公共组", "NO-REVIEW", "S5", 10L, 5L)))
                .currentRows().get(0);

        assertEquals(50L, row.bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.50000000"), row.matchPassRate());
    }

    @Test
    void anyUnknownOrNegativeSplitMakesTheDomainUseTheLegacyBaseWithoutAnyBonus() {
        List<ReplayInterfaceComparisonRow> invalidRows = List.of(
                detail(CURRENT, "公共组", "B", "UNKNOWN-NO", null, 3L),
                detail(CURRENT, "公共组", "B", "UNKNOWN-WITH", 2L, null),
                detail(CURRENT, "公共组", "B", "NEGATIVE-NO", -1L, 3L),
                detail(CURRENT, "公共组", "B", "NEGATIVE-WITH", 2L, -1L));
        for (var invalid : invalidRows) {
            var row = current(List.of(summary(CURRENT, "公共组", 100L, 5L, 80L, "0.50")),
                    List.of(issue(1L, "公共组", false, "A", "字段级", "无需处理",
                            ReplayIssueReviewStatus.APPROVED, REPAIRED)),
                    List.of(detail(CURRENT, "公共组", "A", "S1", 60L, 20L), invalid))
                    .currentRows().get(0);

            assertEquals(45L, row.bothSuccessNoFieldDifference(), invalid.sCode());
            assertEquals(new BigDecimal("0.50000000"), row.matchPassRate(), invalid.sCode());
        }
    }

    @Test
    void keepsPreviousCurrentDomainAndSandboxQualificationsSeparate() {
        var report = calculator.calculate(
                List.of(summary(PREVIOUS, "公共组", 100L, 5L, 80L, "0.10")),
                List.of(issue(1L, "公共组", false, "A", "字段级", "无需处理",
                        ReplayIssueReviewStatus.APPROVED, REPAIRED)),
                List.of(detail(PREVIOUS, "公共组", "A", "S1", 60L, 20L)),
                List.of(summary(CURRENT, "公共组", 100L, 5L, 80L, "0.10"),
                        summary(CURRENT, "沙箱-公共组", 100L, 0L, 50L, "0.10")),
                List.of(issue(2L, "公共组", true, "B", "字段级", "无需处理",
                        ReplayIssueReviewStatus.APPROVED, REPAIRED)),
                List.of(
                        detail(CURRENT, "公共组", "A", "S1", 60L, 20L),
                        detail(CURRENT, "沙箱_公共组", "B", "S2", 40L, 10L),
                        detail(CURRENT, "存款组", "OTHER", "OTHER-DOMAIN", 900L, 900L),
                        detail(PREVIOUS, "公共组", "A", "WRONG-BATCH", 900L, 900L)));

        assertEquals(new BigDecimal("0.85000000"), report.previousRows().get(0).matchPassRate());
        assertEquals(new BigDecimal("0.65000000"), report.currentRows().get(0).matchPassRate());
        assertEquals(new BigDecimal("0.50000000"), report.currentRows().get(1).matchPassRate());
        assertEquals(new BigDecimal("0.57500000"), report.currentTotal().matchPassRate());
    }

    @Test
    void totalsAcceptedNumeratorsBeforeDividingAndPreservesRawFactsInTheTotal() {
        var report = current(
                List.of(summary(CURRENT, "公共组", 100L, 5L, 80L, "0.10"),
                        summary(CURRENT, "存款组", 300L, 15L, 200L, "0.10")),
                List.of(issue(1L, "公共组", false, "A", "字段级", "无需处理",
                        ReplayIssueReviewStatus.APPROVED, REPAIRED)),
                List.of(detail(CURRENT, "公共组", "A", "S1", 60L, 20L),
                        detail(CURRENT, "存款组", "B", "S2", 150L, 50L)));

        assertEquals(new BigDecimal("0.85000000"), report.currentRows().get(0).matchPassRate());
        assertEquals(new BigDecimal("0.55000000"), report.currentRows().get(1).matchPassRate());
        assertEquals(210L, report.currentTotal().bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.62500000"), report.currentTotal().matchPassRate());
        assertEquals(new BigDecimal("0.75000000"), report.currentTotal().successRate());
    }

    @Test
    void rejectsOverflowInsteadOfWrappingSourceOrAcceptedCounts() {
        var sourceSummary = List.of(summary(CURRENT, "公共组", Long.MAX_VALUE, 0L, Long.MAX_VALUE, "0"));
        assertThrows(RuntimeException.class, () -> current(sourceSummary, List.of(),
                List.of(detail(CURRENT, "公共组", "A", "S1", Long.MAX_VALUE, 0L),
                        detail(CURRENT, "公共组", "B", "S2", 1L, 0L))));
        assertThrows(RuntimeException.class, () -> current(sourceSummary,
                List.of(issue(1L, "公共组", false, "A", "字段级", "无需处理",
                        ReplayIssueReviewStatus.APPROVED, REPAIRED)),
                List.of(detail(CURRENT, "公共组", "A", "S1", 0L, Long.MAX_VALUE),
                        detail(CURRENT, "公共组", "A", "S2", 0L, 1L))));
    }

    @Test
    void usesKnownZeroFactsInsteadOfFallingBackToAnImportedRate() {
        var row = current(List.of(summary(CURRENT, "公共组", 100L, 5L, 80L, "0.50")),
                List.of(issue(1L, "公共组", false, "A", "字段级", "无需处理",
                        ReplayIssueReviewStatus.APPROVED, REPAIRED)),
                List.of(detail(CURRENT, "公共组", "A", "S1", 0L, 0L)))
                .currentRows().get(0);

        assertEquals(0L, row.bothSuccessNoFieldDifference());
        assertEquals(new BigDecimal("0.05000000"), row.matchPassRate());
    }

    private ReplayDailyReportCalculator.CalculatedReport current(List<ReplayDailySummaryRow> summaries,
                                                                 List<ReplayDailyIssueStatisticRow> issues,
                                                                 List<ReplayInterfaceComparisonRow> comparisons) {
        return calculator.calculate(List.of(), List.of(), List.of(), summaries, issues, comparisons);
    }

    private static ReplayDailySummaryRow summary(String batch, String domain, long sent,
                                                 long sameFail, long bothSuccess, String matchRate) {
        return new ReplayDailySummaryRow(batch, domain, 1L, sent, sameFail, bothSuccess,
                0L, new BigDecimal(matchRate), 0L, 1, "{}");
    }

    private static ReplayDailyIssueStatisticRow issue(long id, String group, boolean sandbox, String code,
                                                      String level, String status,
                                                      ReplayIssueReviewStatus review, LocalDate repairDate) {
        return new ReplayDailyIssueStatisticRow(id, group, sandbox, "合理差异", level, "字段-" + id,
                status, 0L, false, review, code, repairDate);
    }

    private static ReplayInterfaceComparisonRow detail(String batch, String domain, String code, String sCode,
                                                       Long withoutDifference, Long withDifference) {
        Long successTotal = withoutDifference == null || withDifference == null
                ? null : Math.addExact(withoutDifference, withDifference);
        return new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL, code, sCode,
                "交易", "开发", "行内", domain, 100L, 0L, 0L, 0L, 0L, successTotal, 0L,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}",
                withoutDifference, withDifference);
    }

    private static ReplayInterfaceComparisonRow total(String batch, Long withoutDifference, Long withDifference) {
        return new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.TOTAL, null, null,
                null, null, null, null, 999L, 0L, 0L, 0L, 0L, 1998L, 0L,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 2, "{}",
                withoutDifference, withDifference);
    }
}
