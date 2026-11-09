package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplayIssueSentTransactionCountMigrationTest {

    @Test
    void addsNullableBigintWithoutGuessingHistoricalCountsOrChangingManualFields() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        jdbc.execute("CREATE TABLE dii_replay_issue (id BIGINT PRIMARY KEY, transaction_code VARCHAR(64), remark VARCHAR(128))");
        jdbc.update("INSERT INTO dii_replay_issue(id,transaction_code,remark) VALUES (1,'006208','人工备注')");

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V87__replay_issue_sent_transaction_count.sql"))
                .execute(jdbc.getDataSource());

        assertNull(jdbc.queryForObject("SELECT sent_transaction_count FROM dii_replay_issue WHERE id=1", Long.class));
        assertEquals("人工备注", jdbc.queryForObject("SELECT remark FROM dii_replay_issue WHERE id=1", String.class));
        jdbc.update("UPDATE dii_replay_issue SET sent_transaction_count=? WHERE id=1", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject(
                "SELECT sent_transaction_count FROM dii_replay_issue WHERE id=1", Long.class));
        jdbc.update("UPDATE dii_replay_issue SET sent_transaction_count=NULL WHERE id=1");
        assertNull(jdbc.queryForObject("SELECT sent_transaction_count FROM dii_replay_issue WHERE id=1", Long.class));
    }
}
