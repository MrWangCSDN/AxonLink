package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueReportServiceTest {
    private static final String BATCH = "DB20261029-01";
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
    void totalsEachTableOnceAndClassifiesActualTypesFromCurrentRows() throws Exception {
        fixture();
        // The current projection changed after the requested batch, while its original imported metrics did not.
        jdbc.update("UPDATE dii_replay_database_issue SET data_snapshot=? WHERE id=1", snapshot(14, 9999, 110, 2, 12));
        var report = service.build(BATCH);
        assertTrue(report.tableMetricsComplete());
        var common = row(report, "公共组");
        assertEquals(150, common.total528());
        assertEquals(160, common.totalCcbs());
        assertEquals(2, common.only528Count());
        assertEquals(12, common.onlyCcbsCount());
        assertEquals(5, common.fieldDifferenceCount());
        assertEquals(2, common.comparedTableCount());
        assertEquals(5L, common.fieldCount());
        assertEquals(1, common.identicalTableCount());
        assertEquals(3, common.issueCount());
        assertEquals(1, common.unprocessedCount());
        assertEquals(1L, common.typeCounts().get("新增类型`'测试"));
        assertEquals(180, report.total().total528());
        assertEquals(192, report.total().totalCcbs());
        assertEquals(7, report.total().fieldDifferenceCount());
        assertEquals(3, report.total().comparedTableCount());
        assertEquals(6L, report.total().fieldCount());
        assertEquals(5, report.total().issueCount());
        assertEquals(2, report.total().unprocessedCount());
        assertEquals(0L, report.total().typeCounts().get("已消失分类"));
        assertTrue(report.issueTypes().containsAll(List.of("修改代码", "新增类型`'测试", "规则性差异", "已消失分类")));
        assertFalse(report.issueTypes().contains("代码问题"));
        for (var entry : report.rows()) {
            assertEquals(entry.issueCount(), entry.unprocessedCount() + entry.typeCounts().values().stream().mapToLong(Long::longValue).sum());
        }
        assertEquals(report.total().issueCount(), report.rows().stream().mapToLong(ReplayDatabaseIssueReport.Row::issueCount).sum());
        assertEquals(report.total().issueCount(), report.total().unprocessedCount()
                + report.total().typeCounts().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(List.of("公共组", "存款组", "结算组", "贷款组"), report.rows().stream().map(ReplayDatabaseIssueReport.Row::domain).toList());
    }

    @Test
    void lastImportWinsIncludingNullDisappearanceAndSameSecondCorrections() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        mapping("a", "公共组"); configuration(1, "a", "public", 1);
        issue(1, "a", "表级", 0, null, 100, 100, 0, 0, true, "已修复");
        issue(2, "a", "字段级", 9, "修改代码", 100, 100, 0, 0, true, "打开");
        history(2, null, BATCH, "2026-10-29 10:00:00", true);
        // A later manual save must not restore an issue that the final import removed.
        history(2, snapshot(90, 100, 100, 0, 0), BATCH, "2026-10-29 12:00:00", false);
        issue(3, "a", "字段级", 2, "修改比对规则", 100, 100, 0, 0, true, "打开");
        history(3, snapshot(3, 100, 100, 0, 0), BATCH, "2026-10-29 10:00:00", true);
        var report = service.build(BATCH);
        assertEquals(3, report.total().fieldDifferenceCount());
        assertEquals(1, report.total().issueCount());
        assertEquals(0, report.total().identicalTableCount());
        assertEquals(1L, report.total().typeCounts().get("修改比对规则"));
    }

    @Test
    void historicalBatchUsesOriginalInputsButCurrentClassificationAndDomain() throws Exception {
        fixture();
        String old = "DB20261028-01";
        batch(old, "2026-10-29 20:00:00");
        history(1, snapshot(4, 40, 44, 0, 4), old, "2026-10-28 09:00:00", true);
        history(2, snapshot(1, 40, 44, 0, 4), old, "2026-10-28 09:00:00", true);
        jdbc.update("UPDATE dii_replay_database_issue SET issue_type='修改迁移规则_新版' WHERE id=2");
        jdbc.update("UPDATE dii_replay_database_table_person SET domain_name='平台组' WHERE table_name='a'");
        var report = service.build(old);
        assertEquals(old, report.batchNo());
        assertEquals(40, report.total().total528());
        assertEquals(44, report.total().totalCcbs());
        assertEquals(1, report.total().fieldDifferenceCount());
        assertEquals(2, report.total().issueCount());
        assertEquals(1L, row(report, "平台组").typeCounts().get("修改迁移规则_新版"));
        assertFalse(report.issueTypes().contains("修改代码"));
        assertEquals(BATCH, service.build(null).batchNo());
    }

    @Test
    void legacyRowsWithoutBatchHistoryFallBackToTheirCurrentSnapshot() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        mapping("a", "公共组"); configuration(1, "a", "public", 1);
        issue(1, "a", "表级", 3, "修改代码", 20, 23, 0, 3, false, "打开");
        var report = service.build(BATCH);
        assertEquals(20, report.total().total528());
        assertEquals(3, report.total().onlyCcbsCount());
        assertEquals(1, report.total().issueCount());
    }

    @Test
    void missingOrAmbiguousConfigurationLeavesFieldCountsUnknownAndMissingDomainsRemainVisible() throws Exception {
        fixture();
        jdbc.update("DELETE FROM dii_replay_db_compare_registration WHERE id=3");
        jdbc.update("DELETE FROM dii_replay_database_table_person WHERE table_name='c'");
        var missing = service.build(BATCH);
        assertNull(row(missing, "未分配领域").fieldCount());
        assertEquals(2, row(missing, "未分配领域").issueCount());
        assertNull(missing.total().fieldCount());
        assertEquals(5L, row(missing, "公共组").fieldCount());
        configuration(4, "a", "other_schema", 2);
        var ambiguous = service.build(BATCH);
        assertNull(row(ambiguous, "公共组").fieldCount());
    }

    @Test
    void emptyDatabaseAndEmptyTypeCatalogProduceZeroRowsWithoutInventedCategories() throws Exception {
        var empty = service.build(null);
        assertNull(empty.batchNo());
        assertTrue(empty.tableMetricsComplete());
        assertEquals(4, empty.rows().size());
        assertTrue(empty.issueTypes().isEmpty());
        assertEquals(0, empty.total().issueCount());
        assertEquals(0L, empty.total().fieldCount());
        batch(BATCH, "2026-10-29 10:00:00");
        mapping("a", "公共组"); configuration(1, "a", "public", 1);
        issue(1, "a", "表级", 0, "  ", 0, 0, 0, 0, true, "已修复");
        var consistent = service.build(BATCH);
        assertTrue(consistent.issueTypes().isEmpty());
        assertEquals(1, consistent.total().comparedTableCount());
        assertEquals(1, consistent.total().identicalTableCount());
        assertEquals(0, consistent.total().issueCount());
    }

    @Test
    void fullBatchCatalogKeepsHistoricalBatchesThatHaveNoCurrentIssuesAndUsesBusinessDateFirst() {
        batch("DB20261028-01", "2026-10-29 23:59:00");
        batch(BATCH, "2026-10-29 10:00:00");
        batch("DB20261029-02", "2026-10-29 11:00:00");
        assertEquals(List.of("DB20261029-02", BATCH, "DB20261028-01"), service.batchNames());
        assertEquals("DB20261029-02", service.build("").batchNo());
    }

    @Test
    void existingBatchWithoutRetainedInputsDoesNotPresentUnknownTableMetricsAsZero() {
        batch(BATCH, "2026-10-29 10:00:00");
        var report = service.build(BATCH);
        assertEquals(BATCH, report.batchNo());
        assertFalse(report.tableMetricsComplete());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("缺少完整表级") && note.contains("不以0代替未知值")));
    }

    @Test
    void historicalFormatsWithoutTableInputsMarkMetricsUnknownButKeepFieldDifferencesAndTypes() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        mapping("a", "公共组");
        issue(1, "a", "仅ccbs", 4, "修改代码", 100, 104, 0, 4, true, "打开");
        issue(2, "a", "字段级", 2, "修改比对规则", 100, 104, 0, 4, true, "打开");
        var report = service.build(BATCH);
        assertFalse(report.tableMetricsComplete());
        assertEquals(2, report.total().fieldDifferenceCount());
        assertEquals(2, report.total().issueCount());
        assertEquals(1L, report.total().typeCounts().get("修改代码"));
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("缺少完整表级") && note.contains("不以0代替未知值")));
    }

    @Test
    void invalidAndUnknownExplicitBatchesAreRejectedBeforeGeneratingAReport() {
        for (String invalid : List.of("RPT20261029-01", "DB20260230-01", "bad", "DB20261029-01' OR 1=1")) {
            assertThrows(IllegalArgumentException.class, () -> service.build(invalid));
        }
        var unknown = assertThrows(IllegalArgumentException.class, () -> service.build(BATCH));
        assertTrue(unknown.getMessage().contains("批次"));
    }

    @Test
    void corruptSnapshotsFailClearlyWithoutChangingBusinessRows() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        issue(1, "a", "表级", 1, null, 1, 1, 0, 1, false, "打开");
        jdbc.update("UPDATE dii_replay_database_issue SET data_snapshot='not-json' WHERE id=1");
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue", Long.class);
        var error = assertThrows(IllegalArgumentException.class, () -> service.build(BATCH));
        assertTrue(error.getMessage().contains("快照"));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue", Long.class));
        assertEquals("not-json", jdbc.queryForObject("SELECT data_snapshot FROM dii_replay_database_issue WHERE id=1", String.class));
    }

    @Test
    void excludesNotComparedStatusesFromHistoryAndFallbackButKeepsTheSameNamedIssueType() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        mapping("a", "公共组"); mapping("b", "公共组"); mapping("c", "存款组");
        configuration(1, "a", "public", 3); configuration(2, "b", "public", 2); configuration(3, "c", "public", 1);
        issue(1, "a", "表级", 4, "隐藏分类", 100, 104, 0, 4, true, "不参与比对");
        issue(2, "a", "字段级", 5, "隐藏分类", 100, 104, 0, 4, true, "不参与比对");
        issue(3, "b", "表级", 6, "隐藏分类", 200, 206, 0, 6, false, "不参与比对");
        issue(4, "b", "字段级", 7, "隐藏分类", 200, 206, 0, 6, false, "不参与比对");
        issue(5, "c", "表级", 2, "不参与比对", 30, 32, 0, 2, true, "无需处理");
        issue(6, "c", "字段级", 3, "不参与比对", 30, 32, 0, 2, false, "打开");
        long historyCount = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history", Long.class);

        var report = service.build(BATCH);

        assertEquals(30, report.total().total528());
        assertEquals(32, report.total().totalCcbs());
        assertEquals(2, report.total().onlyCcbsCount());
        assertEquals(3, report.total().fieldDifferenceCount());
        assertEquals(1, report.total().comparedTableCount());
        assertEquals(1L, report.total().fieldCount());
        assertEquals(2, report.total().issueCount());
        assertEquals(0, report.total().unprocessedCount());
        assertEquals(List.of("不参与比对"), report.issueTypes());
        assertEquals(2L, report.total().typeCounts().get("不参与比对"));
        assertEquals(0, row(report, "公共组").issueCount());
        assertEquals(6L, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue", Long.class));
        assertEquals(historyCount, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history", Long.class));
    }

    @Test
    void anEntirelyExcludedBatchHasKnownZeroMetricsAndRemainsAvailableInBatchHistory() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        issue(1, "a", "表级", 9, "隐藏分类", 100, 109, 0, 9, true, "不参与比对");

        var report = service.build(BATCH);

        assertEquals(List.of(BATCH), service.batchNames());
        assertTrue(report.tableMetricsComplete());
        assertEquals(0L, report.total().fieldCount());
        assertEquals(0, report.total().comparedTableCount());
        assertEquals(0, report.total().issueCount());
        assertTrue(report.issueTypes().isEmpty());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(new ReplayDatabaseIssueReportWorkbookWriter().write(report)))) {
            assertEquals(0d, workbook.getSheet("领域汇总").getRow(3).getCell(7).getNumericCellValue());
        }
    }

    @Test
    void currentNotComparedStatusExcludesHistoricalInputsBeforeSnapshotValidation() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        issue(1, "a", "表级", 9, "隐藏分类", 100, 109, 0, 9, false, "不参与比对");
        String old = "DB20261028-01";
        batch(old, "2026-10-28 10:00:00");
        history(1, "not-json", old, "2026-10-28 10:00:00", true);

        var report = assertDoesNotThrow(() -> service.build(old));

        assertEquals(0, report.total().issueCount());
        assertEquals(0, report.total().total528());
        assertTrue(report.tableMetricsComplete());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("问题状态") && note.contains("不参与比对")));
    }

    private ReplayDatabaseIssueReport.Row row(ReplayDatabaseIssueReport report, String domain) {
        return report.rows().stream().filter(row -> row.domain().equals(domain)).findFirst().orElseThrow();
    }

    private void fixture() throws Exception {
        batch(BATCH, "2026-10-29 10:00:00");
        mapping("a", "公共组"); mapping("b", "公共组"); mapping("c", "存款组");
        configuration(1, "a", "public", 3); configuration(2, "b", "public", 2); configuration(3, "c", "public", 1);
        issue(1, "a", "表级", 14, "   ", 100, 110, 2, 12, true, "新建");
        issue(2, "a", "字段级", 3, "修改代码", 100, 110, 2, 12, true, "已修复");
        issue(3, "a", "字段级", 2, "新增类型`'测试", 100, 110, 2, 12, true, "打开");
        issue(4, "b", "表级", 0, null, 50, 50, 0, 0, true, "已修复");
        issue(5, "c", "表级", 2, null, 30, 32, 0, 2, true, "打开");
        issue(6, "c", "字段级", 2, "规则性差异", 30, 32, 0, 2, true, "无需处理");
        issue(7, "a", "字段级", 99, "已消失分类", 100, 110, 2, 12, true, "已修复");
        history(7, null, BATCH, "2026-10-29 11:00:00", true);
    }

    private void batch(String batch, String importedAt) {
        jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,imported_at,input_rows,created_rows,updated_rows,auto_repaired_rows) VALUES(?,?,?,0,0,0,0)", batch,
                LocalDate.parse(batch.substring(2, 10), java.time.format.DateTimeFormatter.BASIC_ISO_DATE), importedAt);
    }

    private void mapping(String table, String domain) {
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,imported_at) VALUES(?,?,CURRENT_TIMESTAMP)", table, domain);
    }

    private void configuration(long id, String table, String schema, int count) {
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(id,schema_name,table_name,domain_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES(?,?,?,'旧领域',CURRENT_DATE,'test','test',CURRENT_TIMESTAMP,'test','test',CURRENT_TIMESTAMP)", id, schema, table);
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO dii_replay_db_compare_field(registration_id,column_name,ordinal_position,comparison_order,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)", id, "f" + i, i + 1, i + 1);
        }
    }

    private void issue(long id, String table, String level, long diff, String type, long a, long b, long onlyA, long onlyB, boolean withHistory, String status) throws Exception {
        String raw = snapshot(diff, a, b, onlyA, onlyB);
        jdbc.update("INSERT INTO dii_replay_database_issue(id,issue_id,table_name,domain_name,issue_level,field_name,difference_count,batch_no,batch_date,issue_status,issue_type,data_snapshot) VALUES(?,?,?,'历史领域',?,?,?,?,?,?,?,?)",
                id, "issue-" + id, table, level, level.equals("字段级") ? "field-" + id : "", diff, BATCH, LocalDate.of(2026, 10, 29), status, type, raw);
        if (withHistory) history(id, raw, BATCH, "2026-10-29 10:00:00", true);
    }

    private String snapshot(long diff, long a, long b, long onlyA, long onlyB) throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("differenceCount", diff); values.put("total528", a); values.put("totalCcbs", b);
        values.put("only528Count", onlyA); values.put("onlyCcbsCount", onlyB);
        return json.writeValueAsString(values);
    }

    private void history(long issue, String raw, String batch, String at, boolean importEvent) {
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,import_event,after_snapshot,incoming_snapshot) VALUES(?,?,'导入',?,?,?,?)", issue, batch, at, importEvent ? 1 : 0, "{}", raw);
    }
}
