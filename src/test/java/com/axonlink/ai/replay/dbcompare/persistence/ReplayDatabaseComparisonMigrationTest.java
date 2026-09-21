package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonMigrationTest {

    @Test
    void createsRegistrationFieldAndAppendOnlyAuditStructures() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V62__dii_replay_database_comparison_fields.sql"))
                .execute(jdbc.getDataSource());

        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_REGISTRATION",
                "SCHEMA_NAME", "TABLE_NAME", "TABLE_COMMENT", "DOMAIN_NAME",
                "REVISER_EMP_NO", "REVISER_USERNAME", "REVISER_NAME", "GROUP_OWNER_EMP_NO", "GROUP_OWNER_NAME",
                "REGISTERED_DATE", "DELETED", "VERSION");
        assertNullable(jdbc, "DII_REPLAY_DB_COMPARE_REGISTRATION",
                "REVISER_EMP_NO", "REVISER_USERNAME", "REVISER_NAME",
                "GROUP_OWNER_EMP_NO", "GROUP_OWNER_NAME");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_FIELD",
                "REGISTRATION_ID", "COLUMN_NAME", "COLUMN_COMMENT", "ORDINAL_POSITION", "PRIMARY_KEY",
                "COMPARISON_ORDER");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_AUDIT_EVENT",
                "REGISTRATION_ID", "SCHEMA_NAME", "TABLE_NAME", "OPERATION", "REGISTRATION_VERSION",
                "CHANGE_COUNT", "REASON", "OPERATOR_EMP_NO", "OPERATOR_NAME", "OPERATED_AT");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_AUDIT_DETAIL",
                "AUDIT_EVENT_ID", "DETAIL_ORDER", "CHANGE_TYPE", "FIELD_CODE", "FIELD_LABEL",
                "BEFORE_VALUE", "AFTER_VALUE", "CREATED_AT");

        jdbc.update("INSERT INTO dii_replay_db_compare_registration "
                        + "(schema_name,table_name,domain_name,reviser_emp_no,reviser_name,"
                        + "group_owner_emp_no,group_owner_name,registered_date,"
                        + "created_by,created_name,created_at,updated_by,updated_name,updated_at) "
                        + "VALUES ('base','acct','存款组','001','张三','002','李四',CURRENT_DATE,'001','张三',"
                        + "CURRENT_TIMESTAMP,'001','张三',CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE dii_replay_db_compare_registration SET deleted=1 WHERE table_name='acct'");

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_registration WHERE schema_name='base' AND table_name='acct'",
                Integer.class));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_REGISTRATION",
                "UK_REPLAY_DB_COMPARE_SCHEMA_TABLE"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_FIELD",
                "UK_REPLAY_DB_COMPARE_FIELD"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_FIELD",
                "UK_REPLAY_DB_COMPARE_FIELD_ORDER"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_AUDIT_DETAIL",
                "UK_REPLAY_DB_COMPARE_AUDIT_DETAIL_ORDER"));
        assertTrue(hasIndex(jdbc, "DII_REPLAY_DB_COMPARE_AUDIT_EVENT",
                "IDX_REPLAY_DB_COMPARE_AUDIT_BUSINESS"));
        assertFalse(hasReferentialConstraint(jdbc, "DII_REPLAY_DB_COMPARE_AUDIT_EVENT"));
        assertFalse(hasReferentialConstraint(jdbc, "DII_REPLAY_DB_COMPARE_AUDIT_DETAIL"));
    }

    @Test
    void createsImmutableVersionSnapshotsWithoutGenerationLock() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();

        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V64__drop_replay_database_comparison_generation_lock.sql"),
                new ClassPathResource("db/daoindex/V65__dii_replay_database_comparison_version_script.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"),
                new ClassPathResource("db/daoindex/V70__replay_db_compare_scope.sql"),
                new ClassPathResource("db/daoindex/V71__replay_db_compare_ordering_primary_key_snapshot.sql"),
                new ClassPathResource("db/daoindex/V74__replay_db_compare_partition_num.sql"))
                .execute(jdbc.getDataSource());

        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_AUDIT_EVENT", "OPERATOR_USERNAME");

        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_VERSION",
                "VERSION_NO", "CONFIGURATION_HASH", "TABLE_COUNT", "FIELD_COUNT",
                "GENERATED_BY", "GENERATED_NAME", "GENERATED_AT");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_TABLE",
                "VERSION_ID", "SOURCE_REGISTRATION_ID", "SOURCE_REGISTRATION_VERSION",
                "SCHEMA_NAME", "TABLE_NAME", "TABLE_COMMENT", "DOMAIN_NAME",
                "REVISER_EMP_NO", "REVISER_USERNAME", "REVISER_NAME",
                "GROUP_OWNER_EMP_NO", "GROUP_OWNER_USERNAME", "GROUP_OWNER_NAME", "REGISTERED_DATE",
                "WHERE_CONDITION_JSON", "WHERE_SQL", "COMPARE_LIMIT");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_FIELD",
                "VERSION_TABLE_ID", "COLUMN_NAME", "COLUMN_COMMENT", "ORDINAL_POSITION",
                "PRIMARY_KEY", "COMPARISON_ORDER", "PRIMARY_KEY_ORDER");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_REGISTRATION",
                "WHERE_CONDITION_JSON", "COMPARE_LIMIT", "ORDER_BY_PRIMARY_KEYS_JSON");
        assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_SCRIPT",
                "VERSION_ID", "FILE_NAME", "COMPRESSION", "SCRIPT_CONTENT",
                "SCRIPT_SHA256", "SCRIPT_SIZE", "COMPRESSED_SIZE",
                "GENERATED_BY", "GENERATED_NAME", "GENERATED_AT");
        assertFalse(tableExists(jdbc, "DII_REPLAY_DB_COMPARE_GENERATION_LOCK"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION",
                "UK_REPLAY_DB_COMPARE_VERSION_NO"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_TABLE",
                "UK_REPLAY_DB_COMPARE_VERSION_TABLE"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_FIELD",
                "UK_REPLAY_DB_COMPARE_VERSION_FIELD"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_FIELD",
                "UK_REPLAY_DB_COMPARE_VERSION_FIELD_ORDER"));
        assertTrue(hasUniqueConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_SCRIPT",
                "UK_REPLAY_DB_COMPARE_VERSION_SCRIPT_VERSION"));
        assertTrue(hasIndex(jdbc, "DII_REPLAY_DB_COMPARE_VERSION",
                "IDX_REPLAY_DB_COMPARE_VERSION_TIME"));

        String insertVersion = "INSERT INTO dii_replay_db_compare_version "
                + "(version_no,configuration_hash,table_count,field_count,generated_by,generated_name,generated_at) "
                + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)";
        jdbc.update(insertVersion, "20260914-142530", "a".repeat(64), 1, 2, "100", "张三");
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                insertVersion, "20260914-142530", "b".repeat(64), 1, 2, "101", "李四"));
        assertFalse(hasReferentialConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_TABLE"));
        assertFalse(hasReferentialConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_FIELD"));
        assertFalse(hasReferentialConstraint(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_SCRIPT"));
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

    private static void assertNullable(JdbcTemplate jdbc, String table, String... expectedColumns) {
        for (String expectedColumn : expectedColumns) {
            String nullable = jdbc.queryForObject(
                    "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=?",
                    String.class, table, expectedColumn);
            assertEquals("YES", nullable, table + "." + expectedColumn + " must be nullable");
        }
    }

    private static boolean hasIndex(JdbcTemplate jdbc, String table, String index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME=? AND INDEX_NAME=?",
                Integer.class, table, index);
        return count != null && count > 0;
    }

    private static boolean hasReferentialConstraint(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME=? AND CONSTRAINT_TYPE='FOREIGN KEY'",
                Integer.class, table);
        return count != null && count > 0;
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME=?",
                Integer.class, table);
        return count != null && count > 0;
    }
}
