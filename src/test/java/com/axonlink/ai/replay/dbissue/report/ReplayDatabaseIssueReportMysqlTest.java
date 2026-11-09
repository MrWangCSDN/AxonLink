package com.axonlink.ai.replay.dbissue.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in Connector/J streaming and CTE test against only its own disposable loopback database. */
@EnabledIfEnvironmentVariable(named = "REPORT_MYSQL_REGRESSION", matches = "true")
class ReplayDatabaseIssueReportMysqlTest {
    @Test
    void mysqlStreamingBatchInputsRemainReadableAndCloseBeforeFollowingConfigurationQueries() {
        String address = System.getenv().getOrDefault("REPORT_MYSQL_ADDRESS", "127.0.0.1:3306");
        assertTrue(address.matches("(?:127\\.0\\.0\\.1|localhost):[0-9]+"), "This fixture only permits a loopback MySQL server");
        String user = System.getenv().getOrDefault("REPORT_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("REPORT_MYSQL_PASSWORD", "");
        String database = "codex_test_db_report_" + UUID.randomUUID().toString().replace("-", "");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://" + address + "/" + options, user, password));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource("jdbc:mysql://" + address + "/" + database + options, user, password);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                    new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
                    new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),
                    new ClassPathResource("db/daoindex/V89__replay_database_issue_registration_version.sql"))
                    .execute(source);
            jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,imported_at,input_rows,created_rows,updated_rows,auto_repaired_rows) VALUES "
                    + "('DB20261028-01','2026-10-28','2026-10-29 23:00:00',0,0,0,0),"
                    + "('DB20261029-01','2026-10-29','2026-10-29 10:00:00',3,3,0,0)");
            jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,imported_at) VALUES('a','公共组',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO dii_replay_db_compare_registration(id,schema_name,table_name,domain_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) "
                    + "VALUES(1,'base','a','公共组',CURRENT_DATE,'test','test',CURRENT_TIMESTAMP,'test','test',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO dii_replay_db_compare_field(registration_id,column_name,ordinal_position,comparison_order,created_at) "
                    + "VALUES(1,'id',1,1,CURRENT_TIMESTAMP),(1,'amount',2,2,CURRENT_TIMESTAMP)");
            String table = "{\"differenceCount\":5,\"total528\":100,\"totalCcbs\":105,\"only528Count\":0,\"onlyCcbsCount\":5,\"sameKeyDifferentRowCount\":2}";
            String field = "{\"differenceCount\":2,\"total528\":100,\"totalCcbs\":105,\"only528Count\":0,\"onlyCcbsCount\":5}";
            insertIssue(jdbc, 1, "表级", " ", table);
            insertIssue(jdbc, 2, "字段级", "修改代码", field);
            insertIssue(jdbc, 3, "字段级", "已消失分类", field);
            insertImport(jdbc, 1, table);
            insertImport(jdbc, 2, field);
            insertImport(jdbc, 3, field);
            insertImport(jdbc, 3, null);
            // The imported metrics must win over a subsequently changed current snapshot.
            jdbc.update("UPDATE dii_replay_database_issue SET data_snapshot='{}' WHERE id=1");

            var service = new ReplayDatabaseIssueReportService(new ReplayDatabaseIssueReportDao(jdbc));
            var report = assertDoesNotThrow(() -> service.build(null));
            assertEquals("DB20261029-01", report.batchNo());
            assertTrue(report.tableMetricsComplete());
            assertEquals(100, report.total().total528());
            assertEquals(105, report.total().totalCcbs());
            assertEquals(2, report.total().fieldDifferenceCount());
            assertEquals(2L, report.total().sameKeyDifferentRowCount());
            assertEquals(1, report.total().comparedTableCount());
            // This query follows consumption/closure of Connector/J's streaming result set.
            assertEquals(2L, report.total().fieldCount());
            assertEquals(2, report.total().issueCount());
            assertEquals(1, report.total().unprocessedCount());
            assertEquals(1L, report.total().typeCounts().get("修改代码"));
            assertEquals(0L, report.total().typeCounts().get("已消失分类"));
            assertEquals(List.of("DB20261029-01", "DB20261028-01"), service.batchNames());
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue", Long.class));

            new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"))
                    .execute(source);
            jdbc.update("INSERT INTO dii_replay_db_compare_version(id,version_no,configuration_hash,table_count,field_count,generated_by,generated_name,generated_at) "
                    + "VALUES(10,'20261027-141302',REPEAT('a',64),2,4,'test','test',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO dii_replay_db_compare_version_table(id,version_id,source_registration_id,source_registration_version,schema_name,table_name,domain_name,registered_date) "
                    + "VALUES(10,10,1,0,'base','a','历史领域',CURRENT_DATE),(11,10,2,0,'base','unused','历史领域',CURRENT_DATE)");
            jdbc.update("INSERT INTO dii_replay_db_compare_version_field(version_table_id,column_name,ordinal_position,primary_key,comparison_order) "
                    + "VALUES(10,'id',1,0,1),(11,'id',1,0,1),(11,'amount',2,0,2),(11,'name',3,0,3)");
            jdbc.update("UPDATE dii_replay_database_issue_batch SET registration_version_id=10 WHERE batch_no='DB20261029-01'");
            jdbc.update("DELETE FROM dii_replay_db_compare_field");
            jdbc.update("DELETE FROM dii_replay_db_compare_registration");
            jdbc.update("DELETE FROM dii_replay_database_table_person");

            var historical = assertDoesNotThrow(() -> service.build(null));
            assertEquals("20261027-141302", historical.registrationVersionNo());
            assertEquals(1L, historical.total().fieldCount());
            var historicalDomain = historical.rows().stream().filter(row -> row.domain().equals("历史领域")).findFirst().orElseThrow();
            assertEquals(100, historicalDomain.total528());
            assertEquals(1L, historicalDomain.fieldCount());
            assertEquals(report.total().issueCount(), historical.total().issueCount());

            insertIssue(jdbc, 4, "字段级", "修改代码", field);
            insertImport(jdbc, 4, field);
            var overlappingFields = service.build(null);
            assertEquals(4, overlappingFields.total().fieldDifferenceCount());
            assertEquals(2L, overlappingFields.total().sameKeyDifferentRowCount());

            jdbc.update("UPDATE dii_replay_database_issue SET issue_status='不参与比对'");
            var excluded = assertDoesNotThrow(() -> service.build(null));
            assertTrue(excluded.tableMetricsComplete());
            assertEquals(0, excluded.total().total528());
            assertEquals(0L, excluded.total().fieldCount());
            assertEquals(0, excluded.total().issueCount());
            assertTrue(excluded.issueTypes().isEmpty());
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue", Long.class));
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history", Long.class));

            jdbc.update("UPDATE dii_replay_database_issue SET issue_status='已修复' WHERE id=1");
            jdbc.update("UPDATE dii_replay_database_issue SET issue_status='无需处理',issue_type='不参与比对' WHERE id=2");
            var sameNamedType = assertDoesNotThrow(() -> service.build(null));
            assertEquals(2, sameNamedType.total().issueCount());
            assertEquals(2, sameNamedType.total().fieldDifferenceCount());
            assertEquals(1L, sameNamedType.total().typeCounts().get("不参与比对"));
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }

    private static void insertIssue(JdbcTemplate jdbc, long id, String level, String type, String snapshot) {
        jdbc.update("INSERT INTO dii_replay_database_issue(id,issue_id,table_name,domain_name,issue_level,difference_count,batch_no,batch_date,issue_status,issue_type,data_snapshot) "
                + "VALUES(?,?,'a','历史领域',?,2,'DB20261029-01','2026-10-29','打开',?,?)", id, "issue-" + id, level, type, snapshot);
    }

    private static void insertImport(JdbcTemplate jdbc, long id, String snapshot) {
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,import_event,after_snapshot,incoming_snapshot) "
                + "VALUES(?,'DB20261029-01','导入','2026-10-29 10:00:00',1,'{}',?)", id, snapshot);
    }
}
