package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTablePage;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonGenerationException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonConditionCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonVersionDaoTest {

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonVersionDao dao;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"),
                new ClassPathResource("db/daoindex/V70__replay_db_compare_scope.sql"),
                new ClassPathResource("db/daoindex/V71__replay_db_compare_ordering_primary_key_snapshot.sql"),
                new ClassPathResource("db/daoindex/V73__replay_db_compare_partition_num.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayDatabaseComparisonVersionDao(jdbc);
        now = LocalDateTime.of(2026, 9, 14, 15, 30, 0);
    }

    @Test
    void persistsAndQueriesImmutableVersionHistoryNewestFirst() {
        long firstId = dao.insertVersion(
                "20260914-120000", "a".repeat(64), 1, 2,
                "100", "张三", now.minusHours(1));
        ReplayDbCompareRegistration account = registration(
                7L, 3L, "acct_master", "账户主表", "存款组", "100", "张三",
                List.of(field("acct_no", "账号", 1, true, 1),
                        field("customer_no", "客户号", 2, false, 2)));
        long tableId = dao.insertVersionTable(firstId, account.withPartitionNum(16), "li-manager");
        dao.insertVersionFields(tableId, account.fields());
        dao.insertVersion(
                "20260914-130000", "b".repeat(64), 0, 0,
                "101", "李四", now);

        ReplayDatabaseComparisonVersionDao.StoredVersion latest = dao.findLatestStoredVersion();
        ReplayDbCompareVersionPage versions = dao.findVersions(0, 20);
        ReplayDbCompareVersionTablePage snapshot = dao.searchVersion(
                "20260914-120000",
                new ReplayDbCompareVersionQuery(
                        0, 50, "acct", "customer", List.of("存款组"),
                        List.of("100"), List.of("200"),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));

        assertEquals("20260914-130000", latest.versionNo());
        assertEquals("b".repeat(64), latest.configurationHash());
        assertEquals(List.of("20260914-130000", "20260914-120000"),
                versions.items().stream().map(item -> item.versionNo()).toList());
        assertTrue(versions.items().get(0).latest());
        assertFalse(versions.items().get(1).latest());
        assertEquals(1, snapshot.total());
        assertEquals(7L, snapshot.items().get(0).sourceRegistrationId());
        assertEquals(16, snapshot.items().get(0).partitionNum());
        assertEquals(16, dao.findCompleteSnapshot(firstId).get(0).partitionNum());
        assertEquals("li-manager", snapshot.items().get(0).groupOwnerUsername());
        assertEquals(List.of("acct_no", "customer_no"), snapshot.items().get(0).fields().stream()
                .map(item -> item.columnName()).toList());
        assertTrue(snapshot.items().get(0).fields().get(0).primaryKey());
    }

    @Test
    void returnsSnapshotHeaderOptionsWithoutBaseMetadata() {
        long versionId = dao.insertVersion(
                "20260914-120000", "a".repeat(64), 1, 1,
                "100", "张三", now);
        ReplayDbCompareRegistration account = registration(
                7L, 3L, "acct_master", "账户主表", "存款组", "100", "张三",
                List.of(field("acct_no", "账号", 1, true, 1)));
        long tableId = dao.insertVersionTable(versionId, account);
        dao.insertVersionFields(tableId, account.fields());

        var fieldOptions = dao.versionHeaderFilterOptions(
                "20260914-120000",
                new ReplayDbCompareHeaderFilterRequest(
                        "fieldName", "账号", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));
        var tableOptions = dao.versionHeaderFilterOptions(
                "20260914-120000",
                new ReplayDbCompareHeaderFilterRequest(
                        "tableName", "账户主", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));
        var reviserOptions = dao.versionHeaderFilterOptions(
                "20260914-120000",
                new ReplayDbCompareHeaderFilterRequest(
                        "reviser", "zhangsan", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));

        assertEquals(1, fieldOptions.options().size());
        assertEquals("acct_no", fieldOptions.options().get(0).value());
        assertEquals("acct_no(账号)", fieldOptions.options().get(0).label());
        assertEquals(1, fieldOptions.matchedRegistrationCount());
        assertEquals("acct_master(账户主表)", tableOptions.options().get(0).label());
        assertEquals("张三(zhangsan)", reviserOptions.options().get(0).label());
    }

    @Test
    void supportsExactMultiSelectAndExplicitEmptyPeopleInVersionHistory() {
        long versionId = dao.insertVersion(
                "20260914-121500", "e".repeat(64), 2, 2,
                "100", "张三", now);
        ReplayDbCompareRegistration account = registration(
                7L, 3L, "acct_master", "账户主表", "存款组", "100", "张三",
                List.of(field("acct_no", "账号", 1, true, 1)));
        ReplayDbCompareRegistration customer = new ReplayDbCompareRegistration(
                8L, "base_schema", "customer_master", "客户主表", "存款组",
                null, null, null, null, null,
                LocalDate.of(2026, 9, 15), false, null, null, null, 1L,
                "100", "张三", now, "100", "张三", now,
                List.of(field("customer_no", "客户号", 1, true, 1)));
        long accountTableId = dao.insertVersionTable(versionId, account, "li-manager");
        long customerTableId = dao.insertVersionTable(versionId, customer, null);
        dao.insertVersionFields(accountTableId, account.fields());
        dao.insertVersionFields(customerTableId, customer.fields());

        ReplayDbCompareVersionTablePage selected = dao.searchVersion(
                "20260914-121500",
                new ReplayDbCompareVersionQuery(
                        0, 50, null, null, List.of(), List.of("__EMPTY__"),
                        List.of("__EMPTY__"), null, null,
                        List.of("acct_master", "customer_master"),
                        List.of("customer_no"), List.of(LocalDate.of(2026, 9, 15))));
        var reviserOptions = dao.versionHeaderFilterOptions(
                "20260914-121500",
                new ReplayDbCompareHeaderFilterRequest(
                        "reviser", "", 20, null, null,
                        List.of(), List.of(), List.of(), null, null,
                        List.of(), List.of(), List.of()));
        var ownerOptions = dao.versionHeaderFilterOptions(
                "20260914-121500",
                new ReplayDbCompareHeaderFilterRequest(
                        "groupOwner", "", 20, null, null,
                        List.of(), List.of(), List.of(), null, null,
                        List.of(), List.of(), List.of()));

        assertEquals(List.of("customer_master"), selected.items().stream()
                .map(item -> item.tableName()).toList());
        assertTrue(reviserOptions.options().stream().anyMatch(option ->
                option.value().equals("__EMPTY__") && option.label().equals("空")));
        assertTrue(ownerOptions.options().stream().anyMatch(option ->
                option.value().equals("__EMPTY__") && option.label().equals("空")));
    }

    @Test
    void filtersAndGroupsVersionQueryConditionsIncludingFullTable() {
        long versionId = dao.insertVersion(
                "20260914-121700", "g".repeat(64), 2, 2,
                "100", "张三", now);
        ReplayDbCompareRegistration fullTable = registration(
                7L, 3L, "acct_full", "全表账户", "存款组", "100", "张三",
                List.of(field("acct_no", "账号", 1, true, 1)));
        ReplayDbCompareConditionTree condition = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(
                                "status_cd", ReplayDbCompareConditionOperator.EQ, List.of("1"))))));
        ReplayDbCompareRegistration scoped = new ReplayDbCompareRegistration(
                8L, "base_schema", "acct_scoped", "条件账户", "存款组",
                "101", "lisi", "李四", "200", "李经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 3L,
                "100", "张三", now, "100", "张三", now,
                List.of(field("acct_no", "账号", 1, true, 1)), condition, null, null);
        dao.insertVersionTable(versionId, fullTable);
        dao.insertVersionTable(versionId, scoped);
        String conditionKey = new ReplayDatabaseComparisonConditionCodec().encode(condition);

        ReplayDbCompareVersionTablePage selected = dao.searchVersion(
                "20260914-121700",
                new ReplayDbCompareVersionQuery(
                        0, 50, null, null, List.of(), List.of(), List.of(), null, null,
                        List.of(), List.of(), List.of(), List.of("__FULL_TABLE__", conditionKey)));
        assertEquals(List.of("acct_full", "acct_scoped"), selected.items().stream()
                .map(item -> item.tableName()).toList());

        var options = dao.versionHeaderFilterOptions(
                "20260914-121700",
                new ReplayDbCompareHeaderFilterRequest(
                        "whereCondition", "ignored-by-dao", 20, null, null,
                        List.of(), List.of(), List.of(), null, null,
                        List.of(), List.of(), List.of(), List.of()));
        assertEquals(List.of("__FULL_TABLE__", conditionKey), options.options().stream()
                .map(option -> option.value()).sorted().toList());
    }

    @Test
    void keepsUsernameOnlyRevisersDistinctInVersionHistory() {
        long versionId = dao.insertVersion(
                "20260914-122000", "f".repeat(64), 2, 2,
                "100", "张三", now);
        ReplayDbCompareRegistration first = new ReplayDbCompareRegistration(
                7L, "base_schema", "acct_a", "账户甲", "存款组",
                null, "c-zhangs", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 1L,
                "100", "张三", now, "100", "张三", now,
                List.of(field("acct_no", "账号", 1, true, 1)));
        ReplayDbCompareRegistration second = new ReplayDbCompareRegistration(
                8L, "base_schema", "acct_b", "账户乙", "存款组",
                null, "c-lisi", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 15), false, null, null, null, 1L,
                "100", "张三", now, "100", "张三", now,
                List.of(field("customer_no", "客户号", 1, true, 1)));
        dao.insertVersionTable(versionId, first);
        dao.insertVersionTable(versionId, second);

        var options = dao.versionHeaderFilterOptions(
                "20260914-122000",
                new ReplayDbCompareHeaderFilterRequest(
                        "reviser", "", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));

        assertEquals(List.of("c-lisi", "c-zhangs"), options.options().stream()
                .map(option -> option.value()).sorted().toList());
        ReplayDbCompareVersionTablePage selected = dao.searchVersion(
                "20260914-122000",
                new ReplayDbCompareVersionQuery(
                        0, 50, null, null, List.of(), List.of("c-zhangs"),
                        List.of(), null, null));
        assertEquals(List.of("acct_a"), selected.items().stream()
                .map(item -> item.tableName()).toList());
    }

    @Test
    void reportsMissingVersionAndCapsHistoryPageSize() {
        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> dao.searchVersion("missing", ReplayDbCompareVersionQuery.empty(0, 50)));

        assertEquals("VERSION_NOT_FOUND", error.errorCode());
        assertEquals(100, dao.findVersions(0, 1000).size());
    }

    @Test
    void readsEveryTableAndFieldFromAnImmutableVersionWithoutPagination() {
        long versionId = dao.insertVersion(
                "20260914-172637", "c".repeat(64), 3, 4,
                "100", "张三", now);
        ReplayDbCompareRegistration zTable = registration(
                7L, 1L, "z_table", "Z表", "存款组", "100", "张三",
                List.of(field("normal_field", "普通字段", 2, false, 2),
                        field("pk_field", "主键字段", 1, true, 1)));
        ReplayDbCompareRegistration loanTable = registration(
                8L, 2L, "loan_table", "贷款表", "贷款组", "101", "李四",
                List.of(field("loan_id", "贷款编号", 1, true, 1)));
        ReplayDbCompareRegistration aTable = registration(
                9L, 3L, "a_table", "A表", "存款组", "102", "王五",
                List.of(field("account_id", "账户编号", 1, true, 1)));
        for (ReplayDbCompareRegistration registration : List.of(zTable, loanTable, aTable)) {
            long tableId = dao.insertVersionTable(versionId, registration);
            dao.insertVersionFields(tableId, registration.fields());
        }

        ReplayDatabaseComparisonVersionDao.StoredVersion stored =
                dao.findStoredVersion("20260914-172637");
        List<com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTableItem> snapshot =
                dao.findCompleteSnapshot(stored.id());

        assertEquals(versionId, stored.id());
        assertEquals(List.of("a_table", "z_table"), snapshot.stream()
                .filter(row -> row.domainName().equals("存款组"))
                .map(row -> row.tableName()).toList());
        assertEquals(List.of(1, 2), snapshot.stream()
                .filter(row -> row.tableName().equals("z_table"))
                .findFirst().orElseThrow().fields().stream()
                .map(field -> field.comparisonOrder()).toList());
        assertTrue(snapshot.stream()
                .filter(row -> row.tableName().equals("z_table"))
                .findFirst().orElseThrow().fields().get(0).primaryKey());
    }

    @Test
    void returnsNullForAnUnknownStoredVersionAndEmptySnapshotForAnEmptyVersion() {
        long versionId = dao.insertVersion(
                "20260914-180000", "d".repeat(64), 0, 0,
                "100", "张三", now);

        assertNull(dao.findStoredVersion("missing"));
        assertTrue(dao.findCompleteSnapshot(versionId).isEmpty());
    }

    @Test
    void roundTripsImmutableScopeAndPrimaryKeyOrder() {
        long versionId = dao.insertVersion(
                "20260914-190000", "e".repeat(64), 1, 2,
                "100", "张三", now);
        ReplayDbCompareConditionTree condition = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(
                                "status", ReplayDbCompareConditionOperator.EQ, List.of("1"))))));
        ReplayDbCompareRegistration registration = new ReplayDbCompareRegistration(
                7L, "base_schema", "acct_master", "账户主表", "存款组",
                "100", "zhangsan", "张三", "200", "李经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 3,
                "100", "张三", now, "100", "张三", now,
                List.of(
                        new ReplayDbCompareField("a", "主键A", 1, true, 1, 2, null),
                        new ReplayDbCompareField("b", "主键B", 2, true, 2, 1, null)),
                condition, 1000L, "(status = '1')", null);

        long tableId = dao.insertVersionTable(versionId, registration);
        dao.insertVersionFields(tableId, registration.fields());
        var snapshot = dao.findCompleteSnapshot(versionId).get(0);

        assertEquals("(status = '1')", snapshot.whereSql());
        assertEquals(1000L, snapshot.compareLimit());
        assertEquals(ReplayDbCompareConditionOperator.EQ,
                snapshot.whereCondition().groups().get(0).conditions().get(0).operator());
        assertEquals(List.of(2, 1), snapshot.fields().stream()
                .map(item -> item.primaryKeyOrder()).toList());
    }

    private ReplayDbCompareRegistration registration(
            long id,
            long version,
            String tableName,
            String tableComment,
            String domain,
            String reviserEmpNo,
            String reviserName,
            List<ReplayDbCompareField> fields) {
        return new ReplayDbCompareRegistration(
                id, "base_schema", tableName, tableComment, domain,
                reviserEmpNo, "zhangsan", reviserName, "200", "李经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, version,
                "100", "张三", now, "100", "张三", now, fields);
    }

    private ReplayDbCompareField field(
            String name,
            String comment,
            int ordinal,
            boolean primaryKey,
            int comparisonOrder) {
        return new ReplayDbCompareField(name, comment, ordinal, primaryKey, comparisonOrder);
    }
}
