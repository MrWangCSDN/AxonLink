package com.axonlink.ai.replay.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in migration and scoped replacement test using only its own disposable loopback MySQL database. */
@EnabledIfEnvironmentVariable(named = "ISSUE_SENT_MYSQL_REGRESSION", matches = "true")
class ReplayIssueSentTransactionCountMysqlTest {

    @Test
    void migratesUnknownCountsAndReplacesOnlyTheSelectedModeWithoutChangingManualAnalysis() {
        String address = System.getenv().getOrDefault("REPORT_MYSQL_ADDRESS", "127.0.0.1:3306");
        assertTrue(address.matches("(?:127\\.0\\.0\\.1|localhost):[0-9]+"),
                "This fixture only permits a loopback MySQL server");
        String user = System.getenv().getOrDefault("REPORT_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("REPORT_MYSQL_PASSWORD", "");
        String database = "codex_test_issue_sent_" + UUID.randomUUID().toString().replace("-", "");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://" + address + "/" + options, user, password));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(
                    "jdbc:mysql://" + address + "/" + database + options, user, password);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE dii_replay_issue (id BIGINT PRIMARY KEY, batch_no VARCHAR(128), "
                    + "transaction_code VARCHAR(64), initial_analysis MEDIUMTEXT)");
            insertIssue(jdbc, 1, "RPT20261104-old", " 006208 ");
            insertIssue(jdbc, 2, "RPT20261104-other", "6208");
            insertIssue(jdbc, 3, "RPT20261104-other", "ZERO");
            insertIssue(jdbc, 4, "RPT20261104-other", "MISSING");
            insertIssue(jdbc, 5, "DZ20261104-old", "006208");
            insertIssue(jdbc, 6, "OTHER20261104-old", "6208");
            insertIssue(jdbc, 7, "RPT20261104-other", " 6208 ");
            insertIssue(jdbc, 8, "RPT20261104-other", null);

            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/daoindex/V87__replay_issue_sent_transaction_count.sql"))
                    .execute(source);

            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue "
                    + "WHERE sent_transaction_count IS NOT NULL", Integer.class));
            assertNull(storedCount(jdbc, 1));
            jdbc.update("UPDATE dii_replay_issue SET sent_transaction_count=88");
            jdbc.update("UPDATE dii_replay_issue SET sent_transaction_count=204 WHERE id=5");
            jdbc.update("UPDATE dii_replay_issue SET sent_transaction_count=305 WHERE id=6");
            ReplayIssueDao dao = new ReplayIssueDao(jdbc);

            dao.inTransaction(tx -> {
                tx.replaceSentTransactionCounts("RPT", Map.of(
                        "006208", Long.MAX_VALUE, "6208", 42L, "ZERO", 0L));
                return null;
            });

            assertEquals(Long.MAX_VALUE, storedCount(jdbc, 1));
            assertEquals(42L, storedCount(jdbc, 2));
            assertEquals(0L, storedCount(jdbc, 3));
            assertNull(storedCount(jdbc, 4));
            assertEquals(204L, storedCount(jdbc, 5));
            assertEquals(305L, storedCount(jdbc, 6));
            assertEquals(42L, storedCount(jdbc, 7));
            assertNull(storedCount(jdbc, 8));

            dao.inTransaction(tx -> {
                tx.replaceSentTransactionCounts("DZ", Map.of("006208", 17L));
                return null;
            });

            assertEquals(17L, storedCount(jdbc, 5));
            assertEquals(Long.MAX_VALUE, storedCount(jdbc, 1));
            assertEquals(305L, storedCount(jdbc, 6));
            for (int attempt = 0; attempt < 2; attempt++) {
                dao.inTransaction(tx -> {
                    tx.replaceSentTransactionCounts("RPT", Map.of("006208", 21L, "6208", 21L));
                    return null;
                });
            }

            assertEquals(21L, storedCount(jdbc, 1));
            assertEquals(21L, storedCount(jdbc, 2));
            assertNull(storedCount(jdbc, 3));
            assertNull(storedCount(jdbc, 4));
            assertEquals(17L, storedCount(jdbc, 5));
            assertEquals(305L, storedCount(jdbc, 6));
            assertEquals(21L, storedCount(jdbc, 7));
            assertNull(storedCount(jdbc, 8));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.replaceSentTransactionCounts("RPT%", Map.of("006208", 99L)));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.replaceSentTransactionCounts("rpt", Map.of("006208", 99L)));
            assertThrows(IllegalStateException.class, () -> dao.inTransaction(tx -> {
                tx.replaceSentTransactionCounts("RPT", Map.of("006208", 99L));
                throw new IllegalStateException("Abort this test transaction");
            }));
            assertEquals(21L, storedCount(jdbc, 1));
            assertEquals(21L, storedCount(jdbc, 2));
            assertEquals(List.of("人工分析-1", "人工分析-2", "人工分析-3", "人工分析-4",
                            "人工分析-5", "人工分析-6", "人工分析-7", "人工分析-8"),
                    jdbc.queryForList("SELECT initial_analysis FROM dii_replay_issue ORDER BY id", String.class));
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }

    private static void insertIssue(JdbcTemplate jdbc, long id, String batch, String transactionCode) {
        jdbc.update("INSERT INTO dii_replay_issue (id,batch_no,transaction_code,initial_analysis) VALUES (?,?,?,?)",
                id, batch, transactionCode, "人工分析-" + id);
    }

    private static Long storedCount(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT sent_transaction_count FROM dii_replay_issue WHERE id=?", Long.class, id);
    }
}
