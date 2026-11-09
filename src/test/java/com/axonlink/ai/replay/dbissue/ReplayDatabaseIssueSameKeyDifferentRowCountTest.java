package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueSameKeyDifferentRowCountTest {
    private static final String ROW_COUNT = "sameKeyDifferentRowCount";
    private static final ReplayIssueOperator ACTOR = new ReplayIssueOperator("editor", "编辑人");
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueDao dao;
    private ReplayDatabaseIssueService service;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(List.of(
                "V62__dii_replay_database_comparison_fields.sql", "V63__dii_replay_database_comparison_versions.sql",
                "V77__replay_database_issues.sql", "V78__replay_database_issue_review_projection.sql",
                "V79__replay_database_issue_repair_date_projection.sql", "V80__replay_database_issue_collaborator_projection.sql",
                "V81__replay_database_issue_completion_totals.sql", "V82__replay_database_table_person.sql",
                "V83__replay_database_issue_plan_date.sql", "V89__replay_database_issue_registration_version.sql").stream()
                .map(name -> new ClassPathResource("db/daoindex/" + name)).toArray(ClassPathResource[]::new))
                .execute(jdbc.getDataSource());
        dao = new ReplayDatabaseIssueDao(jdbc);
        service = new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(), dao, new SysUserDao(jdbc),
                Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void persistsOneDifferingRowAcrossTwoFieldsInCurrentAndImportSnapshots() throws Exception {
        service.importFile(workbook(1, 4, 3), null, ACTOR);

        assertEquals(1, currentSnapshot().path(ROW_COUNT).asLong(-1));
        assertEquals(1, historySnapshot("incoming_snapshot").path(ROW_COUNT).asLong(-1));
        assertEquals(1, historySnapshot("after_snapshot").path(ROW_COUNT).asLong(-1));
        assertEquals(7L, dao.findByIssueId("orders").get("differenceCount"));
        assertEquals("新建", dao.findByIssueId("orders").get("issueStatus"));
        assertEquals(1L, dao.findByIssueId("orders-amount").get("differenceCount"));
        assertEquals(1L, dao.findByIssueId("orders-status").get("differenceCount"));
    }

    @Test
    void sameBatchReimportReplacesTheRowCountAndIncomingSnapshot() throws Exception {
        service.importFile(workbook(1, 0, 0), null, ACTOR);
        service.importFile(workbook(2, 0, 0), null, ACTOR);

        assertEquals(2, currentSnapshot().path(ROW_COUNT).asLong(-1));
        assertEquals(2, historySnapshot("incoming_snapshot").path(ROW_COUNT).asLong(-1));
        assertEquals(2, historySnapshot("after_snapshot").path(ROW_COUNT).asLong(-1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history h "
                + "JOIN dii_replay_database_issue i ON i.id=h.issue_id WHERE i.issue_id='orders'", Integer.class));
        assertEquals(0L, dao.findByIssueId("orders").get("differenceCount"));
        assertEquals("已修复", dao.findByIssueId("orders").get("issueStatus"));
        assertEquals("新建", dao.findByIssueId("orders-amount").get("issueStatus"));
    }

    @Test
    void importsAnExplicitZeroAndKeepsItThroughManualSave() throws Exception {
        service.importFile(workbook(0, 1, 0), null, ACTOR);

        assertEquals(0, currentSnapshot().path(ROW_COUNT).asLong(-1));
        assertEquals(0, historySnapshot("incoming_snapshot").path(ROW_COUNT).asLong(-1));
        var row = dao.findByIssueId("orders");
        service.update(((Number) row.get("id")).longValue(), Map.of("version", row.get("version"),
                "issueStatus", "打开", "issueType", "其他问题", "remark", "核实中"), ACTOR);

        assertEquals(0, currentSnapshot().path(ROW_COUNT).asLong(-1));
        assertEquals(0, historySnapshot("after_snapshot").path(ROW_COUNT).asLong(-1));
        assertEquals(0, ((Number) ((Map<?, ?>) service.tracking(((Number) row.get("id")).longValue())
                .get(0).get("originalData")).get(ROW_COUNT)).longValue());
    }

    @Test
    void manualSavePreservesAnUnknownRowCountInLegacySnapshots() throws Exception {
        service.importFile(workbook(0, 1, 0), null, ACTOR);
        var legacy = dao.findByIssueId("orders");
        legacy.remove(ROW_COUNT);
        dao.update(legacy);

        service.update(((Number) legacy.get("id")).longValue(), Map.of("version", legacy.get("version"),
                "issueStatus", "打开", "issueType", "其他问题", "remark", "历史数据"), ACTOR);

        assertFalse(currentSnapshot().has(ROW_COUNT));
        assertFalse(historySnapshot("after_snapshot").has(ROW_COUNT));
        assertEquals(1L, dao.findByIssueId("orders").get("differenceCount"));
    }

    private JsonNode currentSnapshot() throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT data_snapshot FROM dii_replay_database_issue "
                + "WHERE issue_id='orders'", String.class));
    }

    private JsonNode historySnapshot(String column) throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT h." + column + " FROM dii_replay_database_issue_history h "
                + "JOIN dii_replay_database_issue i ON i.id=h.issue_id WHERE i.issue_id='orders' "
                + "ORDER BY h.id DESC LIMIT 1", String.class));
    }

    private static MockMultipartFile workbook(long rowCount, long only528, long onlyCcbs) throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var tables = workbook.createSheet("表明细");
            row(tables, 0, "表", "表中文名称", "528总量", "ccbs总量", "仅528存在", "仅ccbs存在", "主键相同、字段不同", "批次号");
            row(tables, 1, "orders", "订单", "100", "98", String.valueOf(only528), String.valueOf(onlyCcbs),
                    String.valueOf(rowCount), "DB20261106-01");
            var fields = workbook.createSheet("表字段明细");
            row(fields, 0, "表", "字段名称", "差异数量", "批次号");
            if (rowCount > 0) {
                row(fields, 1, "orders", "amount", String.valueOf(rowCount), "DB20261106-01");
                row(fields, 2, "orders", "status", String.valueOf(rowCount), "DB20261106-01");
            }
            workbook.write(bytes);
            return new MockMultipartFile("file", "row-count.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
        }
    }

    private static void row(Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
