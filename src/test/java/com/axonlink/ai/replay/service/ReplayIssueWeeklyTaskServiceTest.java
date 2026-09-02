package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueWeeklyTaskConfig;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayIssueWeeklyTaskDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueWeeklyTaskServiceTest {

    private JdbcTemplate jdbc;
    private ReplayIssueWeeklyTaskService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        service = new ReplayIssueWeeklyTaskService(new ReplayIssueWeeklyTaskDao(jdbc));

        ReplayIssueDao issueDao = new ReplayIssueDao(jdbc);
        issueDao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-1", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-2", "second"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-3", "third")),
                LocalDateTime.of(2026, 8, 19, 9, 0));
        jdbc.batchUpdate("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,?,?,?,?,? FROM dii_replay_issue WHERE issue_key=?",
                List.of(
                        new Object[]{"BATCH-A", timestamp(), timestamp(), timestamp(), timestamp(), "key-1"},
                        new Object[]{"BATCH-A", timestamp(), timestamp(), timestamp(), timestamp(), "key-2"},
                        new Object[]{"BATCH-B", timestamp(), timestamp(), timestamp(), timestamp(), "key-2"},
                        new Object[]{"BATCH-B", timestamp(), timestamp(), timestamp(), timestamp(), "key-3"}));
    }

    @Test
    void replaceNormalizesNamesAndCountsIssueUnionOnce() {
        ReplayIssueWeeklyTaskConfig result = service.replace(List.of(" BATCH-B ", "BATCH-A", "BATCH-B"));

        assertEquals(List.of("BATCH-A", "BATCH-B"), result.batchNames());
        assertEquals(List.of("BATCH-A", "BATCH-B", "RPT20260820-142055-0001",
                "RPT20260820-142055-0002", "RPT20260820-142055-0003"), result.availableBatchNames());
        assertEquals(3L, result.issueCount());
        assertEquals(List.of("BATCH-A", "BATCH-B"), service.current().batchNames());
    }

    @Test
    void replaceRejectsUnknownBatchAndKeepsPreviousConfiguration() {
        service.replace(List.of("BATCH-A"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.replace(List.of("BATCH-NOT-FOUND")));

        assertEquals("出现批次不存在：BATCH-NOT-FOUND", error.getMessage());
        assertEquals(List.of("BATCH-A"), service.current().batchNames());
    }

    @Test
    void emptyReplacementClearsConfiguration() {
        service.replace(List.of("BATCH-A"));

        ReplayIssueWeeklyTaskConfig result = service.replace(java.util.Arrays.asList(" ", null));

        assertEquals(List.of(), result.batchNames());
        assertEquals(0L, result.issueCount());
    }

    private static LocalDateTime timestamp() {
        return LocalDateTime.of(2026, 8, 19, 10, 0);
    }
}
