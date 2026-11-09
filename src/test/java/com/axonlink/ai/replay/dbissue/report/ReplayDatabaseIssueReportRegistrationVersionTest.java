package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueReportRegistrationVersionTest {
    private static final String BATCH = "DB20261028-1";
    private static final String VERSION = "20261027-141302";
    private static final String RAW = "{\"differenceCount\":0,\"total528\":20,\"totalCcbs\":20,\"only528Count\":0,\"onlyCcbsCount\":0}";
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueReportService service;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),
                new ClassPathResource("db/daoindex/V89__replay_database_issue_registration_version.sql"))
                .execute(jdbc.getDataSource());
        service = new ReplayDatabaseIssueReportService(new ReplayDatabaseIssueReportDao(jdbc));
        version(10, VERSION, 107);
        snapshotTable(101, 10, "base", "a", 3);
        snapshotTable(102, 10, "base", "b", 4);
        snapshotTable(103, 10, "base", "unused", 100);
    }

    @Test
    void deletingCurrentRegistrationsKeepsTheBoundHistoricalDomainAndParticipatingFieldTotal() throws Exception {
        facts(BATCH, 10L, List.of("a", "b"));
        currentTable(1, "a", 9);
        currentTable(2, "b", 11);
        var before = service.build(BATCH);
        assertEquals(7L, row(before, "公共组").fieldCount(), "仅汇总参与的a和b，不能使用整版107或当前20");
        assertEquals(2L, row(before, "公共组").comparedTableCount());
        jdbc.update("DELETE FROM dii_replay_db_compare_field");
        jdbc.update("DELETE FROM dii_replay_db_compare_registration");
        jdbc.update("DELETE FROM dii_replay_database_table_person");

        var after = service.build(BATCH);

        assertEquals(before.rows(), after.rows());
        assertEquals(7L, after.total().fieldCount());
        assertTrue(after.notes().stream().anyMatch(note -> note.contains(VERSION)));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(new ReplayDatabaseIssueReportWorkbookWriter().write(after)))) {
            var sheet = workbook.getSheet("领域汇总");
            assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().contains(VERSION));
            assertEquals(7d, sheet.getRow(3).getCell(7).getNumericCellValue());
        }
    }

    @Test
    void eachBatchUsesItsOwnBoundVersionAfterTheCurrentIssueRowsMoveForward() {
        facts(BATCH, 10L, List.of("a", "b"));
        version(20, "20261029-141302", 11);
        snapshotTable(201, 20, "base", "a", 5);
        snapshotTable(202, 20, "base", "b", 6);
        String later = "DB20261029-01";
        jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,imported_at,input_rows,created_rows,updated_rows,auto_repaired_rows,registration_version_id) VALUES (?, '2026-10-29', CURRENT_TIMESTAMP,0,0,0,0,20)", later);
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,import_event,after_snapshot,incoming_snapshot) SELECT id,batch_no,'导入',CURRENT_TIMESTAMP,1,'{}',data_snapshot FROM dii_replay_database_issue");
        jdbc.update("UPDATE dii_replay_database_issue SET batch_no=?,batch_date='2026-10-29'", later);

        assertEquals(7L, service.build(BATCH).total().fieldCount());
        assertEquals(11L, service.build(later).total().fieldCount());
    }

    @Test
    void boundVersionsRejectMissingOrAmbiguousParticipatingTablesWithTheirNames() {
        facts(BATCH, 10L, List.of("a", "b"));
        jdbc.update("DELETE FROM dii_replay_db_compare_version_field WHERE version_table_id=102");
        jdbc.update("DELETE FROM dii_replay_db_compare_version_table WHERE id=102");
        var missing = assertThrows(IllegalArgumentException.class, () -> service.build(BATCH));
        assertTrue(missing.getMessage().contains("b"));
        snapshotTable(102, 10, "base", "b", 4);
        snapshotTable(104, 10, "other_schema", "a", 2);
        var ambiguous = assertThrows(IllegalArgumentException.class, () -> service.build(BATCH));
        assertTrue(ambiguous.getMessage().contains("a"));
    }

    @Test
    void aUniqueSnapshotTableWithZeroFieldsHasAKnownZeroTotal() {
        snapshotTable(104, 10, "base", "empty", 0);
        facts(BATCH, 10L, List.of("empty"));
        assertEquals(0L, service.build(BATCH).total().fieldCount());
    }

    @Test
    void aDanglingHistoricalBindingFailsWithAReadableVersionError() {
        facts(BATCH, 999L, List.of("a"));
        var failure = assertThrows(IllegalArgumentException.class, () -> service.build(BATCH));
        assertTrue(failure.getMessage().contains("版本"));
    }

    @Test
    void excludedTablesDoNotRequireVersionCoverageOrContributeHistoricalFields() {
        facts(BATCH, 10L, List.of("a", "b"));
        jdbc.update("UPDATE dii_replay_database_issue SET issue_status='不参与比对' WHERE table_name='b'");
        jdbc.update("DELETE FROM dii_replay_db_compare_version_table WHERE id=102");

        var report = assertDoesNotThrow(() -> service.build(BATCH));

        assertEquals(3L, report.total().fieldCount());
        assertEquals(1, report.total().comparedTableCount());
        assertEquals(20, report.total().total528());
    }

    private void version(long id, String version, int fields) {
        jdbc.update("INSERT INTO dii_replay_db_compare_version(id,version_no,configuration_hash,table_count,field_count,generated_by,generated_name,generated_at) VALUES (?,?,?,3,?,'tester','测试人',CURRENT_TIMESTAMP)", id, version, "a".repeat(64), fields);
    }

    private void snapshotTable(long id, long version, String schema, String table, int fields) {
        jdbc.update("INSERT INTO dii_replay_db_compare_version_table(id,version_id,source_registration_id,source_registration_version,schema_name,table_name,domain_name,registered_date) VALUES (?,?,?,0,?,?, '公共组',CURRENT_DATE)", id, version, id, schema, table);
        for (int index = 0; index < fields; index++) {
            jdbc.update("INSERT INTO dii_replay_db_compare_version_field(version_table_id,column_name,ordinal_position,primary_key,comparison_order) VALUES (?,?,?,0,?)", id, "f" + index, index, index);
        }
    }

    private void currentTable(long id, String table, int fields) {
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,imported_at) VALUES (?,'当前领域',CURRENT_TIMESTAMP)", table);
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(id,schema_name,table_name,domain_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES (?,'base',?,'当前领域',CURRENT_DATE,'test','test',CURRENT_TIMESTAMP,'test','test',CURRENT_TIMESTAMP)", id, table);
        for (int index = 0; index < fields; index++) jdbc.update("INSERT INTO dii_replay_db_compare_field(registration_id,column_name,ordinal_position,comparison_order,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)", id, "c" + index, index, index);
    }

    private void facts(String batch, Long version, List<String> tables) {
        jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,imported_at,input_rows,created_rows,updated_rows,auto_repaired_rows,registration_version_id) VALUES (?,'2026-10-28',CURRENT_TIMESTAMP,0,0,0,0,?)", batch, version);
        for (String table : tables) jdbc.update("INSERT INTO dii_replay_database_issue(issue_id,table_name,domain_name,issue_level,field_name,difference_count,batch_no,batch_date,issue_status,issue_type,data_snapshot) VALUES (?,?,'导入领域','表级','',0,?,'2026-10-28','已修复','',?)", table, table, batch, RAW);
    }

    private static ReplayDatabaseIssueReport.Row row(ReplayDatabaseIssueReport report, String domain) {
        return report.rows().stream().filter(row -> row.domain().equals(domain)).findFirst().orElseThrow();
    }
}
