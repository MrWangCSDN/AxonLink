package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in migration and DAO test against only its own disposable loopback MySQL database. */
@EnabledIfEnvironmentVariable(named = "INTERFACE_SPLIT_MYSQL_REGRESSION", matches = "true")
class ReplayInterfaceComparisonSplitMysqlTest {

    @Test
    void preservesHistoricalUnknownSplitAndRoundTripsDetailZeroAndTotalCounts() {
        String address = System.getenv().getOrDefault("REPORT_MYSQL_ADDRESS", "127.0.0.1:3306");
        assertTrue(address.matches("(?:127\\.0\\.0\\.1|localhost):[0-9]+"),
                "This fixture only permits a loopback MySQL server");
        String user = System.getenv().getOrDefault("REPORT_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("REPORT_MYSQL_PASSWORD", "");
        String database = "codex_test_interface_split_" + UUID.randomUUID().toString().replace("-", "");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://" + address + "/" + options, user, password));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(
                    "jdbc:mysql://" + address + "/" + database + options, user, password);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/daoindex/V56__dii_replay_daily_import_data.sql"))
                    .execute(source);
            jdbc.update("INSERT INTO dii_replay_interface_comparison "
                            + "(batch_no,row_type,transaction_code,both_success,source_row,raw_json,imported_at) "
                            + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    "RPT-LEGACY", "DETAIL", "LEGACY", 250L, 2, "{}");

            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/daoindex/V86__replay_interface_success_field_difference.sql"))
                    .execute(source);

            ReplayDailyDataDao dao = new ReplayDailyDataDao(jdbc);
            ReplayInterfaceComparisonRow legacy = dao.findComparisons("RPT-LEGACY").get(0);
            assertEquals(250L, legacy.bothSuccess());
            assertNull(legacy.bothSuccessNoFieldDifference());
            assertNull(legacy.bothSuccessWithFieldDifference());

            String batch = "RPT20261104-01";
            List<ReplayInterfaceComparisonRow> comparisons = List.of(
                    comparison(batch, ReplayDailyRowType.DETAIL, "A001", 2, 250L, 249L, 1L),
                    comparison(batch, ReplayDailyRowType.DETAIL, "A002", 3, 250L, 250L, 0L),
                    comparison(batch, ReplayDailyRowType.TOTAL, null, 4, 500L, 499L, 1L));
            dao.replaceBatch(new ReplayDailyWorkbookData(batch, List.of(), comparisons, List.of(), List.of()),
                    LocalDateTime.of(2026, 11, 4, 9, 0));

            assertEquals(List.of(249L, 250L, 499L), jdbc.queryForList(
                    "SELECT both_success_no_field_difference FROM dii_replay_interface_comparison "
                            + "WHERE batch_no=? ORDER BY source_row", Long.class, batch));
            assertEquals(List.of(1L, 0L, 1L), jdbc.queryForList(
                    "SELECT both_success_with_field_difference FROM dii_replay_interface_comparison "
                            + "WHERE batch_no=? ORDER BY source_row", Long.class, batch));
            List<ReplayInterfaceComparisonRow> saved = dao.findComparisons(batch);
            assertEquals(comparisons, saved);
            assertEquals(0L, saved.get(1).bothSuccessWithFieldDifference());
            assertEquals(ReplayDailyRowType.TOTAL, saved.get(2).rowType());
            assertEquals(500L, saved.get(2).bothSuccess());
            assertEquals(499L, saved.get(2).bothSuccessNoFieldDifference());
            assertEquals(1L, saved.get(2).bothSuccessWithFieldDifference());
            assertEquals(legacy, dao.findComparisons("RPT-LEGACY").get(0));
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }

    private static ReplayInterfaceComparisonRow comparison(String batch, ReplayDailyRowType rowType,
                                                           String code, int sourceRow, Long bothSuccess,
                                                           Long noFieldDifference, Long withFieldDifference) {
        return new ReplayInterfaceComparisonRow(batch, rowType, code, null, null, null, null, null,
                bothSuccess, null, null, null, null, bothSuccess, null, null, null, null, null, sourceRow, "{}",
                noFieldDifference, withFieldDifference);
    }
}
