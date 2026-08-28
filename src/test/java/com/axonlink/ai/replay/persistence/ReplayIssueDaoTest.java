package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueGroupSummary;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssuePersonRanking;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.LocalDate;
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
    void currentRowRoundTripsReviewProjection() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "reviewed")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED', reviewer_username='zhangsan', reviewer_real_name='张三', reviewed_at=? WHERE issue_key='key-1'", IMPORTED_AT.plusHours(1));

        ReplayIssueRow row = dao.findCurrentByIdForUpdate(((Number) dao.list(ALL).get(0).get("id")).longValue());

        assertEquals(ReplayIssueReviewStatus.APPROVED, row.reviewStatus());
        assertEquals("zhangsan", row.reviewerUsername());
        assertEquals("张三", row.reviewerRealName());
        assertEquals(IMPORTED_AT.plusHours(1), row.reviewedAt());
    }

    @Test
    void plannedCompletionDateRoundTripsAndCanBeUpdatedIndependently() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "planned")), IMPORTED_AT);
        long id = ((Number) dao.list(ALL).get(0).get("id")).longValue();

        dao.updatePlannedCompletionDate(id, LocalDate.of(2026, 8, 26));

        ReplayIssueRow row = dao.findCurrentByIdForUpdate(id);
        assertEquals(LocalDate.of(2026, 8, 26), row.plannedCompletionDate());
        assertEquals(LocalDate.of(2026, 8, 26), dao.list(ALL).get(0).get("planned_completion_date"));
    }

    @Test
    void genericCurrentUpdateCannotOverwritePlannedCompletionDate() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "before import")), IMPORTED_AT);
        long id = ((Number) dao.list(ALL).get(0).get("id")).longValue();
        dao.updatePlannedCompletionDate(id, LocalDate.of(2026, 8, 26));
        ReplayIssueRow current = dao.findCurrentByIdForUpdate(id);

        dao.updateCurrent(withDescriptionAndPlannedDate(current, "after import", null));

        Map<String, Object> persisted = dao.list(ALL).get(0);
        assertEquals("after import", persisted.get("issue_description"));
        assertEquals(LocalDate.of(2026, 8, 26), persisted.get("planned_completion_date"));
    }

    @Test
    void plannedCompletionDateHeaderValuesAndMultiSelectIncludeEmptyAndComposeWithOtherFilters() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-EMPTY", "empty"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-26", "public-26"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-27", "public-27"),
                ReplayIssueTestFixtures.row("贷款组", false, 4, "T-LOAN-26", "loan-26")), IMPORTED_AT);
        jdbc.batchUpdate("UPDATE dii_replay_issue SET planned_completion_date=? WHERE transaction_code=?", List.of(
                new Object[]{LocalDate.of(2026, 8, 26), "T-26"},
                new Object[]{LocalDate.of(2026, 8, 27), "T-27"},
                new Object[]{LocalDate.of(2026, 8, 26), "T-LOAN-26"}));

        assertEquals(List.of("空", "2026-08-26", "2026-08-27"),
                dao.headerFilterValues("plannedCompletionDate", ALL, null));

        ReplayIssueQuery query = new ReplayIssueQuery(50, 0, "公共组", null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                null, List.of(), null, List.of(), List.of(), List.of("2026-08-26", "空"));

        assertEquals(2L, dao.count(query));
        assertEquals(List.of("T-EMPTY", "T-26"), dao.list(query).stream()
                .map(row -> row.get("transaction_code")).toList());
    }

    @Test
    void issueSerialGlobalSerialAndDefectDateHeaderFiltersSupportSearchEmptyAndComposition() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-EMPTY", "empty"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-100", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-200", "second")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_id='',serial_no='',global_serial_no='',defect_repair_date=NULL WHERE transaction_code='T-EMPTY'");
        jdbc.update("UPDATE dii_replay_issue SET issue_id='ISS-100',serial_no='SER-AAA-100',global_serial_no='GS-100',defect_repair_date='2026-08-20' WHERE transaction_code='T-100'");
        jdbc.update("UPDATE dii_replay_issue SET issue_id='ISS-200',serial_no='SER-BBB-200',global_serial_no='GS-200',defect_repair_date='2026-08-21' WHERE transaction_code='T-200'");

        assertEquals(List.of("空", "ISS-100", "ISS-200"), dao.headerFilterValues("issueId", ALL, null));
        assertEquals(List.of("ISS-200"), dao.headerFilterValues("issueId", ALL, "SS-2"));
        assertEquals(List.of("SER-BBB-200"), dao.headerFilterValues("serialNo", ALL, "BBB"));
        assertEquals(List.of("GS-200"), dao.headerFilterValues("globalSerialNo", ALL, "GS-2"));
        assertEquals(List.of("2026-08-20", "2026-08-21"), dao.headerFilterValues("defectRepairDate", ALL, "2026-08"));

        ReplayIssueQuery issueOrEmpty = queryWithFourHeaderFilters(
                List.of("ISS-100", "空"), List.of(), List.of(), List.of());
        assertEquals(List.of("T-EMPTY", "T-100"), dao.list(issueOrEmpty).stream()
                .map(row -> row.get("transaction_code")).toList());

        ReplayIssueQuery composed = queryWithFourHeaderFilters(
                List.of(), List.of("SER-AAA-100", "SER-BBB-200"), List.of("GS-200"), List.of("2026-08-21"));
        assertEquals(List.of("T-200"), dao.list(composed).stream()
                .map(row -> row.get("transaction_code")).toList());
        assertEquals(1L, dao.count(composed));
    }

    private static ReplayIssueQuery queryWithFourHeaderFilters(
            List<String> issueIds, List<String> serialNos, List<String> globalSerialNos,
            List<String> defectRepairDates) {
        return new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                null, List.of(), null, List.of(), List.of(), List.of(),
                issueIds, serialNos, globalSerialNos, defectRepairDates);
    }

    @Test
    void reviewStatusFiltersAndNoActionStatisticsUseDisplayValues() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-1", "pending"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-2", "approved"),
                ReplayIssueTestFixtures.row("存款组", false, 3, "T-3", "open")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='PENDING' WHERE issue_key='key-1'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='APPROVED' WHERE issue_key='key-2'");

        ReplayIssueQuery pending = new ReplayIssueQuery(50, 0, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                "待审核", List.of());
        ReplayIssueQuery blankReview = new ReplayIssueQuery(50, 0, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                "空", List.of());

        assertEquals(1L, dao.count(pending));
        assertEquals(1L, dao.count(blankReview));
        assertEquals("待审核", dao.list(pending).get(0).get("review_status"));
        assertEquals(List.of("空", "已审核", "待审核"), dao.headerFilterValues("reviewStatus", ALL, null));
        assertEquals(2L, dao.stats().get("noActionTotal"));
        assertEquals(2L, dao.groupIssueSummaries().stream()
                .filter(summary -> "公共组".equals(summary.groupName())).findFirst().orElseThrow().noActionCount());
        assertEquals(List.of("待审核", "已审核"), dao.options().reviewStatuses());
    }

    @Test
    void listCombinesGroupSandboxLevelTypeAndKeyword() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "6208", "CCBS响应不一致"),
                ReplayIssueTestFixtures.row("贷款组", true, 2, "6208", "沙箱数据缺失"),
                ReplayIssueTestFixtures.row("存款组", false, 3, "1001", "CCBS响应不一致")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET transaction_owner=?, cooperation_person_username=?, cooperation_person_real_name=? WHERE transaction_code=? AND is_sandbox=0",
                "张负责人", "sunhy1", "孙海英", "6208");
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,bank_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?)",
                "贷款组", "6208", "交易6208", "张负责人", "王负责人", "100001、100002", IMPORTED_AT);

        ReplayIssueQuery query = new ReplayIssueQuery(50, 0, "贷款组", false,
                "交易级", "数据差异", "CCBS", null, "张负", null, "海英",
                null, null, null, null);

        assertEquals(1, dao.count(query));
        assertEquals("6208", dao.list(query).get(0).get("transaction_code"));
        assertEquals("100001、100002", dao.list(query).get(0).get("matched_bank_owner_emp_nos"));
    }

    @Test
    void issueIdGroupAndSandboxFiltersComposeAcrossListCountAndHeaderOptions() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-1", "public-normal"),
                ReplayIssueTestFixtures.row("公共组", true, 2, "T-2", "public-sandbox"),
                ReplayIssueTestFixtures.row("存款组", false, 3, "T-3", "deposit-normal"),
                ReplayIssueTestFixtures.row("贷款组", true, 4, "T-4", "loan-sandbox")), IMPORTED_AT);
        jdbc.batchUpdate("UPDATE dii_replay_issue SET issue_id=? WHERE transaction_code=?", List.of(
                new Object[]{"ISSUE-ALPHA-001", "T-1"},
                new Object[]{"ISSUE-ALPHA-002", "T-2"},
                new Object[]{"ISSUE-BETA-003", "T-3"},
                new Object[]{"ISSUE-ALPHA-004", "T-4"}));

        ReplayIssueQuery issueIdOnly = queryWithNewFilters("ALPHA", List.of(), List.of());
        ReplayIssueQuery composed = queryWithNewFilters("ALPHA", List.of("公共组", "存款组"), List.of("是"));

        assertEquals(3L, dao.count(issueIdOnly));
        assertEquals(List.of("ISSUE-ALPHA-001", "ISSUE-ALPHA-002", "ISSUE-ALPHA-004"),
                dao.list(issueIdOnly).stream().map(row -> row.get("issue_id")).toList());
        assertEquals(1L, dao.count(composed));
        assertEquals(List.of("T-2"), dao.list(composed).stream()
                .map(row -> row.get("transaction_code")).toList());
        assertEquals(List.of("公共组", "贷款组"), dao.headerFilterValues("groupName", issueIdOnly, null));
        assertEquals(List.of("否", "是"), dao.headerFilterValues("sandbox", issueIdOnly, null));
    }

    private static ReplayIssueQuery queryWithNewFilters(String issueId, List<String> groupNames, List<String> sandboxes) {
        return new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                null, List.of(), issueId, groupNames, sandboxes);
    }

    private static ReplayIssueRow withDescriptionAndPlannedDate(ReplayIssueRow row, String description,
                                                                 LocalDate plannedCompletionDate) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
                row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
                row.issueLevel(), row.registeredDate(), row.fieldName(), description, row.transactionOwner(),
                row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(),
                row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(),
                row.firstOccurrenceDate(), row.lastOccurrenceDate(), row.importedAt(), row.issueStatus(),
                row.importDate(), row.defectRepairDate(), row.cooperationPersonUsername(),
                row.cooperationPersonRealName(), row.globalSerialNo(), row.reviewStatus(), row.reviewerUsername(),
                row.reviewerRealName(), row.reviewedAt(), plannedCompletionDate);
    }

    @Test
    void weeklyTaskProjectionAndFilterUseOccurrenceBatchUnionWithoutDuplicates() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-A", "weekly-a"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-B", "weekly-b"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "T-C", "ordinary")), IMPORTED_AT);
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                + "SELECT id,issue_key,'TASK-B',?,?,?,? FROM dii_replay_issue WHERE issue_key='key-1'",
                IMPORTED_AT, IMPORTED_AT, IMPORTED_AT, IMPORTED_AT);
        jdbc.batchUpdate("INSERT INTO dii_replay_weekly_task_batch(batch_name) VALUES (?)",
                List.of(new Object[]{"BATCH-1"}, new Object[]{"TASK-B"}, new Object[]{"BATCH-2"}));

        List<Map<String, Object>> allRows = dao.list(ALL);
        ReplayIssueQuery weeklyPublic = new ReplayIssueQuery(50, 0, "公共组", null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertEquals(Boolean.TRUE, allRows.get(0).get("weekly_task"));
        assertEquals(Boolean.TRUE, allRows.get(1).get("weekly_task"));
        assertEquals(Boolean.FALSE, allRows.get(2).get("weekly_task"));
        assertEquals(2L, dao.count(weeklyPublic));
        assertEquals(List.of("T-A", "T-B"), dao.list(weeklyPublic).stream()
                .map(row -> row.get("transaction_code")).toList());
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
        assertIterableEquals(List.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "参数问题", "平台问题", "规则差异问题", "合理差异", "外围问题", "其他问题"), options.issueTypes());
        assertIterableEquals(List.of("新建", "打开", "无需处理", "延后修复", "修复待验证", "重新打开", "已修复"), options.issueStatuses());
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
    void groupIssueSummariesCountAllFormalStatusesAndExcludeUnknownStatusFromTotal() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "L-NEW", "new"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "L-OPEN", "open"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "L-DEFERRED", "deferred"),
                ReplayIssueTestFixtures.row("贷款组", false, 4, "L-REOPENED", "reopened"),
                ReplayIssueTestFixtures.row("贷款组", false, 5, "L-PENDING", "pending"),
                ReplayIssueTestFixtures.row("贷款组", false, 6, "L-NO-ACTION", "no-action"),
                ReplayIssueTestFixtures.row("贷款组", false, 7, "L-FIXED", "fixed"),
                ReplayIssueTestFixtures.row("贷款组", false, 8, "L-ANALYZING", "analyzing"),
                ReplayIssueTestFixtures.row("存款组", false, 9, "D-OPEN", "deposit-open")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='新建' WHERE transaction_code='L-NEW'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='延后修复' WHERE transaction_code='L-DEFERRED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='重新打开' WHERE transaction_code='L-REOPENED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证' WHERE transaction_code='L-PENDING'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='无需处理' WHERE transaction_code='L-NO-ACTION'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复' WHERE transaction_code='L-FIXED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='分析中' WHERE transaction_code='L-ANALYZING'");

        List<ReplayIssueGroupSummary> summaries = dao.groupIssueSummaries();

        ReplayIssueGroupSummary deposit = summaries.stream().filter(row -> "存款组".equals(row.groupName())).findFirst().orElseThrow();
        assertEquals(1, deposit.openCount());
        assertEquals(1, deposit.totalCount());
        ReplayIssueGroupSummary loan = summaries.stream().filter(row -> "贷款组".equals(row.groupName())).findFirst().orElseThrow();
        assertEquals(1, loan.newCount());
        assertEquals(1, loan.openCount());
        assertEquals(1, loan.reopenedCount());
        assertEquals(1, loan.deferredCount());
        assertEquals(1, loan.pendingVerificationCount());
        assertEquals(1, loan.noActionCount());
        assertEquals(7, loan.totalCount());
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
        assertEquals(1, rankings.stream().filter(row -> row.groupName().equals("存款组")).mapToLong(ReplayIssuePersonRanking::totalCount).sum());
        assertEquals(5, rankings.stream().filter(row -> row.groupName().equals("贷款组")).mapToLong(ReplayIssuePersonRanking::totalCount).sum());
        assertEquals(2, rankings.stream().filter(row -> row.developer().equals("赵六(c-zhaol6)")).findFirst().orElseThrow().totalCount());
        assertEquals(2, rankings.stream().filter(row -> row.developer().equals("张三(c-zhangs3)、李四(c-lisi)")).findFirst().orElseThrow().totalCount());
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
