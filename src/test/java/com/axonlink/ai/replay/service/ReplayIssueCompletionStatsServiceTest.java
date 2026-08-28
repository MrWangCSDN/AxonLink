package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.persistence.ReplayIssueCompletionStatsDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueCompletionStatsServiceTest {

    private ReplayIssueCompletionStatsService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
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
    void datePointsDefaultToTheLatestThreeRealDates() {
        var response = service.datePoints();

        assertEquals(4, response.datePoints().size());
        assertEquals(LocalDate.of(2026, 8, 22), response.defaultStartDate());
        assertEquals(LocalDate.of(2026, 8, 28), response.defaultEndDate());
    }

    @Test
    void dashboardWithoutBoundsUsesLatestThreeAndServerToday() {
        var dashboard = service.dashboard(null, null);

        assertEquals(LocalDate.of(2026, 8, 22), dashboard.effectiveStartDate());
        assertEquals(LocalDate.of(2026, 8, 28), dashboard.effectiveEndDate());
        assertEquals(LocalDate.of(2026, 8, 27), dashboard.today());
        assertEquals(3, dashboard.summary().plannedTotal());
        assertEquals(2, dashboard.summary().overdueUnfinishedCount());
        assertEquals(1, dashboard.summary().unfinishedCount());
    }

    @Test
    void missingDatesSnapInwardToExistingPoints() {
        var dashboard = service.dashboard("2026-08-21", "2026-08-27");

        assertEquals(LocalDate.of(2026, 8, 22), dashboard.effectiveStartDate());
        assertEquals(LocalDate.of(2026, 8, 26), dashboard.effectiveEndDate());
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

        assertNull(empty.datePoints().defaultStartDate());
        assertEquals(0, empty.dashboard(null, null).summary().plannedTotal());
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

    private void assertRangeError(String start, String end) {
        ReplayIssueCompletionRangeException error = assertThrows(
                ReplayIssueCompletionRangeException.class, () -> service.dashboard(start, end));
        assertEquals("计划验证日期范围不合法", error.getMessage());
    }
}
