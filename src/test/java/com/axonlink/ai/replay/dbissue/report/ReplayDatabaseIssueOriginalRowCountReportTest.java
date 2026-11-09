package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueOriginalRowCountReportTest {
    private static final String BATCH = "DB20261106-01";
    private final ObjectMapper json = new ObjectMapper();
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
        service = new ReplayDatabaseIssueReportService(new ReplayDatabaseIssueReportDao(jdbc));
    }

    @Test
    void exportsOneOriginalRowWhenTwoFieldsDifferAndCountsEachTableOnlyOnce() throws Exception {
        batch(BATCH);
        mapping("a", "公共组");
        issue(1, "a", "表级", 0, 1L);
        issue(2, "a", "字段级", 1, null);
        issue(3, "a", "字段级", 1, null);
        issue(4, "a", "表级", 0, 1L);

        var report = service.build(BATCH);

        assertEquals(2, report.total().fieldDifferenceCount());
        assertEquals(2, report.total().issueCount());
        assertEquals(1, report.total().comparedTableCount());
        assertEquals(1L, metric(report, "公共组"));
        assertEquals(1L, metric(report, "合计"));
    }

    @Test
    void aMissingOriginalValueMarksItsDomainAndTotalUnknownWhileOtherDomainsRemainKnown() throws Exception {
        batch(BATCH);
        mapping("a", "公共组"); mapping("b", "存款组");
        issue(1, "a", "表级", 0, null);
        issue(2, "a", "字段级", 2, null);
        issue(3, "b", "表级", 0, 4L);
        issue(4, "b", "字段级", 4, null);

        var report = service.build(BATCH);

        assertTrue(report.tableMetricsComplete());
        assertEquals(6, report.total().fieldDifferenceCount());
        assertEquals("—", metric(report, "公共组"));
        assertEquals(4L, metric(report, "存款组"));
        assertEquals("—", metric(report, "合计"));
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("未保存") && note.contains("原始")));
    }

    @Test
    void anExplicitZeroIsKnownAndRemainsDistinctFromAMissingLegacyValue() throws Exception {
        batch(BATCH);
        mapping("a", "公共组");
        issue(1, "a", "表级", 0, 0L);
        assertEquals(0L, metric(service.build(BATCH), "公共组"));

        jdbc.update("UPDATE dii_replay_database_issue SET data_snapshot=? WHERE id=1", snapshot(0, null));

        assertEquals("—", metric(service.build(BATCH), "公共组"));
    }

    @Test
    void usesHistoricalOriginalCountsWithoutBorrowingANewerCurrentValue() throws Exception {
        batch(BATCH);
        String older = "DB20261105-01";
        batch(older);
        mapping("a", "公共组");
        issue(1, "a", "表级", 0, 9L);
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,import_event,after_snapshot,incoming_snapshot) "
                + "VALUES(1,?,'导入',CURRENT_TIMESTAMP,1,'{}',?)", older, snapshot(0, 1L));
        assertEquals(1L, metric(service.build(older), "公共组"));
        assertEquals(9L, metric(service.build(BATCH), "公共组"));

        jdbc.update("UPDATE dii_replay_database_issue_history SET incoming_snapshot=? WHERE issue_id=1", snapshot(0, null));

        assertEquals("—", metric(service.build(older), "公共组"));
        assertEquals(9L, metric(service.build(BATCH), "公共组"));
    }

    @Test
    void aFieldOnlyLegacyTableCannotSupplyAnOriginalTableRowCount() throws Exception {
        batch(BATCH);
        mapping("a", "公共组");
        issue(1, "a", "字段级", 3, null);
        var report = service.build(BATCH);
        assertEquals(3, report.total().fieldDifferenceCount());
        assertEquals("—", metric(report, "公共组"));
        assertEquals("—", metric(report, "合计"));
    }

    @Test
    void emptyDataAndStatusExclusionAreZeroWhileLostHistoricalInputsAreUnknown() throws Exception {
        assertEquals(0L, metric(service.build(null), "合计"));
        batch(BATCH);
        assertEquals("—", metric(service.build(BATCH), "合计"));
        issue(1, "a", "表级", 0, null);
        jdbc.update("UPDATE dii_replay_database_issue SET issue_status='不参与比对',data_snapshot='not-json' WHERE id=1");
        assertEquals(0L, metric(service.build(BATCH), "合计"));
    }

    @Test
    void corruptOriginalCountsFailInsteadOfFallingBackToFieldCounts() throws Exception {
        batch(BATCH);
        mapping("a", "公共组");
        issue(1, "a", "表级", 0, 0L);
        for (Object bad : List.of(-1, "invalid", 1.5)) {
            jdbc.update("UPDATE dii_replay_database_issue SET data_snapshot=? WHERE id=1", snapshot(0, bad));
            assertThrows(IllegalArgumentException.class, () -> service.build(BATCH));
        }
    }

    private Object metric(ReplayDatabaseIssueReport report, String domain) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(new ReplayDatabaseIssueReportWorkbookWriter().write(report)))) {
            var sheet = workbook.getSheet("领域汇总");
            for (int index = 3; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (domain.equals(row.getCell(0).getStringCellValue())) {
                    var cell = row.getCell(5);
                    return cell.getCellType() == CellType.NUMERIC ? (long) cell.getNumericCellValue() : cell.getStringCellValue();
                }
            }
        }
        throw new AssertionError("Missing domain " + domain);
    }

    private void batch(String name) {
        jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,imported_at,input_rows,created_rows,updated_rows,auto_repaired_rows) "
                + "VALUES(?,'2026-11-06',CURRENT_TIMESTAMP,0,0,0,0)", name);
    }

    private void mapping(String table, String domain) {
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,imported_at) VALUES(?,?,CURRENT_TIMESTAMP)", table, domain);
    }

    private void issue(long id, String table, String level, long difference, Long count) throws Exception {
        jdbc.update("INSERT INTO dii_replay_database_issue(id,issue_id,table_name,domain_name,issue_level,field_name,difference_count,batch_no,batch_date,issue_status,issue_type,data_snapshot) "
                + "VALUES(?,?,?,'原始领域',?,'',?,?,'2026-11-06','打开','修改代码',?)",
                id, "issue-" + id, table, level, difference, BATCH, snapshot(difference, count));
    }

    private String snapshot(long difference, Object count) throws Exception {
        var data = new LinkedHashMap<String, Object>();
        data.put("differenceCount", difference);
        data.put("total528", 10); data.put("totalCcbs", 10);
        data.put("only528Count", 0); data.put("onlyCcbsCount", 0);
        if (count != null) data.put("sameKeyDifferentRowCount", count);
        return json.writeValueAsString(data);
    }
}
