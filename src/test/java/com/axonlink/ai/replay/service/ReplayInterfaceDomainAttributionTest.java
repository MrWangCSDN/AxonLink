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

class ReplayInterfaceDomainAttributionTest {
    private static final String BATCH = "RPT20260902-01";
    private final ReplayDailyReportCalculator calculator = new ReplayDailyReportCalculator();

    @Test
    void attributesTransactionAndAcceptedFieldCountsToInterfaceDomainRegardlessOfIssueGroup() {
        var transaction = issue(1L, "006208", "交易级", "无需处理", 7L, true);
        var report = calculator.calculate(List.of(), List.of(), List.of(), List.of(summary("存款组")),
                List.of(transaction, transaction,
                        issue(2L, "FIELD", "字段级", "无需处理", 0L, true),
                        issue(3L, "FIELD", "字段级", "已修复", 0L, true),
                        issue(4L, "OTHER", "交易级", "无需处理", 50L, true),
                        issue(5L, "FAIL", "交易级", "打开", 3L, false)),
                List.of(detail("存款组", "006208", "S1", 10L, 0L),
                        detail("存款组", "006208", "S2", 20L, 0L),
                        detail("存款组", "FIELD", "S3", 30L, 20L),
                        detail("存款组", "FAIL", "S4", 0L, 0L)));

        var row = report.currentRows().get(0);
        assertEquals(7L, row.noAction());
        assertEquals(3L, row.c528SuccessCcbsFail());
        assertEquals(new BigDecimal("0.91397849"), row.matchPassRate());
        assertEquals(0L, row.issueTotal(), "问题条数仍按问题组，交易归属不改变问题责任组");
    }

    @Test
    void anUnrepairedFieldInAnotherIssueGroupStillBlocksTheTransaction() {
        var blocked = new ReplayDailyIssueStatisticRow(3L, "贷款组", true, "代码问题", "字段级", "其他字段",
                "打开", 0L, false, null, "FIELD", null);
        var accepted = new ReplayDailyIssueStatisticRow(1L, "存款组", false, "合理差异", "字段级", "字段1",
                "无需处理", 0L, false, ReplayIssueReviewStatus.APPROVED, "FIELD", LocalDate.of(2026, 9, 2));
        var report = calculator.calculate(List.of(), List.of(), List.of(), List.of(summary("存款组")),
                List.of(accepted, blocked),
                List.of(detail("存款组", "FIELD", "S1", 60L, 20L)));

        assertEquals(new BigDecimal("0.65000000"), report.currentRows().get(0).matchPassRate());
    }

    @Test
    void missingInterfaceMembershipCannotUseIssueGroupAsAFallback() {
        var report = calculator.calculate(List.of(), List.of(), List.of(), List.of(summary("公共组")),
                List.of(issue(1L, "MISSING", "交易级", "无需处理", 7L, true)), List.of());

        assertEquals(0L, report.currentRows().get(0).noAction());
        assertEquals(new BigDecimal("0.85000000"), report.currentRows().get(0).matchPassRate());
    }

    @Test
    void aTransactionCannotBeChargedToTwoInterfaceDomains() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                List.of(), List.of(), List.of(), List.of(summary("存款组"), summary("贷款组")),
                List.of(issue(1L, "SAME", "交易级", "无需处理", 7L, true)),
                List.of(detail("存款组", "SAME", "S1", 30L, 0L),
                        detail("贷款组", "SAME", "S2", 30L, 0L))));
    }

    private static ReplayDailySummaryRow summary(String domain) {
        return new ReplayDailySummaryRow(BATCH, domain, 1L, 100L, 5L, 80L, 0L,
                new BigDecimal("0.90"), 0L, 1, "{}");
    }

    private static ReplayDailyIssueStatisticRow issue(long id, String code, String level, String status,
                                                      long affected, boolean repaired) {
        return new ReplayDailyIssueStatisticRow(id, "公共组", false, "合理差异", level, "528成功ccbs失败",
                status, affected, false, ReplayIssueReviewStatus.APPROVED, code,
                repaired ? LocalDate.of(2026, 9, 2) : null);
    }

    private static ReplayInterfaceComparisonRow detail(String domain, String code, String sCode, long noDiff, long withDiff) {
        return new ReplayInterfaceComparisonRow(BATCH, ReplayDailyRowType.DETAIL, code, sCode, "交易", "开发", "行内",
                domain, 100L, 0L, 0L, 5L, 0L, noDiff + withDiff, 0L, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}", noDiff, withDiff);
    }
}
