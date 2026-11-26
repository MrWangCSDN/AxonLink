package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueReportMetricsTest {
    private static final String CURRENT = "DB20261126-01";
    private static final String PREVIOUS = "DB20261125-02";
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueReportService service;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),
                new ClassPathResource("db/daoindex/V89__replay_database_issue_registration_version.sql"))
                .execute(jdbc.getDataSource());
        jdbc.execute("ALTER TABLE dii_replay_database_issue ADD COLUMN defect_repair_date VARCHAR(32) DEFAULT ''");
        service = new ReplayDatabaseIssueReportService(new ReplayDatabaseIssueReportDao(jdbc));
    }

    @Test
    void carriesOnlySharedPositiveIssuesWithAnEmptyCurrentRepairDate() {
        batch(CURRENT, "2026-11-26 10:00:00");
        batch(PREVIOUS, "2026-11-25 10:00:00");
        configuration(1, "a", "公共组", 5);
        configuration(2, "b", "存款组", 3);
        issue(1, "a", "", 10, "");
        issue(2, "a", "f0", 20, null);
        issue(3, "a", "f1", 2, "   ");
        issue(4, "a", "f2", 3, "2026-11-27");
        issue(5, "a", "f3", 4, "");
        issue(6, "b", "", 2, "");
        issue(7, "b", "f0", 0, "");
        issue(8, "b", "f1", 2, "");
        issue(9, "b", "f2", 3, "");
        for (long id : new long[]{1, 2, 3, 4, 6, 7, 9}) history(id, PREVIOUS, raw(1), true);
        history(8, PREVIOUS, raw(0), true);
        history(6, CURRENT, null, true);
        jdbc.update("UPDATE dii_replay_database_issue SET issue_status='已修复' WHERE id=2");
        jdbc.update("UPDATE dii_replay_database_issue SET issue_status='不参与比对' WHERE id=9");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history", Long.class);

        var report = service.build(CURRENT);

        assertEquals(PREVIOUS, report.previousBatchNo());
        assertEquals(3, report.total().previousUnrepairedIssueCount());
        assertEquals(3, row(report, "公共组").previousUnrepairedIssueCount());
        assertEquals(0, row(report, "存款组").previousUnrepairedIssueCount());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains(PREVIOUS)));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history", Long.class));
    }

    @Test
    void latestImportInEitherBatchWinsAndManualEventsCannotRestoreDisappearedIssues() {
        batch(CURRENT, "2026-11-26 10:00:00");
        batch(PREVIOUS, "2026-11-25 10:00:00");
        configuration(1, "a", "公共组", 3);
        issue(1, "a", "", 0, "");
        issue(2, "a", "f0", 4, "");
        issue(3, "a", "f1", 5, "");
        issue(4, "a", "f2", 6, "");
        history(2, PREVIOUS, raw(4), true);
        history(2, PREVIOUS, null, true);
        history(2, PREVIOUS, raw(4), false);
        history(3, PREVIOUS, null, true);
        history(3, PREVIOUS, raw(5), true);
        history(3, PREVIOUS, null, false);
        history(4, PREVIOUS, raw(6), true);
        history(4, CURRENT, null, true);
        history(4, CURRENT, raw(6), false);

        assertEquals(1, service.build(CURRENT).total().previousUnrepairedIssueCount());
    }

    @Test
    void usesLastImportOnTheNearestEarlierBusinessDateForAnExplicitReport() {
        batch(CURRENT, "2026-11-26 10:00:00");
        batch("DB20261126-02", "2026-11-26 23:00:00");
        batch("DB20261125-01", "2026-11-25 08:00:00");
        batch(PREVIOUS, "2026-11-25 09:00:00");
        batch("DB20261124-99", "2026-11-30 23:00:00");
        configuration(1, "a", "公共组", 1);
        issue(1, "a", "", 4, "");
        history(1, "DB20261125-01", raw(0), true);
        history(1, PREVIOUS, raw(1), true);

        var report = service.build(CURRENT);
        assertEquals(PREVIOUS, report.previousBatchNo());
        assertEquals(1, report.total().previousUnrepairedIssueCount());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("导入时间相同时按批次号倒序选择")));
    }

    @Test
    void emptyDatabaseAndNoEarlierDateHaveKnownZeroCarryover() {
        var empty = service.build(null);
        assertNull(empty.previousBatchNo());
        assertEquals(0, empty.total().previousUnrepairedIssueCount());
        assertEquals(0L, empty.total().identicalFieldCount());
        batch(CURRENT, "2026-11-26 10:00:00");
        configuration(1, "a", "公共组", 0);
        issue(1, "a", "", 0, "");
        var report = service.build(CURRENT);
        assertNull(report.previousBatchNo());
        assertEquals(0, report.total().previousUnrepairedIssueCount());
        assertEquals(0L, report.total().identicalFieldCount());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("无更早日期批次")));
    }

    @Test
    void countsSameNamedFieldsPerTableAndSubtractsFieldsRatherThanDifferingRows() {
        batch(CURRENT, "2026-11-26 10:00:00");
        configuration(1, "a", "公共组", 3);
        configuration(2, "b", "存款组", 3);
        issue(1, "a", "", 7, "");
        issue(2, "a", "f0", 100, "2026-11-27");
        history(2, CURRENT, raw(200), true);
        issue(3, "a", "f1", 0, "");
        issue(4, "b", "", 0, "");
        issue(5, "b", "f0", 2, "");
        issue(6, "b", "f1", 5, "");
        issue(7, "b", "f2", 0, "");

        var report = service.build(CURRENT);

        assertEquals(6L, report.total().fieldCount());
        assertEquals(207, report.total().fieldDifferenceCount());
        assertEquals(3L, report.total().identicalFieldCount());
        assertEquals(2L, row(report, "公共组").identicalFieldCount());
        assertEquals(1L, row(report, "存款组").identicalFieldCount());
    }

    @Test
    void unknownOrContradictoryFieldTotalsPropagateToTheAffectedDomainAndGrandTotal() {
        batch(CURRENT, "2026-11-26 10:00:00");
        configuration(1, "a", "公共组", 0);
        configuration(2, "b", "存款组", 3);
        issue(1, "a", "", 0, "");
        issue(2, "a", "f0", 2, "");
        issue(3, "b", "", 0, "");
        issue(4, "b", "f0", 1, "");

        var mismatch = service.build(CURRENT);
        assertEquals(0L, row(mismatch, "公共组").fieldCount());
        assertNull(row(mismatch, "公共组").identicalFieldCount());
        assertEquals(2L, row(mismatch, "存款组").identicalFieldCount());
        assertNull(mismatch.total().identicalFieldCount());
        assertTrue(mismatch.notes().stream().anyMatch(note -> note.contains("差异字段数量超过登记字段总数")));

        jdbc.update("DELETE FROM dii_replay_db_compare_registration WHERE table_name='b'");
        var missing = service.build(CURRENT);
        assertNull(row(missing, "存款组").fieldCount());
        assertNull(row(missing, "存款组").identicalFieldCount());
    }

    @Test
    void incompleteTableSourcesLeaveIdenticalFieldsUnknownButStillCountSharedIssues() {
        batch(CURRENT, "2026-11-26 10:00:00");
        batch(PREVIOUS, "2026-11-25 10:00:00");
        configuration(1, "a", "公共组", 3);
        issue(1, "a", "f0", 2, "");
        history(1, PREVIOUS, raw(2), true);

        var report = service.build(CURRENT);
        assertFalse(report.tableMetricsComplete());
        assertNull(report.total().identicalFieldCount());
        assertEquals(1, report.total().previousUnrepairedIssueCount());
    }

    @Test
    void attributesCarryoverToTheSelectedVersionAndSkipsUnrelatedCorruptPreviousInputs() {
        batch(CURRENT, "2026-11-26 10:00:00");
        batch(PREVIOUS, "2026-11-25 10:00:00");
        configuration(1, "a", "当前人员领域", 3);
        issue(1, "a", "", 0, "");
        issue(2, "a", "f0", 3, "");
        issue(3, "b", "", 4, "");
        jdbc.update("UPDATE dii_replay_database_issue SET batch_no=? WHERE id=3", PREVIOUS);
        jdbc.update("DELETE FROM dii_replay_database_issue_history WHERE issue_id=3");
        history(2, PREVIOUS, raw(3), true);
        history(3, PREVIOUS, "not-json", true);
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"))
                .execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO dii_replay_db_compare_version(id,version_no,configuration_hash,table_count,field_count,generated_by,generated_name,generated_at) VALUES(10,'v-selected',REPEAT('a',64),1,3,'test','test',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO dii_replay_db_compare_version_table(id,version_id,source_registration_id,source_registration_version,schema_name,table_name,domain_name,registered_date) VALUES(10,10,1,0,'base','a','所选版本领域',CURRENT_DATE)");
        for (int index = 0; index < 3; index++) jdbc.update("INSERT INTO dii_replay_db_compare_version_field(version_table_id,column_name,ordinal_position,primary_key,comparison_order) VALUES(10,?,?,0,?)", "f" + index, index, index);
        jdbc.update("UPDATE dii_replay_database_issue_batch SET registration_version_id=10 WHERE batch_no=?", CURRENT);
        jdbc.update("UPDATE dii_replay_database_issue_batch SET registration_version_id=999 WHERE batch_no=?", PREVIOUS);

        var report = assertDoesNotThrow(() -> service.build(CURRENT));

        assertEquals(1, row(report, "所选版本领域").previousUnrepairedIssueCount());
        assertEquals(2L, row(report, "所选版本领域").identicalFieldCount());
        assertEquals(1, report.total().previousUnrepairedIssueCount());
    }

    @Test
    void aCorruptPreviousSnapshotForAnActualCandidateFailsClearly() {
        batch(CURRENT, "2026-11-26 10:00:00");
        batch(PREVIOUS, "2026-11-25 10:00:00");
        configuration(1, "a", "公共组", 1);
        issue(1, "a", "", 0, "");
        issue(2, "a", "f0", 3, "");
        history(2, PREVIOUS, "not-json", true);

        var error = assertThrows(IllegalArgumentException.class, () -> service.build(CURRENT));

        assertTrue(error.getMessage().contains("报表数据快照无效"));
        assertTrue(error.getMessage().contains("问题 2"));
        assertEquals("not-json", jdbc.queryForObject("SELECT incoming_snapshot FROM dii_replay_database_issue_history WHERE issue_id=2 AND batch_no=?", String.class, PREVIOUS));
    }

    private void batch(String batch, String at) {
        jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,imported_at,input_rows,created_rows,updated_rows,auto_repaired_rows) VALUES(?,?,?,0,0,0,0)",
                batch, LocalDate.parse(batch.substring(2, 10), DateTimeFormatter.BASIC_ISO_DATE), at);
    }

    private void configuration(long id, String table, String domain, int fields) {
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,imported_at) VALUES(?,?,CURRENT_TIMESTAMP)", table, domain);
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(id,schema_name,table_name,domain_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES(?,'base',?,?,CURRENT_DATE,'test','test',CURRENT_TIMESTAMP,'test','test',CURRENT_TIMESTAMP)", id, table, domain);
        for (int index = 0; index < fields; index++) jdbc.update("INSERT INTO dii_replay_db_compare_field(registration_id,column_name,ordinal_position,comparison_order,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)", id, "f" + index, index, index);
    }

    private void issue(long id, String table, String field, long difference, String repaired) {
        String level = field.isEmpty() ? "表级" : "字段级";
        jdbc.update("INSERT INTO dii_replay_database_issue(id,issue_id,table_name,domain_name,issue_level,field_name,difference_count,batch_no,batch_date,issue_status,data_snapshot,defect_repair_date) VALUES(?,?,?,'原始领域',?,?,?,?,?,'打开',?,?)",
                id, field.isEmpty() ? table : table + "-" + field, table, level, field, difference, CURRENT, LocalDate.of(2026, 11, 26), raw(difference), repaired);
        history(id, CURRENT, raw(difference), true);
    }

    private void history(long id, String batch, String raw, boolean imported) {
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,import_event,after_snapshot,incoming_snapshot) VALUES(?,?,'导入','2026-11-26 10:00:00',?,'{}',?)", id, batch, imported ? 1 : 0, raw);
    }

    private static String raw(long difference) {
        return "{\"differenceCount\":" + difference + ",\"total528\":100,\"totalCcbs\":100,\"only528Count\":0,\"onlyCcbsCount\":0,\"sameKeyDifferentRowCount\":0,\"defectRepairDate\":\"2099-01-01\"}";
    }

    private static ReplayDatabaseIssueReport.Row row(ReplayDatabaseIssueReport report, String domain) {
        return report.rows().stream().filter(row -> row.domain().equals(domain)).findFirst().orElseThrow();
    }
}
