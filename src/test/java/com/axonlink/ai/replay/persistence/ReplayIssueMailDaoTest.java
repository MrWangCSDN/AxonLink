package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayIssueMailDaoTest {
    private JdbcTemplate jdbc;
    private ReplayIssueMailDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        jdbc.execute("CREATE TABLE dii_replay_issue_mail (id BIGINT AUTO_INCREMENT PRIMARY KEY, replay_issue_id BIGINT NOT NULL, issue_key VARCHAR(1024) NOT NULL, recipient_username VARCHAR(128), recipient_email VARCHAR(320) NOT NULL, sender_email VARCHAR(320) NOT NULL, content_hash VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, sent_at TIMESTAMP NULL, failure_message VARCHAR(1000), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, UNIQUE (replay_issue_id, recipient_email, sender_email, content_hash))");
        dao = new ReplayIssueMailDao(jdbc);
    }

    @Test
    void returnsUnsentForNewRecipientAndSentForExistingContent() {
        assertEquals("UNSENT", dao.findStatus(1L, "key-1", "a@example.com", "system@example.com", "hash-a").status());
        dao.insertPending(1L, "key-1", "user-a", "a@example.com", "system@example.com", "hash-a");
        dao.markSent(1L, "a@example.com", "system@example.com", "hash-a");
        assertEquals("SENT", dao.findStatus(1L, "key-1", "a@example.com", "system@example.com", "hash-a").status());
        assertEquals("UNSENT", dao.findStatus(1L, "key-1", "b@example.com", "system@example.com", "hash-a").status());
        assertEquals("PENDING", dao.findStatus(1L, "key-1", "a@example.com", "system@example.com", "hash-b").status());
    }
}
