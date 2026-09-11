package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonMigrationTest {

    @Test
    void createsRegistrationFieldAndHistoryStructures() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V62__dii_replay_database_comparison_fields.sql"))
                .execute(jdbc.getDataSource());

        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_REGISTRATION",
                "SCHEMA_NAME", "TABLE_NAME", "TABLE_COMMENT", "DELETED", "VERSION");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_FIELD",
                "REGISTRATION_ID", "COLUMN_NAME", "COLUMN_COMMENT", "ORDINAL_POSITION");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_HISTORY",
                "REGISTRATION_ID", "OPERATION", "BEFORE_SNAPSHOT", "AFTER_SNAPSHOT", "OPERATED_AT");

        jdbc.update("INSERT INTO dii_replay_db_compare_registration "
                        + "(schema_name,table_name,domain_name,owner_emp_no,owner_name,group_name,registered_date,"
                        + "created_by,created_name,created_at,updated_by,updated_name,updated_at) "
                        + "VALUES ('base','acct','存款','001','张三','存款组',CURRENT_DATE,'001','张三',"
                        + "CURRENT_TIMESTAMP,'001','张三',CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE dii_replay_db_compare_registration SET deleted=1 WHERE table_name='acct'");

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_registration WHERE schema_name='base' AND table_name='acct'",
                Integer.class));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_REGISTRATION",
                "UK_REPLAY_DB_COMPARE_SCHEMA_TABLE"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_FIELD",
                "UK_REPLAY_DB_COMPARE_FIELD"));
    }

    private static void assertColumns(JdbcTemplate jdbc, String table, String... expectedColumns) {
        List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=?",
                String.class, table);
        for (String expectedColumn : expectedColumns) {
            assertTrue(columns.contains(expectedColumn), table + " missing " + expectedColumn);
        }
    }

    private static boolean hasUniqueConstraint(JdbcTemplate jdbc, String table, String constraint) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME=? "
                        + "AND CONSTRAINT_NAME=? AND CONSTRAINT_TYPE='UNIQUE'",
                Integer.class, table, constraint);
        return count != null && count > 0;
    }
}
