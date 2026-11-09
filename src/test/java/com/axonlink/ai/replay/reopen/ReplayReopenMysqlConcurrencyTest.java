package com.axonlink.ai.replay.reopen;

import static org.junit.jupiter.api.Assertions.*;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/** Opt-in real InnoDB regression; creates and drops only its own randomly named database. */
@EnabledIfEnvironmentVariable(named = "REOPEN_MYSQL_REGRESSION", matches = "true")
class ReplayReopenMysqlConcurrencyTest {
    @Test
    void concurrentBackfillsRemainIdempotentAfterBothEstablishRepeatableReadSnapshots()
            throws Exception {
        String database = "codex_reopen_rr_" + UUID.randomUUID().toString().replace("-", "");
        String user = System.getenv().getOrDefault("REOPEN_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("REOPEN_MYSQL_PASSWORD", "");
        String address = System.getenv().getOrDefault("REOPEN_MYSQL_ADDRESS", "127.0.0.1:3306");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://" + address + "/" + options, user, password));
        admin.execute("CREATE DATABASE " + database);
        var pool = Executors.newFixedThreadPool(2);
        try {
            DataSource source =
                    new DriverManagerDataSource(
                            "jdbc:mysql://" + address + "/" + database + options, user, password);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(
                            new ClassPathResource(
                                    "db/daoindex/V85__replay_issue_reopen_attribution.sql"))
                    .execute(source);
            jdbc.execute(
                    "CREATE TABLE dii_replay_issue_round (id BIGINT PRIMARY KEY,round_id"
                        + " BIGINT,replay_issue_id BIGINT,issue_key VARCHAR(1024),status_before"
                        + " VARCHAR(32),status_after VARCHAR(32),source_sheet"
                        + " VARCHAR(64),recorded_at DATETIME,incoming_snapshot TEXT,batch_name"
                        + " VARCHAR(128)) ENGINE=InnoDB");
            jdbc.update(
                    "INSERT INTO dii_replay_issue_round VALUES"
                        + " (1,10,100,'RR-KEY','修复待验证','重新打开','公共组','2026-10-22"
                        + " 10:00:00','{}','RPT20261022-1')");
            CountDownLatch bothSourceSnapshots = new CountDownLatch(2);
            CountDownLatch winnerCommitted = new CountDownLatch(1);
            ReplayReopenRecorder winner =
                    recorder(
                            new JdbcTemplate(
                                    new SnapshotBarrierDataSource(
                                            source, bothSourceSnapshots, winnerCommitted, false)));
            ReplayReopenRecorder loser =
                    recorder(
                            new JdbcTemplate(
                                    new SnapshotBarrierDataSource(
                                            source, bothSourceSnapshots, winnerCommitted, true)));
            var first =
                    pool.submit(
                            () -> {
                                try {
                                    return winner.backfill(0, 10, ReplayIssueOperator.system());
                                } finally {
                                    winnerCommitted.countDown();
                                }
                            });
            var second = pool.submit(() -> loser.backfill(0, 10, ReplayIssueOperator.system()));
            assertEquals(1, first.get(20, TimeUnit.SECONDS).inserted());
            var retry =
                    assertDoesNotThrow(
                            () -> second.get(20, TimeUnit.SECONDS),
                            "A second backfill with an older RR snapshot must observe the winner's"
                                + " committed source event");
            assertEquals(1, retry.scanned());
            assertEquals(0, retry.inserted());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM dii_replay_reopen_event", Long.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM dii_replay_reopen_audit", Long.class));
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT next_sequence FROM dii_replay_reopen_counter", Long.class));
        } finally {
            pool.shutdownNow();
            assertTrue(
                    pool.awaitTermination(20, TimeUnit.SECONDS),
                    "Backfill workers must stop before database cleanup");
            admin.execute("DROP DATABASE " + database);
        }
    }

    private static ReplayReopenRecorder recorder(JdbcTemplate jdbc) {
        ReplayReopenProperties properties = new ReplayReopenProperties();
        properties.setEnabled(true);
        return new ReplayReopenRecorder(
                new ReplayIssueDao(jdbc),
                new ReplayTransactionPersonDao(jdbc),
                new SysUserDao(jdbc),
                new ReplayReopenDao(jdbc),
                properties);
    }

    /**
     * Both source SELECTs execute before either import commits; the loser then resumes after
     * commit.
     */
    private static final class SnapshotBarrierDataSource extends DelegatingDataSource {
        private final CountDownLatch bothSnapshots;
        private final CountDownLatch winnerCommitted;
        private final boolean loser;

        SnapshotBarrierDataSource(
                DataSource source,
                CountDownLatch bothSnapshots,
                CountDownLatch winnerCommitted,
                boolean loser) {
            super(source);
            this.bothSnapshots = bothSnapshots;
            this.winnerCommitted = winnerCommitted;
            this.loser = loser;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return (Connection)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                Object result = invoke(connection, method, args);
                                if (method.getName().equals("prepareStatement")
                                        && args[0] instanceof String sql
                                        && sql.contains("FROM dii_replay_issue_round WHERE id>")) {
                                    PreparedStatement statement = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {PreparedStatement.class},
                                            (statementProxy, statementMethod, statementArgs) -> {
                                                Object queryResult =
                                                        invoke(
                                                                statement,
                                                                statementMethod,
                                                                statementArgs);
                                                if (statementMethod
                                                        .getName()
                                                        .equals("executeQuery")) {
                                                    bothSnapshots.countDown();
                                                    assertTrue(
                                                            bothSnapshots.await(
                                                                    10, TimeUnit.SECONDS),
                                                            "Both backfills must establish a source"
                                                                + " snapshot");
                                                    if (loser)
                                                        assertTrue(
                                                                winnerCommitted.await(
                                                                        10, TimeUnit.SECONDS),
                                                                "Winner must commit before loser"
                                                                    + " continues");
                                                }
                                                return queryResult;
                                            });
                                }
                                return result;
                            });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
