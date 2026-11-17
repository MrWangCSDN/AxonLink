package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.persistence.ReplayIssueMailDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueMailDaoTest {
    private static final String RECIPIENT = "helper@spdbdev.com";
    private static final String SENDER = "system@spdbdev.com";
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueMailDao dao;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V46__dii_replay_issue_mail.sql"),
                new ClassPathResource("db/daoindex/V92__replay_database_issue_mail.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayDatabaseIssueMailDao(jdbc);
    }

    @Test
    void tracksNewContentAndAllowsOnlyFailedAttemptsToBeClaimedAgain() {
        assertEquals("UNSENT", status("hash-a"));
        assertTrue(claim("hash-a"));
        assertEquals("SENDING", status("hash-a"));
        assertFalse(claim("hash-a"));

        dao.markFailed(1L, RECIPIENT, SENDER, "hash-a", "SMTP unavailable");
        assertEquals("FAILED", status("hash-a"));
        assertTrue(claim("hash-a"));
        assertNull(dao.findStatus(1L, RECIPIENT, SENDER, "hash-a").failureMessage());
        assertFalse(claim("hash-a"));

        dao.markSent(1L, RECIPIENT, SENDER, "hash-a");
        assertEquals("SENT", status("hash-a"));
        assertNotNull(dao.findStatus(1L, RECIPIENT, SENDER, "hash-a").sentAt());
        assertFalse(claim("hash-a"));
        assertEquals("PENDING", status("hash-b"));
        assertEquals(dao.findStatus(1L, RECIPIENT, SENDER, "hash-a").sentAt(),
                dao.findStatus(1L, RECIPIENT, SENDER, "hash-b").sentAt());
        assertEquals("UNSENT", dao.findStatus(1L, "other@spdbdev.com", SENDER, "hash-a").status());
        assertEquals("UNSENT", dao.findStatus(1L, RECIPIENT, "other-sender@spdbdev.com", "hash-a").status());
    }

    @Test
    void databaseAndTransactionIssuesWithTheSameNumericIdKeepIndependentMailState() {
        var transactionMail = new ReplayIssueMailDao(jdbc);
        transactionMail.insertPending(1L, "transaction-key", "helper", RECIPIENT, SENDER, "same-hash");
        transactionMail.markSent(1L, RECIPIENT, SENDER, "same-hash");

        assertEquals("UNSENT", status("same-hash"));
        assertTrue(claim("same-hash"));
        dao.markFailed(1L, RECIPIENT, SENDER, "same-hash", "database issue send failed");

        assertEquals("FAILED", status("same-hash"));
        assertEquals("SENT", transactionMail.findStatusForRecipient(1L, RECIPIENT, SENDER, "same-hash").status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_mail", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_mail", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentNewRequestsAndFailedRetriesHaveOnlyOneClaimant(boolean transactional) throws Exception {
        assertEquals(1L, parallelClaims("hash-a", transactional));
        assertEquals("SENDING", status("hash-a"));
        dao.markFailed(1L, RECIPIENT, SENDER, "hash-a", "try again");
        assertEquals(1L, parallelClaims("hash-a", transactional));
        assertEquals("SENDING", status("hash-a"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_mail", Integer.class));
    }

    @Test
    void truncatesFailureMessagesToThePersistedColumnLength() {
        assertTrue(claim("hash-a"));
        dao.markFailed(1L, RECIPIENT, SENDER, "hash-a", "x".repeat(1100));
        assertEquals(1000, dao.findStatus(1L, RECIPIENT, SENDER, "hash-a").failureMessage().length());
    }

    private long parallelClaims(String hash, boolean transactional) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return transactional ? dao.inTransaction(() -> claim(hash)) : claim(hash);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long accepted = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) accepted++;
            }
            return accepted;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private boolean claim(String hash) {
        return dao.claim(1L, "database-key", "helper", RECIPIENT, SENDER, hash);
    }

    private String status(String hash) {
        return dao.findStatus(1L, RECIPIENT, SENDER, hash).status();
    }
}
