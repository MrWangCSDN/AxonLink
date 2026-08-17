package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueGroupSummary;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssuePersonRanking;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)",
                "贷款组", "6208", "交易6208", "张负责人", "王负责人", IMPORTED_AT);

        ReplayIssueQuery query = new ReplayIssueQuery(50, 0, "贷款组", false,
                "交易级", "数据差异", "CCBS", null, "张负", null, "海英",
                null, null, null, null);

        assertEquals(1, dao.count(query));
        assertEquals("6208", dao.list(query).get(0).get("transaction_code"));
    }

    @Test
    void developerAndBankOwnerFiltersAreIndependentAndIgnoreHistoricalTransactionOwner() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-A", "first"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-B", "second")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET transaction_owner='刘科技' WHERE transaction_code='T-B'");
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)",
                List.of(
                        new Object[] {"贷款组", "T-A", "交易A", "张开发", "刘科技", IMPORTED_AT},
                        new Object[] {"贷款组", "T-B", "交易B", "张开发", "王科技", IMPORTED_AT}));

        ReplayIssueQuery both = new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                "张开发", "刘科技", null, null, null, null, null);
        ReplayIssueQuery bankOnly = new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, "刘科技", null, null, null, null, null);

        assertEquals(List.of("T-A"), dao.list(both).stream().map(row -> row.get("transaction_code")).toList());
        assertEquals(List.of("T-A"), dao.list(bankOnly).stream().map(row -> row.get("transaction_code")).toList());
    }

    @Test
    void headerFiltersSplitDeveloperAndBankOwnerListsAndMatchContainedPeople() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-A", "combo"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-B", "single"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "T-C", "other")), IMPORTED_AT);
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)",
                List.of(
                        new Object[] {"贷款组", "T-A", "交易A", "张三(c-zhangs3)、李四(c-lisi)", "刘六(c-liul6)、王七(c-wangq7)", IMPORTED_AT},
                        new Object[] {"贷款组", "T-B", "交易B", "赵六(c-zhaol6)", "刘六(c-liul6)", IMPORTED_AT},
                        new Object[] {"贷款组", "T-C", "交易C", "王五(c-wangw5)", "钱八(c-qianb8)", IMPORTED_AT}));

        ReplayIssueQuery all = new ReplayIssueQuery(50, 0, null, null, null, null, null, null);
        ReplayIssueQuery developerOnly = new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of("李四(c-lisi)"), List.of(), List.of(), List.of(), List.of(), List.of());
        ReplayIssueQuery developerOr = new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of("李四(c-lisi)", "赵六(c-zhaol6)"), List.of(), List.of(), List.of(), List.of(), List.of());
        ReplayIssueQuery developerAndBankOwner = new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of("李四(c-lisi)"), List.of("刘六(c-liul6)"), List.of(), List.of(), List.of(), List.of());

        assertIterableEquals(List.of("张三(c-zhangs3)", "李四(c-lisi)", "王五(c-wangw5)", "赵六(c-zhaol6)"),
                dao.headerFilterValues("developer", all, null));
        assertIterableEquals(List.of("李四(c-lisi)"), dao.headerFilterValues("developer", all, "李四"));
        assertEquals(List.of("T-A"), dao.list(developerOnly).stream().map(row -> row.get("transaction_code")).toList());
        assertEquals(List.of("T-A", "T-B"), dao.list(developerOr).stream().map(row -> row.get("transaction_code")).toList());
        assertEquals(List.of("T-A"), dao.list(developerAndBankOwner).stream().map(row -> row.get("transaction_code")).toList());
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
        assertIterableEquals(List.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "参数问题", "平台问题", "规则差异问题", "合理差异", "其他问题"), options.issueTypes());
        assertIterableEquals(List.of("新建", "打开", "延后修复", "修复待验证", "重新打开", "已修复"), options.issueStatuses());
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
    void groupIssueSummariesCountNonFixedStatusesAndKeepLegacyStatusOnlyInTotal() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "L-OPEN-1", "open-1"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "L-OPEN-2", "open-2"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "L-DEFERRED", "deferred"),
                ReplayIssueTestFixtures.row("贷款组", false, 4, "L-REOPENED", "reopened"),
                ReplayIssueTestFixtures.row("贷款组", false, 5, "L-PENDING", "pending"),
                ReplayIssueTestFixtures.row("贷款组", false, 6, "L-ANALYZING", "analyzing"),
                ReplayIssueTestFixtures.row("贷款组", false, 7, "L-FIXED", "fixed"),
                ReplayIssueTestFixtures.row("存款组", false, 8, "D-OPEN", "deposit-open")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='延后修复' WHERE transaction_code='L-DEFERRED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='重新打开' WHERE transaction_code='L-REOPENED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证' WHERE transaction_code='L-PENDING'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='分析中' WHERE transaction_code='L-ANALYZING'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复' WHERE transaction_code='L-FIXED'");

        List<ReplayIssueGroupSummary> summaries = dao.groupIssueSummaries();

        assertEquals(List.of(
                new ReplayIssueGroupSummary("存款组", 1, 0, 0, 0, 1),
                new ReplayIssueGroupSummary("贷款组", 2, 1, 1, 1, 6)), summaries);
    }

    @Test
    void personIssueRankingsKeepDeveloperCombinationsAndRestartRankForEachGroup() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("存款组", false, 1, "D-1", "deposit"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "L-COMBO-1", "combo-1"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "L-COMBO-2", "combo-2"),
                ReplayIssueTestFixtures.row("贷款组", false, 4, "L-SINGLE", "single"),
                ReplayIssueTestFixtures.row("贷款组", false, 5, "L-UNMATCHED", "unmatched"),
                ReplayIssueTestFixtures.row("贷款组", false, 6, "L-FIXED", "fixed")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='延后修复' WHERE transaction_code='L-COMBO-2'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复' WHERE transaction_code='L-FIXED'");
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                List.of(
                        new Object[] {"存款组", "D-1", "存款交易", "王五(c-wangw5)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-COMBO-1", "组合交易一", "张三(c-zhangs3)、李四(c-lisi)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-COMBO-2", "组合交易二", "张三(c-zhangs3)、李四(c-lisi)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-SINGLE", "单人交易", "赵六(c-zhaol6)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-FIXED", "已修复交易", "赵六(c-zhaol6)", IMPORTED_AT}));

        List<ReplayIssuePersonRanking> rankings = dao.personIssueRankings();

        assertEquals(4, rankings.size());
        assertEquals(new ReplayIssuePersonRanking(1, "存款组", "王五(c-wangw5)", 1, 0, 0, 0, 1), rankings.get(0));
        assertEquals(new ReplayIssuePersonRanking(1, "贷款组", "张三(c-zhangs3)、李四(c-lisi)", 1, 1, 0, 0, 2), rankings.get(1));
        assertTrue(rankings.stream().anyMatch(row -> row.groupName().equals("贷款组")
                && row.developer().equals("未匹配负责人") && row.totalCount() == 1 && row.rank() > 1));
        assertTrue(rankings.stream().noneMatch(row -> row.developer().equals("张三(c-zhangs3)")));
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

    @Test
    void occurrenceRoundsKeepHistoricalMembershipAndExcludeAutomaticRepairs() {
        ReplayIssueRow row = ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "first");
        dao.replaceAll(List.of(row), IMPORTED_AT);
        long issueId = ((Number) dao.list(ALL).get(0).get("id")).longValue();
        ReplayIssueOperator operator = new ReplayIssueOperator("SYSTEM", "系统");
        long firstRoundId = dao.insertImportRound("20260804-001", IMPORTED_AT, operator, 1);
        long secondRoundId = dao.insertImportRound("20260805-001", IMPORTED_AT.plusDays(1), operator, 0);
        dao.insertIssueRound(firstRoundId, issueId, row.issueKey(), true, null, ReplayIssueStatus.OPEN,
                "导入新增", "公共组", 2, IMPORTED_AT);
        dao.insertIssueRound(secondRoundId, issueId, row.issueKey(), false, ReplayIssueStatus.DEFERRED,
                ReplayIssueStatus.FIXED, "自动修复", null, null, IMPORTED_AT.plusDays(1));
        dao.upsertOccurrenceBatch(issueId, row.issueKey(), "20260804-001", IMPORTED_AT, ReplayIssueStatus.OPEN);

        Map<String, Object> listed = dao.list(ALL).get(0);
        assertEquals("20260804-001、BATCH-1", listed.get("occurrence_rounds"));
        assertEquals(1, dao.list(queryForRound("20260804-001")).size());
        assertEquals(0, dao.list(queryForRound("20260805-001")).size());
        assertTrue(dao.coverageRounds().containsAll(List.of("20260804-001", "BATCH-1")));
    }

    @Test
    void findsOnlyMissingActiveStatusesForAutoRepair() {
        dao.replaceAll(List.of(
                withIssueKey(ReplayIssueTestFixtures.row("公共组", false, 1, "T-OPEN", "open"), "K-OPEN"),
                withIssueKey(ReplayIssueTestFixtures.row("公共组", false, 2, "T-REOPENED", "reopened"), "K-REOPENED"),
                withIssueKey(ReplayIssueTestFixtures.row("公共组", false, 3, "T-DEFERRED", "deferred"), "K-DEFERRED"),
                withIssueKey(ReplayIssueTestFixtures.row("公共组", false, 4, "T-PENDING", "pending"), "K-PENDING"),
                withIssueKey(ReplayIssueTestFixtures.row("公共组", false, 5, "T-ANALYZING", "analyzing"), "K-ANALYZING"),
                withIssueKey(ReplayIssueTestFixtures.row("公共组", false, 6, "T-FIXED", "fixed"), "K-FIXED")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='重新打开' WHERE issue_key='K-REOPENED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='延后修复' WHERE issue_key='K-DEFERRED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证' WHERE issue_key='K-PENDING'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='分析中' WHERE issue_key='K-ANALYZING'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复' WHERE issue_key='K-FIXED'");

        List<ReplayIssueRow> candidates = dao.findAutoRepairCandidatesMissing(Set.of("K-OPEN"));

        assertEquals(Set.of("K-REOPENED", "K-DEFERRED", "K-PENDING"),
                candidates.stream().map(ReplayIssueRow::issueKey).collect(java.util.stream.Collectors.toSet()));
    }

    private ReplayIssueQuery queryForRound(String roundCode) {
        return new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, roundCode);
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
