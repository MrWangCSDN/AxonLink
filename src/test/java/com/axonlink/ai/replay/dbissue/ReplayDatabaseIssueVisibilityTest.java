package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayDatabaseIssueVisibilityTest {
    private static final String API = "/api/ai/parallel-replay/database-issues";
    private static final ReplayIssueOperator ACTOR = new ReplayIssueOperator("editor", "编辑人");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueDao dao;
    private ReplayDatabaseIssueService service;
    private MockMvc mvc;

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
        service = new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(), dao, new SysUserDao(jdbc), CLOCK);
        var resolver = mock(UserPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP", "editor", null));
        mvc = MockMvcBuilders.standaloneSetup(new ReplayDatabaseIssueController(
                dao, service, resolver, new DaoIndexAnalysisProperties())).build();
    }

    @Test
    void excludesHiddenStatusBeforeCountingSortingAndPaginatingBothListEndpoints() throws Exception {
        seedVisibilityRows();

        mvc.perform(get(API).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].issueId").value("type_only_table"));
        mvc.perform(get(API).param("limit", "1").param("offset", "1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].issueId").value("active_table"));
        mvc.perform(post(API).contentType("application/json")
                        .content("{\"domains\":[\"当前领域\",\"隐藏领域\"],\"differenceCountOrder\":\"ASC\",\"limit\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].issueId").value("active_table"));
        mvc.perform(post(API).contentType("application/json")
                        .content("{\"differenceCountOrder\":\"ASC\",\"limit\":1,\"offset\":1}"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].issueId").value("type_only_table"));
    }

    @Test
    void explicitHiddenStatusFiltersRemainEmptyWhileTheSameIssueTypeRemainsVisible() throws Exception {
        seedVisibilityRows();

        mvc.perform(get(API).param("issueStatus", "不参与比对"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(post(API).contentType("application/json").content("{\"issueStatuses\":[\"不参与比对\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post(API).contentType("application/json").content("{\"issueStatuses\":[\"不参与比对\",\"新建\"]}"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].issueId").value("active_table"));
        mvc.perform(get(API).param("issueType", "不参与比对"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].issueId").value("type_only_table"));
        mvc.perform(post(API).contentType("application/json").content("{\"issueTypes\":[\"不参与比对\"]}"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].issueStatus").value("无需处理"));
    }

    @Test
    void headerCandidatesAndOccurrenceBatchesCountOnlyVisibleIssues() throws Exception {
        seedVisibilityRows();

        mvc.perform(get(API + "/filter-options").param("field", "issueStatus").param("issueStatuses", "不参与比对"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(2))
                .andExpect(jsonPath("$.data.matchedIssueCount").value(2))
                .andExpect(jsonPath("$.data.items[*].value", containsInAnyOrder("新建", "无需处理")));
        mvc.perform(post(API + "/filter-options").contentType("application/json")
                        .content("{\"field\":\"domain\",\"domains\":[\"隐藏领域\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.items[0].value").value("当前领域"))
                .andExpect(jsonPath("$.data.items[0].count").value(2));
        mvc.perform(get(API + "/filter-options").param("field", "issueId"))
                .andExpect(jsonPath("$.data.candidateCount").value(2))
                .andExpect(jsonPath("$.data.items[*].value", containsInAnyOrder("active_table", "type_only_table")));
        mvc.perform(post(API + "/filter-options").contentType("application/json").content("{\"field\":\"batchNo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(3))
                .andExpect(jsonPath("$.data.matchedIssueCount").value(2))
                .andExpect(jsonPath("$.data.items[*].value", containsInAnyOrder("DB20261102-1", "DB20261104-1", "DB20261105-1")))
                .andExpect(jsonPath("$.data.items[*].count", containsInAnyOrder(1, 1, 1)));
        mvc.perform(get(API + "/filter-options").param("field", "batchNo").param("keyword", "20261101"))
                .andExpect(jsonPath("$.data.candidateCount").value(0))
                .andExpect(jsonPath("$.data.matchedIssueCount").value(0));
    }

    @Test
    void staticOptionsUseVisibleDomainsOwnersBatchesAndStatuses() throws Exception {
        seedVisibilityRows();

        mvc.perform(get(API + "/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domains", containsInAnyOrder("当前领域")))
                .andExpect(jsonPath("$.data.owners", containsInAnyOrder("当前负责人")))
                .andExpect(jsonPath("$.data.batches", containsInAnyOrder("DB20261104-1", "DB20261105-1")))
                .andExpect(jsonPath("$.data.issueStatuses", not(hasItem("不参与比对"))))
                .andExpect(jsonPath("$.data.issueTypes", hasItem("不参与比对")));
    }

    @Test
    void bothExcelListExportsUseTheVisibleListAndKeepTypeOnlyIssues() throws Exception {
        seedVisibilityRows();

        byte[] getExport = mvc.perform(get(API + "/export").param("differenceCountOrder", "ASC"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(List.of("active_table", "type_only_table"), exportedIssueIds(getExport));
        byte[] postExport = mvc.perform(post(API + "/export").contentType("application/json")
                        .content("{\"issueTypes\":[\"不参与比对\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(List.of("type_only_table"), exportedIssueIds(postExport));
        byte[] hiddenExport = mvc.perform(get(API + "/export").param("issueStatus", "不参与比对"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertTrue(exportedIssueIds(hiddenExport).isEmpty());
    }

    @Test
    void rawLookupsTrackingAndReadRequestsPreserveHiddenRowsAndHistory() throws Exception {
        seedVisibilityRows();
        var beforeRows = jdbc.queryForList("SELECT * FROM dii_replay_database_issue ORDER BY id");
        var beforeHistory = jdbc.queryForList("SELECT * FROM dii_replay_database_issue_history ORDER BY id");
        var hidden = dao.findByIssueId("hidden_table");
        long hiddenId = ((Number) hidden.get("id")).longValue();

        assertEquals("不参与比对", dao.find(hiddenId).get("issueStatus"));
        assertEquals(3, dao.current().size());
        assertEquals(1, dao.history(hiddenId).size());
        assertEquals(1, service.tracking(hiddenId).size());
        mvc.perform(get(API + "/" + hiddenId + "/tracking"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].batchNo").value("DB20261101-1"));
        mvc.perform(get(API)).andExpect(status().isOk());
        mvc.perform(get(API + "/options")).andExpect(status().isOk());
        mvc.perform(get(API + "/filter-options").param("field", "batchNo")).andExpect(status().isOk());
        mvc.perform(get(API + "/export")).andExpect(status().isOk());

        assertEquals(beforeRows, jdbc.queryForList("SELECT * FROM dii_replay_database_issue ORDER BY id"));
        assertEquals(beforeHistory, jdbc.queryForList("SELECT * FROM dii_replay_database_issue_history ORDER BY id"));
    }

    @Test
    void aLaterImportReusesTheHiddenIssueIdAndRetainsItsEarlierHistory() throws Exception {
        service.importFile(file("DB20261101-1", "retained_table"), null, ACTOR);
        var original = dao.findByIssueId("retained_table");
        long id = ((Number) original.get("id")).longValue();
        service.update(id, Map.of("version", original.get("version"), "issueStatus", "打开",
                "issueType", "修改代码", "remark", "保留历史备注"), ACTOR);
        service.importFile(file("DB20261102-1", "other_table"), null, ACTOR);
        var hidden = dao.find(id);
        assertEquals("不参与比对", hidden.get("issueStatus"));
        var historyIds = dao.history(id).stream().map(event -> event.get("id")).toList();
        assertEquals(3, historyIds.size());
        assertEquals(3, service.tracking(id).stream().mapToInt(group -> ((List<?>) group.get("events")).size()).sum());
        mvc.perform(get(API).param("issueIds", "retained_table"))
                .andExpect(jsonPath("$.data.total").value(0));

        service.importFile(file("DB20261103-1", "retained_table"), null, ACTOR);

        var reappeared = dao.findByIssueId("retained_table");
        assertEquals(id, ((Number) reappeared.get("id")).longValue());
        assertEquals("新建", reappeared.get("issueStatus"));
        assertEquals("", reappeared.get("remark"));
        assertEquals("2026-11-03", reappeared.get("firstOccurredDate"));
        assertEquals(4, dao.current().size());
        assertEquals(4, dao.history(id).size());
        assertTrue(dao.history(id).stream().map(event -> event.get("id")).toList().containsAll(historyIds));
        mvc.perform(get(API).param("issueIds", "retained_table"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id))
                .andExpect(jsonPath("$.data.items[0].occurrenceBatches", containsInAnyOrder("DB20261101-1", "DB20261103-1")));
    }

    private void seedVisibilityRows() {
        var active = insert("active_table", "新建", "修改代码", 2, "DB20261104-1", "当前领域", "当前负责人");
        insert("type_only_table", "无需处理", "不参与比对", 8, "DB20261105-1", "当前领域", "当前负责人");
        var hidden = insert("hidden_table", "不参与比对", "修改代码", 0, "DB20261106-1", "隐藏领域", "隐藏负责人");
        dao.history(null, active, active, "DB20261102-1", "导入新增", "2026-11-02 10:00:00", "editor", "编辑人", true);
        dao.history(null, hidden, hidden, "DB20261101-1", "导入新增", "2026-11-01 10:00:00", "editor", "编辑人", true);
    }

    private Map<String, Object> insert(String table, String status, String type, long differences, String batch,
                                       String domain, String owner) {
        var row = new LinkedHashMap<String, Object>();
        row.put("issueId", table);
        row.put("tableName", table);
        row.put("tableComment", table + "中文名");
        row.put("domain", domain);
        row.put("owner", owner);
        row.put("issueLevel", "表级");
        row.put("fieldName", "");
        row.put("differenceCount", differences);
        row.put("batchNo", batch);
        row.put("issueStatus", status);
        row.put("issueType", type);
        dao.insert(row);
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,developer_display,imported_at) VALUES (?,?,?,'2026-11-06 10:00:00')",
                table, domain, owner);
        return row;
    }

    private static List<String> exportedIssueIds(byte[] content) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheetAt(0);
            var issueIds = new ArrayList<String>();
            for (int row = 1; row <= sheet.getLastRowNum(); row++) {
                issueIds.add(sheet.getRow(row).getCell(13).getStringCellValue());
            }
            return issueIds;
        }
    }

    private static MockMultipartFile file(String batch, String table) throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var tableSheet = workbook.createSheet("表明细");
            var fieldSheet = workbook.createSheet("表字段明细");
            var tableHeader = tableSheet.createRow(0);
            List<String> tableHeaders = List.of("表", "仅ccbs存在", "仅528存在", "主键相同、字段不同", "批次号");
            for (int index = 0; index < tableHeaders.size(); index++) {
                tableHeader.createCell(index).setCellValue(tableHeaders.get(index));
            }
            var fieldHeader = fieldSheet.createRow(0);
            List<String> fieldHeaders = List.of("表", "字段名称", "差异数量", "批次号");
            for (int index = 0; index < fieldHeaders.size(); index++) {
                fieldHeader.createCell(index).setCellValue(fieldHeaders.get(index));
            }
            var tableRow = tableSheet.createRow(1);
            tableRow.createCell(0).setCellValue(table);
            tableRow.createCell(1).setCellValue(1);
            tableRow.createCell(2).setCellValue(0);
            tableRow.createCell(3).setCellValue(1);
            tableRow.createCell(4).setCellValue(batch);
            var fieldRow = fieldSheet.createRow(1);
            fieldRow.createCell(0).setCellValue(table);
            fieldRow.createCell(1).setCellValue("value");
            fieldRow.createCell(2).setCellValue(1);
            fieldRow.createCell(3).setCellValue(batch);
            workbook.write(out);
            return new MockMultipartFile("file", "database-issues.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
