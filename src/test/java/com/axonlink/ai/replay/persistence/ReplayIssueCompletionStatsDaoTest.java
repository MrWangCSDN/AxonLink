package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionCategory;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionCounts;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDatePoint;
import com.axonlink.ai.replay.dto.ReplayIssueReplayType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayIssueCompletionStatsDaoTest {

    private JdbcTemplate jdbc;
    private ReplayIssueCompletionStatsDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        seedIssues();
        dao = new ReplayIssueCompletionStatsDao(jdbc);
    }

    @Test
    void returnsOnlyRealNonZeroDatePointsInAscendingOrder() {
        assertEquals(List.of(
                new ReplayIssueCompletionDatePoint(LocalDate.of(2026, 8, 20), 2),
                new ReplayIssueCompletionDatePoint(LocalDate.of(2026, 8, 25), 2),
                new ReplayIssueCompletionDatePoint(LocalDate.of(2026, 8, 26), 1),
                new ReplayIssueCompletionDatePoint(LocalDate.of(2026, 8, 27), 2)
        ), dao.findDatePoints());
    }

    @Test
    void classifiesEveryIssueOnceAndKeepsDeveloperCombinationAsOneKey() {
        List<ReplayIssueCompletionStatsDao.CompletionAggregateRow> rows = dao.aggregate(
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 27));

        assertEquals(3, rows.size());
        assertAggregate(rows.get(0), "公共组", "张三、李四", ReplayIssueCompletionCounts.of(1, 1, 1, 2, 2));
        assertAggregate(rows.get(1), "存款组", "未匹配负责人", ReplayIssueCompletionCounts.of(0, 0, 1, 0));
        assertAggregate(rows.get(2), "贷款组", "未匹配负责人", ReplayIssueCompletionCounts.of(0, 0, 0, 1));
        assertEquals(7, rows.stream().mapToLong(row -> row.counts().plannedTotal()).sum());
    }

    @Test
    void pagesDrillDownByExactGroupDeveloperAndCategory() {
        var firstPage = dao.findIssues(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 27), "公共组", "张三、李四",
                ReplayIssueCompletionCategory.OVERDUE_UNFINISHED, 1, 0);
        var secondPage = dao.findIssues(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 27), "公共组", "张三、李四",
                ReplayIssueCompletionCategory.OVERDUE_UNFINISHED, 1, 1);

        assertEquals(2, firstPage.total());
        assertEquals("issue-5", firstPage.items().get(0).issueId());
        assertEquals(LocalDate.of(2026, 8, 25), firstPage.items().get(0).plannedCompletionDate());
        assertEquals("张三、李四", firstPage.items().get(0).matchedDeveloper());
        assertEquals("issue-4", secondPage.items().get(0).issueId());
        assertEquals(LocalDate.of(2026, 8, 26), secondPage.items().get(0).plannedCompletionDate());
        assertEquals(LocalDate.of(2026, 8, 27), secondPage.today());
    }

    @Test
    void filtersDashboardAndDrilldownWithoutFilteringGlobalDatePoints() {
        jdbc.update("DELETE FROM dii_replay_issue_occurrence_batch");
        insertOccurrenceBatch("key-1", "RPT20260901-001");
        insertOccurrenceBatch("key-2", "DZ20260901-001");
        insertOccurrenceBatch("key-3", "RPT20260902-001");
        insertOccurrenceBatch("key-3", "DZ20260902-001");

        assertEquals(7, dao.findDatePoints().stream().mapToLong(ReplayIssueCompletionDatePoint::plannedCount).sum());
        assertEquals(2, dao.aggregate(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 27),
                        LocalDate.of(2026, 8, 27), "domain", ReplayIssueReplayType.QUERY).stream()
                .mapToLong(row -> row.counts().plannedTotal()).sum());
        assertEquals(2, dao.aggregate(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 27),
                        LocalDate.of(2026, 8, 27), "domain", ReplayIssueReplayType.DZ).stream()
                .mapToLong(row -> row.counts().plannedTotal()).sum());

        var page = dao.findIssues(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 27), "domain", ReplayIssueReplayType.QUERY,
                "公共组", "张三、李四", ReplayIssueCompletionCategory.ON_TIME_FIXED, 20, 0);
        assertEquals(1, page.total());
        assertEquals("issue-1", page.items().get(0).issueId());
    }

    private static void assertAggregate(ReplayIssueCompletionStatsDao.CompletionAggregateRow actual,
                                        String groupName, String developer,
                                        ReplayIssueCompletionCounts counts) {
        assertEquals(groupName, actual.groupName());
        assertEquals(developer, actual.matchedDeveloper());
        assertEquals(counts, actual.counts());
    }

    private void seedIssues() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> ReplayIssueTestFixtures.row(
                        index <= 5 || index == 8 ? "公共组" : index == 6 ? "存款组" : "贷款组",
                        false, index,
                        index <= 5 || index == 8 ? "6201" : index == 6 ? "6202" : "6203",
                        "统计问题" + index))
                .toList();
        new ReplayIssueDao(jdbc).replaceAll(rows, LocalDateTime.of(2026, 8, 27, 9, 0));
        jdbc.update("INSERT INTO dii_replay_transaction_person " +
                        "(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                "公共组", "6201", "公共交易", "张三、李四", LocalDateTime.of(2026, 8, 27, 8, 0));
        jdbc.update("INSERT INTO dii_replay_transaction_person " +
                        "(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                "存款组", "6202", "存款交易", "  ", LocalDateTime.of(2026, 8, 27, 8, 0));

        updateDates(1, "2026-08-20", "2026-08-20");
        updateDates(2, "2026-08-20", "2026-08-21");
        updateDates(3, "2026-08-27", null);
        updateDates(4, "2026-08-26", null);
        updateDates(5, "2026-08-25", null);
        updateDates(6, "2026-08-27", null);
        updateDates(7, "2026-08-25", null);
        updateDates(8, null, null);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证' WHERE issue_key IN (?,?,?)",
                "key-2", "key-3", "key-5");
    }

    private void updateDates(int rowOrder, String planned, String repaired) {
        jdbc.update("UPDATE dii_replay_issue SET planned_completion_date=?, defect_repair_date=? WHERE issue_key=?",
                planned, repaired, "key-" + rowOrder);
    }

    private void insertOccurrenceBatch(String issueKey, String batchName) {
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,?,?,?,?,? FROM dii_replay_issue WHERE issue_key=?",
                batchName, LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 9, 0),
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 9, 0), issueKey);
    }
}
