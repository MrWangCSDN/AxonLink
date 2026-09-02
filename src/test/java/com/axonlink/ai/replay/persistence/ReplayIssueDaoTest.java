package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueAffectedTransactionCountOrder;
import com.axonlink.ai.replay.dto.ReplayIssueGroupSummary;
import com.axonlink.ai.replay.dto.ReplayIssueHeaderFilterOption;
import com.axonlink.ai.replay.dto.ReplayIssueHeaderFilterOptionResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssuePersonRanking;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDateChangeEntry;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueReplayType;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueDaoTest {

    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 8, 4, 10, 0);
    private static final ReplayIssueQuery ALL = new ReplayIssueQuery(50, 0, null, null, null, null, null, null);

    @Test
    void replayTypeDefaultsToAllAndRejectsUnknownValue() {
        assertEquals(ReplayIssueReplayType.ALL, ReplayIssueReplayType.parse(null));
        assertEquals(ReplayIssueReplayType.ALL, ReplayIssueReplayType.parse(" "));
        assertEquals(ReplayIssueReplayType.DZ, ReplayIssueReplayType.parse("dz"));
        assertEquals(ReplayIssueReplayType.QUERY, ReplayIssueReplayType.parse("QUERY"));
        assertEquals(ReplayIssueReplayType.ALL,
                new ReplayIssueQuery(50, 0, null, null, null, null, null).replayType());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReplayIssueReplayType.parse("OTHER"));
        assertEquals("回放交易类型不合法", error.getMessage());
    }

    @Test
    void filtersListCountOptionsAndExportByReplayType() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "RPT-ONLY", "rpt"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "DZ-ONLY", "dz"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "BOTH", "both"),
                ReplayIssueTestFixtures.row("公共组", false, 4, "LEGACY", "legacy")), IMPORTED_AT);
        jdbc.update("DELETE FROM dii_replay_issue_occurrence_batch");
        insertOccurrenceBatch("RPT-ONLY", "RPT20260901-001");
        insertOccurrenceBatch("DZ-ONLY", "DZ20260901-001");
        insertOccurrenceBatch("BOTH", "RPT20260901-001");
        insertOccurrenceBatch("BOTH", "RPT20260902-001");
        insertOccurrenceBatch("BOTH", "DZ20260901-001");
        insertOccurrenceBatch("LEGACY", "20260901-001");

        ReplayIssueQuery queryOnly = withReplayType(ALL, ReplayIssueReplayType.QUERY);
        ReplayIssueQuery dzOnly = withReplayType(ALL, ReplayIssueReplayType.DZ);

        assertEquals(Set.of("RPT-ONLY", "BOTH"), transactionCodes(dao.list(queryOnly)));
        assertEquals(Set.of("DZ-ONLY", "BOTH"), transactionCodes(dao.list(dzOnly)));
        assertEquals(Set.of("RPT-ONLY", "DZ-ONLY", "BOTH", "LEGACY"), transactionCodes(dao.list(ALL)));
        assertEquals(2L, dao.count(queryOnly));
        assertEquals(2, dao.listForExport(queryOnly).size());
        assertEquals(Set.of("RPT20260901-001", "RPT20260902-001"),
                Set.copyOf(dao.headerFilterValues("occurrenceBatch", queryOnly, null)));

        ReplayIssueQuery oneBatch = withReplayType(new ReplayIssueQuery(50, 0, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("RPT20260902-001")), ReplayIssueReplayType.QUERY);
        assertEquals(Set.of("BOTH"), transactionCodes(dao.list(oneBatch)));
    }

    @Test
    void filtersAllSummaryStatisticsByReplayType() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "RPT-ONLY", "rpt"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "DZ-ONLY", "dz"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "BOTH", "both"),
                ReplayIssueTestFixtures.row("贷款组", false, 4, "LEGACY", "legacy")), IMPORTED_AT);
        jdbc.update("DELETE FROM dii_replay_issue_occurrence_batch");
        insertOccurrenceBatch("RPT-ONLY", "RPT20260901-001");
        insertOccurrenceBatch("DZ-ONLY", "DZ20260901-001");
        insertOccurrenceBatch("BOTH", "RPT20260901-001");
        insertOccurrenceBatch("BOTH", "RPT20260902-001");
        insertOccurrenceBatch("BOTH", "DZ20260901-001");
        insertOccurrenceBatch("LEGACY", "20260901-001");

        assertSummaryTotal(2, ReplayIssueReplayType.QUERY);
        assertSummaryTotal(2, ReplayIssueReplayType.DZ);
        assertSummaryTotal(4, ReplayIssueReplayType.ALL);
    }

    @Test
    void affectedTransactionCountOrderingIsNumericStableAndKeepsInvalidValuesLast() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-10", "ten"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-2", "two"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-BLANK", "blank"),
                ReplayIssueTestFixtures.row("公共组", false, 4, "T-BAD", "bad")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='10' WHERE transaction_code='T-10'");
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='2' WHERE transaction_code='T-2'");
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='' WHERE transaction_code='T-BLANK'");
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='bad' WHERE transaction_code='T-BAD'");

        assertEquals(List.of("T-2", "T-10", "T-BLANK", "T-BAD"),
                dao.list(ALL, ReplayIssueAffectedTransactionCountOrder.ASC).stream()
                        .map(row -> row.get("transaction_code")).toList());
        assertEquals(List.of("T-10", "T-2", "T-BLANK", "T-BAD"),
                dao.list(ALL, ReplayIssueAffectedTransactionCountOrder.DESC).stream()
                        .map(row -> row.get("transaction_code")).toList());
        assertEquals(List.of("T-10", "T-2", "T-BLANK", "T-BAD"),
                dao.list(ALL).stream().map(row -> row.get("transaction_code")).toList());
    }

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
    void issueDomainInitializesFromGroupAndListProjectsTransferCount() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "domain")), IMPORTED_AT);
        Map<String, Object> initial = dao.list(ALL).get(0);
        long id = ((Number) initial.get("id")).longValue();

        assertEquals("公共组", initial.get("issue_domain"));
        assertEquals(0L, ((Number) initial.get("issue_domain_transfer_count")).longValue());

        jdbc.update("INSERT INTO dii_replay_issue_domain_transfer "
                        + "(replay_issue_id,issue_key,from_domain,to_domain,operator_username,operator_real_name,transferred_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                id, "key-1", "公共组", "平台组", "c-zhangsan", "张三", IMPORTED_AT.plusHours(1));

        Map<String, Object> transferred = dao.list(ALL).get(0);
        assertEquals(1L, ((Number) transferred.get("issue_domain_transfer_count")).longValue());
    }

    @Test
    void planDateChangesRoundTripNewestFirstAndListProjectsCount() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "plan history")), IMPORTED_AT);
        Map<String, Object> initial = dao.list(ALL).get(0);
        long id = ((Number) initial.get("id")).longValue();
        ReplayIssueOperator zhang = new ReplayIssueOperator("c-zhangs", "张三");
        ReplayIssueOperator li = new ReplayIssueOperator("c-lisi", "李四");

        assertEquals(0L, ((Number) initial.get("planned_completion_date_change_count")).longValue());
        dao.insertPlanDateChange(id, "key-1", LocalDate.of(2026, 8, 5), li, IMPORTED_AT.plusMinutes(1));
        dao.insertPlanDateChange(id, "key-1", LocalDate.of(2026, 8, 7), zhang, IMPORTED_AT.plusMinutes(2));
        dao.insertPlanDateChange(id, "key-1", null, zhang, IMPORTED_AT.plusMinutes(3));

        assertEquals(3L, dao.countPlanDateChanges(id));
        assertEquals(3L, ((Number) dao.list(ALL).get(0).get("planned_completion_date_change_count")).longValue());
        List<ReplayIssuePlanDateChangeEntry> items = dao.listPlanDateChanges(id);
        assertEquals(3, items.size());
        assertNull(items.get(0).plannedCompletionDate());
        assertEquals(LocalDate.of(2026, 8, 7), items.get(1).plannedCompletionDate());
        assertEquals("张三", items.get(1).operatorRealName());
        assertEquals(LocalDate.of(2026, 8, 5), items.get(2).plannedCompletionDate());
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
    void issueDomainHeaderCandidatesUseStoredValueAndFallbackToGroup() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-FALLBACK", "fallback"),
                ReplayIssueTestFixtures.row("存款组", false, 2, "T-PLATFORM", "platform"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "T-DEPOSIT", "deposit")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_domain='' WHERE transaction_code='T-FALLBACK'");
        jdbc.update("UPDATE dii_replay_issue SET issue_domain='平台组' WHERE transaction_code='T-PLATFORM'");
        jdbc.update("UPDATE dii_replay_issue SET issue_domain='存款组' WHERE transaction_code='T-DEPOSIT'");

        assertEquals(List.of("公共组", "存款组", "平台组"),
                dao.headerFilterValues("issueDomain", ALL, null));
        ReplayIssueHeaderFilterOptionResult counted = dao.headerFilterOptionCounts("issueDomain", ALL, "组");
        assertEquals(3, counted.candidateCount());
        assertEquals(3L, counted.matchedIssueCount());
        assertEquals(List.of("公共组", "存款组", "平台组"),
                counted.items().stream().map(ReplayIssueHeaderFilterOption::value).toList());
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
    void longTextHeaderFiltersSupportFuzzyCandidatesEmptyValuesAndCrossFieldComposition() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-EMPTY", "empty"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-100", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-200", "second")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='',field_name='',issue_description='',issue_key='' WHERE transaction_code='T-EMPTY'");
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='账户查询',field_name='custName',issue_description='新老核心客户名称不一致',issue_key='TC001|custName' WHERE transaction_code='T-100'");
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='账户维护',field_name='accountStatus',issue_description='新老核心账户状态不一致',issue_key='TC002|accountStatus' WHERE transaction_code='T-200'");

        assertEquals(List.of("账户查询"), dao.headerFilterValues("transactionName", ALL, "查询"));
        assertEquals(List.of("custName"), dao.headerFilterValues("fieldName", ALL, "cust"));
        assertEquals(List.of("新老核心客户名称不一致"), dao.headerFilterValues("issueDescription", ALL, "客户名称"));
        assertEquals(List.of("TC002|accountStatus"), dao.headerFilterValues("issueKey", ALL, "TC002"));
        assertTrue(dao.headerFilterValues("transactionName", ALL, null).contains("空"));

        ReplayIssueQuery query = queryWithLongTextHeaderFilters(
                List.of("账户查询", "账户维护"), List.of("accountStatus"),
                List.of("新老核心账户状态不一致"), List.of("TC002|accountStatus"));

        assertEquals(1L, dao.count(query));
        assertEquals(List.of("T-200"), dao.list(query).stream()
                .map(row -> row.get("transaction_code")).toList());
    }

    @Test
    void headerFilterOptionCountsNormalizeSearchAndDeduplicateIssues() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-1", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-2", "second"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-3", "third")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='账户查询' WHERE transaction_code IN ('T-1','T-2')");
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='  ' WHERE transaction_code='T-3'");
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'RPT20260830-100000-0001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='T-1'",
                IMPORTED_AT, IMPORTED_AT, IMPORTED_AT, IMPORTED_AT);
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'RPT20260830-100000-0001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='T-2'",
                IMPORTED_AT, IMPORTED_AT, IMPORTED_AT, IMPORTED_AT);

        ReplayIssueHeaderFilterOptionResult all = dao.headerFilterOptionCounts("transactionName", ALL, null);
        assertEquals(2, all.candidateCount());
        assertEquals(3L, all.matchedIssueCount());
        assertFalse(all.truncated());
        assertEquals(List.of(
                new ReplayIssueHeaderFilterOption("空", 1),
                new ReplayIssueHeaderFilterOption("账户查询", 2)), all.items());

        ReplayIssueHeaderFilterOptionResult searched = dao.headerFilterOptionCounts("transactionName", ALL, "查询");
        assertEquals(2L, searched.matchedIssueCount());
        assertEquals(List.of(new ReplayIssueHeaderFilterOption("账户查询", 2)), searched.items());

        ReplayIssueHeaderFilterOptionResult batches = dao.headerFilterOptionCounts("occurrenceBatch", ALL, "RPT20260830");
        assertEquals(List.of(new ReplayIssueHeaderFilterOption("RPT20260830-100000-0001", 2)), batches.items());
    }

    @Test
    void headerFilterOptionCountsSplitPeopleWithoutDuplicateIssues() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-1", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-2", "second"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-3", "third")), IMPORTED_AT);
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)", List.of(
                new Object[]{"公共组", "T-1", "交易1", "张三、李四", "王五、赵六", IMPORTED_AT},
                new Object[]{"公共组", "T-2", "交易2", "张三", "王五", IMPORTED_AT},
                new Object[]{"公共组", "T-3", "交易3", " ", "", IMPORTED_AT}));

        ReplayIssueHeaderFilterOptionResult developers = dao.headerFilterOptionCounts("developer", ALL, null);
        assertEquals(3L, developers.matchedIssueCount());
        assertEquals(List.of(
                new ReplayIssueHeaderFilterOption("空", 1),
                new ReplayIssueHeaderFilterOption("张三", 2),
                new ReplayIssueHeaderFilterOption("李四", 1)), developers.items());

        ReplayIssueHeaderFilterOptionResult searched = dao.headerFilterOptionCounts("developer", ALL, "李");
        assertEquals(1L, searched.matchedIssueCount());
        assertEquals(List.of(new ReplayIssueHeaderFilterOption("李四", 1)), searched.items());

        ReplayIssueHeaderFilterOptionResult bankOwners = dao.headerFilterOptionCounts("bankOwner", ALL, null);
        assertEquals(List.of(
                new ReplayIssueHeaderFilterOption("空", 1),
                new ReplayIssueHeaderFilterOption("王五", 2),
                new ReplayIssueHeaderFilterOption("赵六", 1)), bankOwners.items());
    }

    private static ReplayIssueQuery queryWithLongTextHeaderFilters(
            List<String> transactionNames, List<String> fieldNames,
            List<String> issueDescriptions, List<String> issueKeys) {
        return new ReplayIssueQuery(50, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                null, List.of(), null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                transactionNames, fieldNames, issueDescriptions, issueKeys);
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
                List.of(new Object[]{"RPT20260820-142055-0001"}, new Object[]{"TASK-B"},
                        new Object[]{"RPT20260820-142055-0002"}));

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
        assertIterableEquals(List.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "参数问题", "平台问题", "规则差异问题", "合理差异", "规则性差异问题", "外围问题", "其他问题"), options.issueTypes());
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
                ReplayIssueTestFixtures.row("贷款组", false, 6, "L-FIXED", "fixed"),
                ReplayIssueTestFixtures.row("贷款组", false, 7, "L-FIXED-2", "fixed-2")), IMPORTED_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='延后修复' WHERE transaction_code='L-COMBO-2'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复' WHERE transaction_code IN ('L-FIXED','L-FIXED-2')");
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                List.of(
                        new Object[] {"存款组", "D-1", "存款交易", "王五(c-wangw5)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-COMBO-1", "组合交易一", "张三(c-zhangs3)、李四(c-lisi)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-COMBO-2", "组合交易二", "张三(c-zhangs3)、李四(c-lisi)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-SINGLE", "单人交易", "赵六(c-zhaol6)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-FIXED", "已修复交易", "赵六(c-zhaol6)", IMPORTED_AT},
                        new Object[] {"贷款组", "L-FIXED-2", "已修复交易二", "赵六(c-zhaol6)", IMPORTED_AT}));

        List<ReplayIssuePersonRanking> rankings = dao.personIssueRankings();

        assertEquals(4, rankings.size());
        assertEquals(1, rankings.stream().filter(row -> row.groupName().equals("存款组")).mapToLong(ReplayIssuePersonRanking::totalCount).sum());
        assertEquals(6, rankings.stream().filter(row -> row.groupName().equals("贷款组")).mapToLong(ReplayIssuePersonRanking::totalCount).sum());
        assertEquals(3, rankings.stream().filter(row -> row.developer().equals("赵六(c-zhaol6)")).findFirst().orElseThrow().totalCount());
        assertEquals(2, rankings.stream().filter(row -> row.developer().equals("张三(c-zhangs3)、李四(c-lisi)")).findFirst().orElseThrow().totalCount());
        List<ReplayIssuePersonRanking> loanRankings = rankings.stream()
                .filter(row -> row.groupName().equals("贷款组"))
                .toList();
        assertEquals(List.of("张三(c-zhangs3)、李四(c-lisi)", "赵六(c-zhaol6)", "未匹配负责人"),
                loanRankings.stream().map(ReplayIssuePersonRanking::developer).toList());
        assertEquals(List.of(2L, 1L, 1L),
                loanRankings.stream().map(ReplayIssuePersonRanking::pendingTotalCount).toList());
        assertEquals(List.of(2L, 3L, 1L),
                loanRankings.stream().map(ReplayIssuePersonRanking::totalCount).toList());
        assertEquals(List.of(1, 2, 3),
                loanRankings.stream().map(ReplayIssuePersonRanking::rank).toList());
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
        assertEquals("20260804-001、RPT20260820-142055-0001", listed.get("occurrence_rounds"));
        assertEquals(1, dao.list(queryForRound("20260804-001")).size());
        assertEquals(0, dao.list(queryForRound("20260805-001")).size());
        assertTrue(dao.coverageRounds().containsAll(List.of("20260804-001", "RPT20260820-142055-0001")));
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

    private void insertOccurrenceBatch(String transactionCode, String batchName) {
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,?,?,?,?,? FROM dii_replay_issue WHERE transaction_code=?",
                batchName, IMPORTED_AT, IMPORTED_AT, IMPORTED_AT, IMPORTED_AT, transactionCode);
    }

    private static Set<String> transactionCodes(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("transaction_code")))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static ReplayIssueQuery withReplayType(ReplayIssueQuery query, ReplayIssueReplayType replayType) {
        return new ReplayIssueQuery(query.limit(), query.offset(), query.groupName(), query.sandbox(),
                query.issueLevel(), query.issueType(), query.keyword(), query.issueStatus(), query.developer(),
                query.bankOwner(), query.cooperationPerson(), query.serialNo(), query.globalSerialNo(),
                query.defectRepairDate(), query.coverageRound(), query.transactionCodes(), query.issueLevels(),
                query.developers(), query.bankOwners(), query.issueStatuses(), query.issueTypes(),
                query.cooperationPersons(), query.occurrenceBatches(), query.weeklyTask(), query.reviewStatus(),
                query.reviewStatuses(), query.issueId(), query.groupNames(), query.sandboxes(),
                query.plannedCompletionDates(), query.issueIds(), query.serialNos(), query.globalSerialNos(),
                query.defectRepairDates(), query.transactionNames(), query.fieldNames(), query.issueDescriptions(),
                query.issueKeys(), query.issueDomains(), replayType);
    }

    private void assertSummaryTotal(long expected, ReplayIssueReplayType replayType) {
        assertEquals(expected, ((Number) dao.stats("issueDomain", replayType).get("total")).longValue());
        assertEquals(expected, dao.groupIssueSummaries("issueDomain", replayType).stream()
                .mapToLong(ReplayIssueGroupSummary::totalCount).sum());
        assertEquals(expected, dao.personIssueRankings("issueDomain", replayType).stream()
                .mapToLong(ReplayIssuePersonRanking::totalCount).sum());
    }
}
