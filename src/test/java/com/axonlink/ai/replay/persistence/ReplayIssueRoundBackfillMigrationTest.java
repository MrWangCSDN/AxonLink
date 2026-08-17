package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplayIssueRoundBackfillMigrationTest {

    @Test
    void backfillsOnlyManualEditsToTheLatestPrecedingRoundForThatIssue() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        ReplayIssueDao dao = new ReplayIssueDao(jdbc);
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "A", "issue A"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "B", "issue B")),
                LocalDateTime.of(2026, 8, 11, 8, 0));
        Map<String, Object> issueA = dao.list(new ReplayIssueQuery(10, 0, null, null, null, null, "key-1")).get(0);
        Map<String, Object> issueB = dao.list(new ReplayIssueQuery(10, 0, null, null, null, null, "key-2")).get(0);
        long issueAId = ((Number) issueA.get("id")).longValue();
        long issueBId = ((Number) issueB.get("id")).longValue();

        long firstRound = dao.insertImportRound("20260811-001", LocalDateTime.of(2026, 8, 11, 9, 0),
                ReplayIssueOperator.system(), 1);
        long secondRound = dao.insertImportRound("20260811-002", LocalDateTime.of(2026, 8, 11, 11, 0),
                ReplayIssueOperator.system(), 1);
        long unrelatedRound = dao.insertImportRound("20260811-003", LocalDateTime.of(2026, 8, 11, 12, 0),
                ReplayIssueOperator.system(), 1);
        dao.insertIssueRound(firstRound, issueAId, "key-1", true, ReplayIssueStatus.OPEN,
                ReplayIssueStatus.OPEN, "保持", "公共组", 2, LocalDateTime.of(2026, 8, 11, 9, 0));
        dao.insertIssueRound(secondRound, issueAId, "key-1", true, ReplayIssueStatus.OPEN,
                ReplayIssueStatus.OPEN, "保持", "公共组", 2, LocalDateTime.of(2026, 8, 11, 11, 0));
        dao.insertIssueRound(unrelatedRound, issueBId, "key-2", true, ReplayIssueStatus.OPEN,
                ReplayIssueStatus.OPEN, "保持", "公共组", 3, LocalDateTime.of(2026, 8, 11, 12, 0));

        insertHistory(jdbc, issueAId, "人工保存", LocalDateTime.of(2026, 8, 11, 8, 30));
        insertHistory(jdbc, issueAId, "人工保存", LocalDateTime.of(2026, 8, 11, 10, 0));
        insertHistory(jdbc, issueAId, "人工保存", LocalDateTime.of(2026, 8, 11, 11, 30));
        insertHistory(jdbc, issueAId, "全量基础数据覆盖", LocalDateTime.of(2026, 8, 11, 12, 30));

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V42__replay_manual_history_round_backfill.sql"))
                .execute(jdbc.getDataSource());

        List<Map<String, Object>> histories = jdbc.queryForList(
                "SELECT operation_type,context_round_id FROM dii_replay_issue_history ORDER BY operation_at");
        assertNull(histories.get(0).get("context_round_id"));
        assertEquals(firstRound, ((Number) histories.get(1).get("context_round_id")).longValue());
        assertEquals(secondRound, ((Number) histories.get(2).get("context_round_id")).longValue());
        assertNull(histories.get(3).get("context_round_id"));
    }

    private static void insertHistory(JdbcTemplate jdbc, long issueId, String operationType,
                                      LocalDateTime operationAt) {
        jdbc.update("INSERT INTO dii_replay_issue_history "
                        + "(replay_issue_id,issue_key,operation_type,operation_at) VALUES (?,?,?,?)",
                issueId, "key-1", operationType, operationAt);
    }
}
