package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDailyDataMigrationTest {

    @Test
    void createsAllDailyImportTablesAndRequiredColumns() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V56__dii_replay_daily_import_data.sql"))
                .execute(jdbc.getDataSource());

        assertColumns(jdbc, "DII_REPLAY_DAILY_SUMMARY",
                "BATCH_NO", "ISSUE_TOTAL", "SOURCE_ROW");
        assertColumns(jdbc, "DII_REPLAY_INTERFACE_COMPARISON",
                "ROW_TYPE", "C528_AVG_DURATION", "CCBS_AVG_DURATION");
        assertColumns(jdbc, "DII_REPLAY_COVERAGE_SUMMARY",
                "ROW_TYPE", "COVERAGE_RATE", "SOURCE_ROW");
        assertColumns(jdbc, "DII_REPLAY_COVERAGE_DETAIL",
                "RELATED_CODE", "LATEST_TRANSACTION_DATE", "COVERAGE_STATUS");
    }

    @Test
    void createsDailyReportSnapshotTableAndRequiredColumns() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V57__dii_replay_daily_report_snapshot.sql"))
                .execute(jdbc.getDataSource());

        assertColumns(jdbc, "DII_REPLAY_DAILY_REPORT_SNAPSHOT",
                "BATCH_NO", "FILE_NAME", "CONTENT_TYPE", "FILE_CONTENT", "FILE_SIZE", "GENERATED_AT");
    }

    @Test
    void preservesExistingSnapshotsAndMailStatus() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V57__dii_replay_daily_report_snapshot.sql"),
                new ClassPathResource("db/daoindex/V58__dii_replay_daily_report_mail.sql"))
                .execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO dii_replay_daily_report_snapshot "
                        + "(batch_no,file_name,content_type,file_content,file_size,generated_at) "
                        + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                "RPT20260908-01", "日报.xlsx", "application/xlsx", new byte[]{1}, 1L);
        jdbc.update("INSERT INTO dii_replay_daily_report_mail "
                        + "(batch_no,status,subject,body,sender_email,to_emails,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "RPT20260908-01", "SENT", "日报", "正文", "from@example.com", "to@example.com");

        ResourceDatabasePopulator invalidation = new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V59__invalidate_replay_daily_report_snapshot_for_issue_total.sql"));
        invalidation.execute(jdbc.getDataSource());
        invalidation.execute(jdbc.getDataSource());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_daily_report_snapshot", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_daily_report_mail", Integer.class));
    }

    private static void assertColumns(JdbcTemplate jdbc, String table, String... expectedColumns) {
        List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=?",
                String.class, table);
        for (String expectedColumn : expectedColumns) {
            assertTrue(columns.contains(expectedColumn), table + " missing " + expectedColumn);
        }
    }
}
