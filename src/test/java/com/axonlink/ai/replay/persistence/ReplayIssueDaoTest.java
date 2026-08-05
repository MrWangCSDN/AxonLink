package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueDaoTest {

    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 8, 4, 10, 0);
    private static final ReplayIssueQuery ALL = new ReplayIssueQuery(50, 0, null, null, null, null, null, null);

    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
    }

    @Test
    void replaceFailureKeepsPreviousSnapshot() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")), IMPORTED_AT);
        ReplayIssueRow bad = ReplayIssueTestFixtures.row(null, false, 2, "6209", "bad");

        assertThrows(DataAccessException.class,
                () -> dao.replaceAll(List.of(bad), IMPORTED_AT.plusDays(1)));

        assertEquals("old", dao.list(ALL).get(0).get("issue_description"));
    }

    @Test
    void listCombinesGroupSandboxLevelTypeAndKeyword() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "6208", "CCBS响应不一致"),
                ReplayIssueTestFixtures.row("贷款组", true, 2, "6208", "沙箱数据缺失"),
                ReplayIssueTestFixtures.row("存款组", false, 3, "1001", "CCBS响应不一致")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET transaction_owner=?, cooperation_person_username=?, cooperation_person_real_name=? WHERE transaction_code=? AND is_sandbox=0",
                "张负责人", "sunhy1", "孙海英", "6208");

        ReplayIssueQuery query = new ReplayIssueQuery(50, 0, "贷款组", false,
                "交易级", "数据差异", "CCBS", null, "张负", "海英");

        assertEquals(1, dao.count(query));
        assertEquals("6208", dao.list(query).get(0).get("transaction_code"));
    }

    @Test
    void listNormalizesLegacySandboxDomainToGroupName() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("贷款组", true, 1, "6208", "legacy")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET domain=? WHERE issue_key=?", "沙箱-贷款组", "key-1");

        assertEquals("贷款组", dao.list(ALL).get(0).get("domain"));
    }

    @Test
    void listClampsPageBoundsAndUsesStableGroupSandboxRowAndIdOrder() {
        dao.replaceAll(List.of(
                withIssueKey(ReplayIssueTestFixtures.row("贷款组", true, 2, "T-2", "two"), "key-t2"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "F-2", "false-second"),
                ReplayIssueTestFixtures.row("贷款组", false, 1, "F-1", "false-first"),
                withIssueKey(ReplayIssueTestFixtures.row("贷款组", false, 2, "F-2b", "false-second-by-id"), "key-2b")), IMPORTED_AT);

        List<Map<String, Object>> rows = dao.list(new ReplayIssueQuery(999, -20, null, null, null, null, null, null));

        assertEquals(4, rows.size());
        assertIterableEquals(List.of("F-1", "F-2", "F-2b", "T-2"),
                rows.stream().map(row -> (String) row.get("transaction_code")).toList());
    }

    @Test
    void listDefaultsZeroLimitToFiftyRows() {
        List<ReplayIssueRow> rows = new ArrayList<>();
        for (int rowOrder = 1; rowOrder <= 51; rowOrder++) {
            rows.add(ReplayIssueTestFixtures.row("公共组", false, rowOrder,
                    "T-" + rowOrder, "issue-" + rowOrder));
        }
        dao.replaceAll(rows, IMPORTED_AT);

        assertEquals(50, dao.list(new ReplayIssueQuery(0, 0, null, null, null, null, null, null)).size());
    }

    @Test
    void optionsExcludeBlankValuesAndStatsDescribeCurrentSnapshot() {
        ReplayIssueRow first = ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "first");
        ReplayIssueRow second = new ReplayIssueRow(null, "沙箱-贷款组", "贷款组", true, 2,
                "贷款组", "2", "BATCH-2", "6209", "交易6209", "字段级", "", "字段", "second",
                "", "环境问题", "", "", "", "", "", "002", "", "", "1", "issue-2", "key-2",
                "0", "", "", null);
        ReplayIssueRow blankOptions = new ReplayIssueRow(null, "贷款组", "贷款组", false, 3,
                "贷款组", "3", "BATCH-3", "6210", "交易6210", "", "", "字段", "third",
                "", "", "", "", "", "", "", "003", "", "", "1", "issue-3", "key-3", "0", "", "", null);
        dao.replaceAll(List.of(first, second, blankOptions), IMPORTED_AT);

        ReplayIssueFilterOptions options = dao.options();
        Map<String, Object> stats = dao.stats();

        assertIterableEquals(List.of("公共组", "贷款组"), options.groups());
        assertIterableEquals(List.of("交易级", "字段级"), options.issueLevels());
        assertIterableEquals(List.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "其他问题"), options.issueTypes());
        assertIterableEquals(List.of("打开", "分析中", "延后修复", "修复待验证", "重新打开", "已修复"), options.issueStatuses());
        assertEquals(3L, stats.get("total"));
        assertEquals(3L, stats.get("openTotal"));
        assertEquals(0L, stats.get("processingTotal"));
        assertEquals(0L, stats.get("pendingVerificationTotal"));
        assertEquals(0L, stats.get("fixedTotal"));
        assertEquals(2L, stats.get("groupCount"));
        assertEquals(1L, stats.get("sandboxCount"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> groupCounts = (Map<String, Map<String, Long>>) stats.get("groupCounts");
        assertEquals(2L, groupCounts.get("贷款组").get("total"));
        assertEquals(1L, groupCounts.get("公共组").get("total"));
        assertEquals(IMPORTED_AT, stats.get("importedAt"));
    }

    @Test
    void statusFilterIsExactAndHistoryIsNewestFirstWithSnapshots() {
        ReplayIssueRow row = ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "first");
        dao.replaceAll(List.of(row), IMPORTED_AT);
        long id = ((Number) dao.list(ALL).get(0).get("id")).longValue();
        dao.insertHistory(id, row.issueKey(), "导入新增", IMPORTED_AT, new com.axonlink.ai.replay.dto.ReplayIssueOperator("SYSTEM", "系统"),
                java.time.LocalDate.of(2026, 8, 4), "公共组", 2, null, "{\"issueStatus\":\"打开\"}", "incoming-1");
        dao.insertHistory(id, row.issueKey(), "人工保存", IMPORTED_AT.plusSeconds(1), new com.axonlink.ai.replay.dto.ReplayIssueOperator("u", "用户"),
                java.time.LocalDate.of(2026, 8, 4), null, null, "before-2", "after-2", null);

        assertEquals(1, dao.list(new ReplayIssueQuery(50, 0, null, null, null, null, null, "打开")).size());
        assertEquals(0, dao.list(new ReplayIssueQuery(50, 0, null, null, null, null, null, "分析中")).size());
        var history = dao.findHistoryByIssueId(id, 200);
        assertEquals(2, history.size());
        assertEquals("人工保存", history.get(0).operationType());
        assertEquals("after-2", history.get(0).afterSnapshot());
        assertEquals("{\"issueStatus\":\"打开\"}", history.get(1).afterSnapshot());
    }

    private ReplayIssueRow withIssueKey(ReplayIssueRow row, String issueKey) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(),
                row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(),
                row.fieldName(), row.issueDescription(), row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(),
                row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(),
                row.affectedTransactionCount(), row.issueId(), issueKey, row.historicalOccurrenceCount(), row.firstOccurrenceDate(),
                row.lastOccurrenceDate(), row.importedAt());
    }
}
