package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueReportPreviousBatchDaoTest {
    private static final String SELECTED = "DB20261126-01";
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueReportDao dao;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"))
                .execute(jdbc.getDataSource());
        jdbc.execute("ALTER TABLE dii_replay_database_issue ADD COLUMN defect_repair_date VARCHAR(32) NOT NULL DEFAULT ''");
        dao = new ReplayDatabaseIssueReportDao(jdbc);
        batch(SELECTED, "2026-11-26", "2026-11-26 10:00:00");
    }

    @Test
    void returnsNullWhenNoEarlierBusinessDateExists() {
        batch("DB20261126-02", "2026-11-26", "2026-11-26 11:00:00");
        batch("DB20261127-01", "2026-11-27", "2026-11-27 10:00:00");

        assertNull(dao.previousDateBatchName(SELECTED));
    }

    @Test
    void excludesOtherBatchesOnTheSelectedBusinessDate() {
        batch("DB20261125-01", "2026-11-25", "2026-11-25 10:00:00");
        batch("DB20261126-02", "2026-11-26", "2026-11-26 11:00:00");

        assertEquals("DB20261125-01", dao.previousDateBatchName(SELECTED));
    }

    @Test
    void choosesTheNearestEarlierBusinessDateBeforeImportTime() {
        batch("DB20261124-99", "2026-11-24", "2026-11-30 23:59:59");
        batch("DB20261125-01", "2026-11-25", "2026-11-25 09:00:00");

        assertEquals("DB20261125-01", dao.previousDateBatchName(SELECTED));
    }

    @Test
    void choosesTheLastImportOnThePreviousDateBeforeBatchNumber() {
        batch("DB20261125-99", "2026-11-25", "2026-11-25 09:00:00");
        batch("DB20261125-01", "2026-11-25", "2026-11-25 11:00:00");

        assertEquals("DB20261125-01", dao.previousDateBatchName(SELECTED));
    }

    @Test
    void resolvesEqualImportTimesByDescendingBatchName() {
        batch("DB20261125-01", "2026-11-25", "2026-11-25 11:00:00");
        batch("DB20261125-02", "2026-11-25", "2026-11-25 11:00:00");

        assertEquals("DB20261125-02", dao.previousDateBatchName(SELECTED));
    }

    @Test
    void importedRowsUseCurrentRepairDateAndTheLatestOriginalImport() {
        String original = "{\"differenceCount\":2,\"defectRepairDate\":\"2026-11-01\"}";
        jdbc.update("INSERT INTO dii_replay_database_issue(id,issue_id,table_name,domain_name,issue_level,difference_count,batch_no,batch_date,issue_status,data_snapshot,defect_repair_date) "
                        + "VALUES(41,'a-amount','a','公共组','字段级',99,?,'2026-11-26','打开','{}','2026-11-25')", SELECTED);
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,import_event,after_snapshot,incoming_snapshot) "
                        + "VALUES(41,?,'导入','2026-11-26 10:00:00',1,'{}',?)", SELECTED, original);

        List<ReplayDatabaseIssueReportDao.ImportedIssue> imported = new ArrayList<>();
        dao.readConsistently(() -> {
            dao.forEachImportedIssue(SELECTED, imported::add);
            return null;
        });

        assertEquals(1, imported.size());
        assertEquals(41, imported.get(0).id());
        assertEquals(original, imported.get(0).rawSnapshot());
        assertEquals("2026-11-25", imported.get(0).defectRepairDate());
    }

    private void batch(String batch, String date, String importedAt) {
        jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,imported_at,input_rows,created_rows,updated_rows,auto_repaired_rows) "
                + "VALUES(?,?,?,0,0,0,0)", batch, LocalDate.parse(date), importedAt);
    }
}
