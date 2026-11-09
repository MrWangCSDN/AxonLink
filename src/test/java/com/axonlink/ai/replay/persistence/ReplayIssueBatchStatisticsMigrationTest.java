package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueBatchStatisticsMigrationTest {
    @Test
    void leavesLegacyHistoryUnknownAndEnforcesOneOpenBatchPerFamily() {
        var jdbc = ReplayIssueTestFixtures.newJdbc();
        jdbc.execute("CREATE TABLE dii_replay_issue_occurrence_batch (replay_issue_id BIGINT,batch_name VARCHAR(128))");
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch VALUES (1,'RPT20260901-01')");

        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V88__replay_issue_batch_statistics_snapshot.sql"))
                .execute(jdbc.getDataSource());

        assertNull(jdbc.queryForObject("SELECT statistics_snapshot FROM dii_replay_issue_occurrence_batch", String.class));
        assertNull(jdbc.queryForObject("SELECT statistics_recorded_at FROM dii_replay_issue_occurrence_batch", LocalDateTime.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_statistics_batch", Integer.class));
        var now = LocalDateTime.of(2026, 9, 1, 9, 0);
        jdbc.update("INSERT INTO dii_replay_issue_statistics_batch VALUES (?,'RPT','OPEN','RPT',?,?)", "RPT20260901-01", now, now);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO dii_replay_issue_statistics_batch VALUES (?,'RPT','OPEN','RPT',?,?)", "RPT20260902-01", now, now));
        jdbc.update("INSERT INTO dii_replay_issue_statistics_batch VALUES (?,'DZ','OPEN','DZ',?,?)", "DZ20260901-01", now, now);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_statistics_batch", Integer.class));
    }
}
