package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.persistence.ReplayIssueCompletionStatsDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.dto.ReplayIssueReplayType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueCompletionStatsServiceTest {

    private ReplayIssueCompletionStatsService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        ReplayIssueDao issueDao = new ReplayIssueDao(jdbc);
        issueDao.replaceAll(java.util.stream.IntStream.rangeClosed(1, 4)
                        .mapToObj(index -> ReplayIssueTestFixtures.row(
                                "公共组", false, index, "62" + index, "范围问题" + index)).toList(),
                LocalDateTime.of(2026, 8, 27, 9, 0));
        List<String> dates = List.of("2026-08-20", "2026-08-22", "2026-08-26", "2026-08-28");
        for (int index = 0; index < dates.size(); index++) {
            jdbc.update("UPDATE dii_replay_issue SET planned_completion_date=? WHERE issue_key=?",
                    dates.get(index), "key-" + (index + 1));
        }
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
        service = new ReplayIssueCompletionStatsService(new ReplayIssueCompletionStatsDao(jdbc), clock);
    }

    @Test
    void datePointsIncludeServerTodayPreviousAndNextDayEvenWhenEmpty() {
        var response = service.datePoints();

        assertEquals(5, response.datePoints().size());
        assertEquals(LocalDate.of(2026, 8, 26), response.defaultStartDate());
        assertEquals(LocalDate.of(2026, 8, 28), response.defaultEndDate());
        assertEquals(List.of(
                        LocalDate.of(2026, 8, 20),
                        LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 26),
                        LocalDate.of(2026, 8, 27),
                        LocalDate.of(2026, 8, 28)),
                response.datePoints().stream().map(point -> point.date()).toList());
        assertEquals(List.of(1L, 1L, 1L, 0L, 1L),
                response.datePoints().stream().map(point -> point.plannedCount()).toList());
    }

    @Test
    void dashboardWithoutBoundsUsesServerThreeDayRange() {
        var dashboard = service.dashboard(null, null);

        assertEquals(LocalDate.of(2026, 8, 26), dashboard.effectiveStartDate());
        assertEquals(LocalDate.of(2026, 8, 28), dashboard.effectiveEndDate());
        assertEquals(LocalDate.of(2026, 8, 27), dashboard.today());
        assertEquals(2, dashboard.summary().plannedTotal());
        assertEquals(1, dashboard.groups().size());
    }

    @Test
    void missingDatesSnapInwardToExistingPoints() {
        var dashboard = service.dashboard("2026-08-21", "2026-08-27");

        assertEquals(LocalDate.of(2026, 8, 22), dashboard.effectiveStartDate());
        assertEquals(LocalDate.of(2026, 8, 27), dashboard.effectiveEndDate());
        assertEquals(2, dashboard.summary().plannedTotal());
    }

    @Test
    void oneMissingBoundUsesTheFullAxisBoundary() {
        var withoutStart = service.dashboard(null, "2026-08-26");
        var withoutEnd = service.dashboard("2026-08-22", null);

        assertEquals(LocalDate.of(2026, 8, 20), withoutStart.effectiveStartDate());
        assertEquals(LocalDate.of(2026, 8, 26), withoutStart.effectiveEndDate());
        assertEquals(LocalDate.of(2026, 8, 22), withoutEnd.effectiveStartDate());
        assertEquals(LocalDate.of(2026, 8, 28), withoutEnd.effectiveEndDate());
    }

    @Test
    void rejectsMalformedOutOfBoundsAndReversedRanges() {
        assertRangeError("2026/08/20", "2026-08-28");
        assertRangeError("2026-08-19", "2026-08-28");
        assertRangeError("2026-08-20", "2026-08-29");
        assertRangeError("2026-08-27", "2026-08-21");
    }

    @Test
    void emptyDatabaseReturnsEmptyAxisAndDashboard() {
        JdbcTemplate emptyJdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(emptyJdbc);
        ReplayIssueCompletionStatsService empty = new ReplayIssueCompletionStatsService(
                new ReplayIssueCompletionStatsDao(emptyJdbc),
                Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

        assertEquals(LocalDate.of(2026, 8, 26), empty.datePoints().defaultStartDate());
        assertEquals(LocalDate.of(2026, 8, 28), empty.datePoints().defaultEndDate());
        assertEquals(List.of(LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 28)),
                empty.datePoints().datePoints().stream().map(point -> point.date()).toList());
        var dashboard = empty.dashboard(null, null);
        assertEquals(LocalDate.of(2026, 8, 26), dashboard.effectiveStartDate());
        assertEquals(LocalDate.of(2026, 8, 28), dashboard.effectiveEndDate());
        assertEquals(0, dashboard.summary().plannedTotal());
    }

    @Test
    void filtersDashboardAndDrilldownByReplayTypeWithoutChangingRange() {
        jdbc.update("DELETE FROM dii_replay_issue_occurrence_batch");
        insertOccurrenceBatch("key-1", "RPT20260901-001");
        insertOccurrenceBatch("key-2", "DZ20260901-001");
        insertOccurrenceBatch("key-3", "RPT20260902-001");
        insertOccurrenceBatch("key-3", "DZ20260902-001");
        insertOccurrenceBatch("key-4", "20260902-001");

        var queryDashboard = service.dashboard("2026-08-20", "2026-08-28", "domain",
                ReplayIssueReplayType.QUERY);
        var dzDashboard = service.dashboard("2026-08-20", "2026-08-28", "domain",
                ReplayIssueReplayType.DZ);
        assertEquals(2, queryDashboard.summary().plannedTotal());
        assertEquals(2, dzDashboard.summary().plannedTotal());
        assertEquals(queryDashboard.effectiveStartDate(), dzDashboard.effectiveStartDate());
        assertEquals(queryDashboard.effectiveEndDate(), dzDashboard.effectiveEndDate());

        var page = service.issues("2026-08-20", "2026-08-28", "domain",
                ReplayIssueReplayType.QUERY, "公共组", null, "OVERDUE_UNFINISHED", 20, 0);
        assertEquals(2, page.total());
    }

    @Test
    void validatesDrillDownCategoryAndPaging() {
        assertThrows(IllegalArgumentException.class, () -> service.issues(
                "2026-08-20", "2026-08-28", "公共组", null, "UNKNOWN", 20, 0));
        assertThrows(IllegalArgumentException.class, () -> service.issues(
                "2026-08-20", "2026-08-28", "公共组", null, "UNFINISHED", 0, 0));

        var page = service.issues("2026-08-20", "2026-08-28", "公共组", null,
                "UNFINISHED", 20, 0);
        assertEquals(1, page.total());
        assertEquals(LocalDate.of(2026, 8, 27), page.today());
    }

    @Test
    void ordersDevelopersByAscendingCompletionRateAndReconcilesPendingVerificationSubset() {
        JdbcTemplate rankingJdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(rankingJdbc);
        ReplayIssueDao issueDao = new ReplayIssueDao(rankingJdbc);
        var rows = java.util.stream.IntStream.rangeClosed(1, 35)
                .mapToObj(index -> ReplayIssueTestFixtures.row("存款组", false, index,
                        index <= 10 ? "7001" : index <= 20 ? "7002" : index <= 30 ? "7003" : "7004",
                        "完成率排序问题" + index))
                .toList();
        issueDao.replaceAll(rows, LocalDateTime.of(2026, 8, 31, 9, 0));
        for (int index = 1; index <= 35; index++) {
            boolean fixed = index == 1 || index == 11 || index == 12 || index == 21 || index == 22 || index == 31;
            rankingJdbc.update("UPDATE dii_replay_issue SET planned_completion_date=?, defect_repair_date=?, "
                            + "issue_status=? WHERE issue_key=?",
                    "2026-08-31", fixed ? "2026-08-31" : null,
                    !fixed && index % 2 == 0 ? "修复待验证" : "打开", "key-" + index);
        }
        rankingJdbc.batchUpdate("INSERT INTO dii_replay_transaction_person "
                        + "(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                List.of(
                        new Object[]{"存款组", "7001", "交易1", "A负责人", LocalDateTime.of(2026, 8, 31, 8, 0)},
                        new Object[]{"存款组", "7002", "交易2", "C负责人", LocalDateTime.of(2026, 8, 31, 8, 0)},
                        new Object[]{"存款组", "7003", "交易3", "B负责人", LocalDateTime.of(2026, 8, 31, 8, 0)},
                        new Object[]{"存款组", "7004", "交易4", "D负责人", LocalDateTime.of(2026, 8, 31, 8, 0)}));
        ReplayIssueCompletionStatsService rankingService = new ReplayIssueCompletionStatsService(
                new ReplayIssueCompletionStatsDao(rankingJdbc),
                Clock.fixed(Instant.parse("2026-08-31T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

        var dashboard = rankingService.dashboard("2026-08-31", "2026-08-31");

        var developers = dashboard.groups().get(0).developers();
        assertEquals(List.of("A负责人", "B负责人", "C负责人", "D负责人"),
                developers.stream().map(row -> row.matchedDeveloper()).toList());
        assertEquals(List.of("10.00", "20.00", "20.00", "20.00"),
                developers.stream().map(row -> row.counts().completionRate().toPlainString()).toList());
        assertEquals(developers.stream().mapToLong(row -> row.counts().pendingVerificationCount()).sum(),
                dashboard.groups().get(0).counts().pendingVerificationCount());
        assertEquals(dashboard.groups().get(0).counts().pendingVerificationCount(),
                dashboard.summary().pendingVerificationCount());
        assertTrue(dashboard.summary().pendingVerificationCount()
                <= dashboard.summary().unfinishedCount() + dashboard.summary().overdueUnfinishedCount());
    }

    private void assertRangeError(String start, String end) {
        ReplayIssueCompletionRangeException error = assertThrows(
                ReplayIssueCompletionRangeException.class, () -> service.dashboard(start, end));
        assertEquals("计划验证日期范围不合法", error.getMessage());
    }

    private void insertOccurrenceBatch(String issueKey, String batchName) {
        LocalDateTime at = LocalDateTime.of(2026, 9, 1, 9, 0);
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,?,?,?,?,? FROM dii_replay_issue WHERE issue_key=?",
                batchName, at, at, at, at, issueKey);
    }
}
