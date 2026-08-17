package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueFullRefreshResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueFullRefreshServiceTest {

    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 8, 7, 16, 0);
    private static final ReplayIssueQuery ALL = new ReplayIssueQuery(50, 0, null, null, null, null, null);

    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private SysUserDao userDao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "username VARCHAR(128), real_name VARCHAR(128), emp_no VARCHAR(64), email VARCHAR(128),"
                + "phone VARCHAR(64), department VARCHAR(128), status INT, remark VARCHAR(255),"
                + "creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        dao = new ReplayIssueDao(jdbc);
        userDao = new SysUserDao(jdbc);
    }

    @Test
    void updatesByIssueKeyInsertsNewRowsAndPreservesUnmentionedRowsAndHistory() throws Exception {
        long matchedId = seedCurrentWithHistory("OLD-MATCHED-ID", "MATCHED-KEY", "old matched", "旧类型");
        long untouchedId = seedCurrentWithHistory("OLD-UNTOUCHED-ID", "UNTOUCHED-KEY", "old untouched", "保留类型");
        jdbc.update("INSERT INTO ccbs_ai_sys_user (username,real_name,status) VALUES (?,?,?)", "sunhy1", "孙海英", 1);

        ReplayIssueFullRefreshResult result = service(dao).fullRefresh(workbook(List.of(
                        row("EXCEL-ID", "MATCHED-KEY", "new matched", "", "孙海英"),
                        row("NEW-ID", "NEW-KEY", "new row", "代码问题", "不存在用户"))),
                new ReplayIssueOperator("admin", "管理员"));

        assertEquals(2, result.totalRows());
        assertEquals(Map.of("基础数据", 2), result.rowsBySheet());
        assertEquals(IMPORTED_AT, result.importedAt());
        assertEquals(3L, dao.count(ALL));

        List<Map<String, Object>> rows = dao.list(ALL);
        Map<String, Object> matched = byKey(rows, "MATCHED-KEY");
        assertEquals(matchedId, ((Number) matched.get("id")).longValue());
        assertEquals("EXCEL-ID", matched.get("issue_id"));
        assertEquals("new matched", matched.get("issue_description"));
        assertEquals("", matched.get("issue_type"));
        assertEquals("sunhy1", matched.get("cooperation_person_username"));
        assertNull(matched.get("data_repair_date"));
        assertNull(matched.get("defect_repair_date"));

        Map<String, Object> untouched = byKey(rows, "UNTOUCHED-KEY");
        assertEquals(untouchedId, ((Number) untouched.get("id")).longValue());
        assertEquals("old untouched", untouched.get("issue_description"));
        assertEquals(1L, dao.countHistory("UNTOUCHED-KEY"));

        var updateHistory = dao.findHistoryByIssueId(matchedId, 10).get(0);
        assertEquals("全量基础数据覆盖", updateHistory.operationType());
        assertTrue(updateHistory.beforeSnapshot().contains("old matched"));
        assertTrue(updateHistory.afterSnapshot().contains("new matched"));
        assertTrue(updateHistory.incomingSnapshot().contains("new matched"));
        assertEquals(2L, dao.countHistory("MATCHED-KEY"));

        long newId = ((Number) byKey(rows, "NEW-KEY").get("id")).longValue();
        var insertHistory = dao.findHistoryByIssueId(newId, 10).get(0);
        assertEquals("全量基础数据导入", insertHistory.operationType());
        assertNull(insertHistory.beforeSnapshot());
        assertEquals(1L, dao.countHistory("NEW-KEY"));
    }

    @Test
    void generatesMissingIdsAndKeysIndependentlyAfterExistingAutoSequences() throws Exception {
        seedCurrentWithHistory("AUTO-000007", "AUTO-0731-000011", "legacy generated", "旧类型");

        ReplayIssueFullRefreshResult result = service(dao).fullRefresh(workbook(List.of(
                row("", "KEY-A", "blank id", "代码问题", ""),
                row("ID-B", "", "blank key", "代码问题", ""),
                row("", "", "both blank", "代码问题", ""))), ReplayIssueOperator.system());

        assertEquals(3, result.generatedIdentityRows());
        List<Map<String, Object>> rows = dao.list(ALL);
        assertEquals("AUTO-000008", byKey(rows, "KEY-A").get("issue_id"));
        assertEquals("ID-B", byDescription(rows, "blank key").get("issue_id"));
        assertEquals("AUTO-KEY-000012", byDescription(rows, "blank key").get("issue_key"));
        assertEquals("AUTO-000009", byDescription(rows, "both blank").get("issue_id"));
        assertEquals("AUTO-KEY-000013", byDescription(rows, "both blank").get("issue_key"));
    }

    @Test
    void historyFailureRollsBackUpdatesInsertsAndHistory() {
        long matchedId = seedCurrentWithHistory("OLD-ID", "MATCHED-KEY", "old matched", "旧类型");
        seedCurrentWithHistory("KEEP-ID", "UNTOUCHED-KEY", "old untouched", "保留类型");
        ReplayIssueDao failingDao = new ReplayIssueDao(jdbc) {
            private int historyInserts;

            @Override
            public void insertHistory(Long replayIssueId, String issueKey, String operationType,
                                      LocalDateTime operationAt, ReplayIssueOperator operator,
                                      java.time.LocalDate importDate, String sourceSheet, Integer sourceRow,
                                      String beforeSnapshot, String afterSnapshot, String incomingSnapshot) {
                if (++historyInserts == 2) {
                    throw new IllegalStateException("history unavailable");
                }
                super.insertHistory(replayIssueId, issueKey, operationType, operationAt, operator, importDate,
                        sourceSheet, sourceRow, beforeSnapshot, afterSnapshot, incomingSnapshot);
            }
        };

        assertThrows(IllegalStateException.class, () -> service(failingDao).fullRefresh(workbook(List.of(
                row("NEW-ID", "MATCHED-KEY", "new matched", "", ""),
                row("INSERT-ID", "INSERT-KEY", "new row", "", ""))), ReplayIssueOperator.system()));

        assertEquals(2L, failingDao.count(ALL));
        assertEquals("old matched", byKey(failingDao.list(ALL), "MATCHED-KEY").get("issue_description"));
        assertFalse(failingDao.list(ALL).stream().anyMatch(row -> "INSERT-KEY".equals(row.get("issue_key"))));
        assertEquals(1L, failingDao.countHistory("MATCHED-KEY"));
        assertEquals(1L, failingDao.countHistory("UNTOUCHED-KEY"));
        assertEquals(0L, failingDao.countHistory("INSERT-KEY"));
        assertEquals(1, dao.findHistoryByIssueId(matchedId, 10).size());
    }

    @Test
    void springSelectsProductionConstructorAndWiresDependencies() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReplayIssueFullRefreshExcelParser.class, ReplayIssueFullRefreshExcelParser::new);
            context.registerBean(ReplayIssueDao.class, () -> dao);
            context.registerBean(SysUserDao.class, () -> userDao);
            context.registerBean(ReplayIssueImportGate.class, () -> new ReplayIssueImportGate());
            context.register(ReplayIssueFullRefreshService.class);
            context.refresh();

            ReplayIssueFullRefreshResult result = context.getBean(ReplayIssueFullRefreshService.class)
                    .fullRefresh(workbook(List.of(row("ID-1", "KEY-1", "one", "代码问题", ""))),
                            ReplayIssueOperator.system());

            assertEquals(1, result.totalRows());
            assertEquals(1, dao.count(ALL));
        }
    }

    private ReplayIssueFullRefreshService service(ReplayIssueDao currentDao) {
        Clock clock = Clock.fixed(IMPORTED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new ReplayIssueFullRefreshService(new ReplayIssueFullRefreshExcelParser(), currentDao, userDao,
                new ReplayIssueImportGate(), clock);
    }

    private long seedCurrentWithHistory(String issueId, String issueKey, String description, String issueType) {
        ReplayIssueRow base = ReplayIssueTestFixtures.row("公共组", false, 1, "6208", description);
        ReplayIssueRow stored = new ReplayIssueRow(null, base.sourceSheet(), base.groupName(), base.sandbox(), base.rowOrder(),
                base.domain(), base.sequenceNo(), base.batchNo(), base.transactionCode(), base.transactionName(),
                base.issueLevel(), base.registeredDate(), base.fieldName(), base.issueDescription(), base.transactionOwner(),
                issueType, base.initialAnalysis(), base.finalSolution(), base.resolvedDate(), base.cooperationGroup(),
                base.resolver(), base.serialNo(), base.dataRepairDate(), base.remark(), base.affectedTransactionCount(),
                issueId, issueKey, base.historicalOccurrenceCount(), base.firstOccurrenceDate(),
                base.lastOccurrenceDate(), IMPORTED_AT);
        long id = dao.insertCurrent(stored);
        dao.insertHistory(id, issueKey, "旧历史", IMPORTED_AT.minusDays(1), ReplayIssueOperator.system(),
                IMPORTED_AT.minusDays(1).toLocalDate(), "旧页签", 2, null,
                "{\"issueDescription\":\"" + description + "\"}",
                "{\"issueDescription\":\"" + description + "\"}");
        return id;
    }

    private MockMultipartFile workbook(List<Map<String, String>> firstSheetRows) {
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("基础数据", firstSheetRows);
        sheets.put("后续页签", List.of(row("IGNORED-ID", "IGNORED-KEY", "ignored", "代码问题", "")));
        return ReplayIssueTestFixtures.fullRefreshWorkbook(sheets);
    }

    private Map<String, String> row(String issueId, String issueKey, String description,
                                    String issueType, String collaborator) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String header : ReplayIssueTestFixtures.FULL_REFRESH_HEADERS) {
            row.put(header, "");
        }
        row.put("领域", "公共组");
        row.put("问题描述", description);
        row.put("问题类型", issueType);
        row.put("初步问题分析", "分析");
        row.put("最终处理方案", "方案");
        row.put("协同人", collaborator);
        row.put("数据修复日期", "2026-08-01");
        row.put("备注", "备注");
        row.put("issue_id", issueId);
        row.put("issue_key", issueKey);
        row.put("问题状态", "延后修复");
        row.put("是否沙箱", "否");
        return row;
    }

    private Map<String, Object> byKey(List<Map<String, Object>> rows, String key) {
        return rows.stream().filter(row -> key.equals(row.get("issue_key"))).findFirst().orElseThrow();
    }

    private Map<String, Object> byDescription(List<Map<String, Object>> rows, String description) {
        return rows.stream().filter(row -> description.equals(row.get("issue_description"))).findFirst().orElseThrow();
    }
}
