package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplayInterfaceComparisonSplitMigrationTest {

    @Test
    void addsNullableSplitCountsWithoutGuessingHistoricalDetailOrTotalValues() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V56__dii_replay_daily_import_data.sql"))
                .execute(jdbc.getDataSource());
        for (String rowType : List.of("DETAIL", "TOTAL")) {
            jdbc.update("INSERT INTO dii_replay_interface_comparison "
                            + "(batch_no,row_type,both_success,source_row,imported_at) "
                            + "VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
                    "RPT20261104-01", rowType, 250L, rowType.equals("DETAIL") ? 2 : 3);
        }

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V86__replay_interface_success_field_difference.sql"))
                .execute(jdbc.getDataSource());

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_interface_comparison", Integer.class));
        jdbc.query("SELECT both_success,both_success_no_field_difference,both_success_with_field_difference "
                        + "FROM dii_replay_interface_comparison ORDER BY source_row", resultSet -> {
                    assertEquals(250L, resultSet.getLong("both_success"));
                    assertNull(resultSet.getObject("both_success_no_field_difference"));
                    assertNull(resultSet.getObject("both_success_with_field_difference"));
                });
        for (String column : List.of("BOTH_SUCCESS_NO_FIELD_DIFFERENCE", "BOTH_SUCCESS_WITH_FIELD_DIFFERENCE")) {
            assertEquals("BIGINT", jdbc.queryForObject(
                    "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_NAME='DII_REPLAY_INTERFACE_COMPARISON' AND COLUMN_NAME=?", String.class, column));
            assertEquals("YES", jdbc.queryForObject(
                    "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_NAME='DII_REPLAY_INTERFACE_COMPARISON' AND COLUMN_NAME=?", String.class, column));
        }
    }
}
