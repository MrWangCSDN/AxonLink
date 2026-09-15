package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseValidatedTable;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditOperation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditGroupPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareDeleteRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListItem;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareMetadataStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbComparePrimaryKeySyncResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareReregisterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSaveRequest;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayDatabaseComparisonServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-12T06:36:08Z"), ZoneId.of("Asia/Shanghai"));

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonDao dao;
    private ReplayDatabaseComparisonVersionDao versionDao;
    private ReplayBaseMetadataService metadataService;
    private ReplayDatabaseComparisonService service;

    @Test
    void assemblesPagedAuditGroupsWithLatestOperator() {
        createWithMetadata("acct_master", List.of("acct_no"), List.of("acct_no"));

        ReplayDbCompareAuditGroupPage page = service.searchGroupedAudits(
                ReplayDbCompareAuditQuery.empty(0, 20));

        assertEquals(1, page.totalElements());
        assertEquals(1, page.totalPages());
        assertEquals("acct_master", page.content().get(0).tableName());
        assertEquals("creator", page.content().get(0).latestOperatorUsername());
        assertEquals("创建人", page.content().get(0).latestOperatorName());
        assertEquals(1, page.content().get(0).events().size());
    }

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"))
                .execute(jdbc.getDataSource());
        createUsers(jdbc);
        dao = spy(new ReplayDatabaseComparisonDao(jdbc));
        versionDao = new ReplayDatabaseComparisonVersionDao(jdbc);
        metadataService = mock(ReplayBaseMetadataService.class);
        when(metadataService.requireTableWithColumns(anyString(), any(Collection.class)))
                .thenAnswer(invocation -> {
                    Collection<String> fields = invocation.getArgument(1);
                    if (fields.isEmpty()) {
                        throw new IllegalArgumentException("至少选择一个比对字段");
                    }
                    return validated(invocation.getArgument(0), fields);
                });
        service = new ReplayDatabaseComparisonService(
                metadataService, dao, versionDao, new SysUserDao(jdbc),
                new ReplayDatabaseComparisonAuditDiff(), jdbc, CLOCK);
    }

    @Test
    void unrelatedLoggedInUserCanEditAndTrustedMetadataWins() {
        ReplayDbCompareRegistration created = service.create(
                save("ACCT_MASTER", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "伪造姓名"));

        ReplayDbCompareRegistration updated = service.update(
                created.id(),
                save("acct_master", "公共组", "102", List.of("customer_no", "acct_no"), created.version()),
                new ReplayIssueOperator("editor", "另一个伪造姓名"));

        assertEquals("母库账户主表", updated.tableComment());
        assertEquals("200", updated.reviserEmpNo());
        assertEquals("editor", updated.reviserUsername());
        assertEquals("编辑人", updated.reviserName());
        assertEquals("102", updated.groupOwnerEmpNo());
        assertEquals("钱经理", updated.groupOwnerName());
        assertEquals(List.of("customer_no", "acct_no"),
                updated.fields().stream().map(field -> field.columnName()).toList());
        assertEquals(2, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void authenticatedUserWithoutEmployeeNumberCanCreateRegistration() {
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES (?,?,?,?)",
                "login-user", "登录用户", null, 1);

        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("login-user", "登录用户"));

        assertEquals("login-user", created.reviserEmpNo());
        assertEquals("login-user", created.reviserUsername());
        assertEquals("登录用户", created.reviserName());
    }

    @Test
    void groupOwnerSelectedByUsernameDoesNotRequireEmployeeNumber() {
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES (?,?,?,?)",
                "owner-user", "小组负责人", null, 1);

        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "owner-user", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));

        assertEquals("owner-user", created.groupOwnerEmpNo());
        assertEquals("小组负责人", created.groupOwnerName());
    }

    @Test
    void noOpSaveDoesNotChangeVersionReviserOrAudit() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        assertEquals("creator", created.reviserUsername());

        ReplayDbCompareRegistration unchanged = service.update(
                created.id(),
                save("ACCT_MASTER", " 存款组 ", "101", List.of("ACCT_NO"), created.version()),
                new ReplayIssueOperator("editor", "编辑人"));

        assertEquals(created.version(), unchanged.version());
        assertEquals("创建人", unchanged.reviserName());
        assertEquals(1, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void updateRejectsEveryMissingCurrentPrimaryKeyWithoutWriting() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        List<ReplayBaseColumnOption> selected = List.of(
                new ReplayBaseColumnOption("a", "主键A", 1, true, 1),
                new ReplayBaseColumnOption("c", "主键C", 3, true, 3),
                new ReplayBaseColumnOption("d", "普通字段D", 4, false, null));
        List<ReplayBaseColumnOption> currentPrimaryKeys = List.of(
                new ReplayBaseColumnOption("a", "主键A", 1, true, 1),
                new ReplayBaseColumnOption("b", "主键B", 2, true, 2),
                new ReplayBaseColumnOption("c", "主键C", 3, true, 3),
                new ReplayBaseColumnOption("f", "主键F", 6, true, 4));
        when(metadataService.requireTableWithColumns("acct_master", List.of("a", "c", "d")))
                .thenReturn(new ReplayBaseValidatedTable(
                        "base_schema", "acct_master", "母库账户主表",
                        selected, currentPrimaryKeys));

        ReplayBasePrimaryKeysRequiredException error = assertThrows(
                ReplayBasePrimaryKeysRequiredException.class,
                () -> service.update(created.id(),
                        save("acct_master", "存款组", "101", List.of("a", "c", "d"), created.version()),
                        new ReplayIssueOperator("editor", "编辑人")));

        assertEquals("acct_master", error.tableName());
        assertEquals(List.of("b", "f"), error.missingPrimaryKeyNames());
        ReplayDbCompareRegistration unchanged = dao.findByIdIncludingDeleted(created.id());
        assertEquals(created.version(), unchanged.version());
        assertEquals(List.of("acct_no"), unchanged.fields().stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertEquals(1, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void createRejectsTableWithoutPrimaryKeyWithoutWriting() {
        List<ReplayBaseColumnOption> columns = List.of(
                new ReplayBaseColumnOption("customer_no", "客户号", 1, false, null),
                new ReplayBaseColumnOption("status", "状态", 2, false, null));
        when(metadataService.requireTableWithColumns(
                "no_primary_key", List.of("customer_no", "status")))
                .thenReturn(new ReplayBaseValidatedTable(
                        "base_schema", "no_primary_key", "无主键表", columns, List.of()));

        ReplayBasePrimaryKeyMissingException error = assertThrows(
                ReplayBasePrimaryKeyMissingException.class,
                () -> service.create(
                        save("no_primary_key", "存款组", "101",
                                List.of("customer_no", "status"), null),
                        new ReplayIssueOperator("creator", "创建人")));

        assertEquals("no_primary_key", error.tableName());
        assertEquals("该表没有主键，请联系 DBA 创建表主键", error.getMessage());
        assertEquals(0, dao.search(ReplayDbCompareQuery.empty(0, 50)).total());
        assertEquals(0, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void updateRemovingAllFieldsRejectsTableWithoutPrimaryKeyWithoutWriting() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        List<ReplayBaseColumnOption> columns = List.of(
                new ReplayBaseColumnOption("acct_no", "账号", 1, false, null));
        doReturn(new ReplayBaseValidatedTable(
                "base_schema", "acct_master", "母库账户主表", columns, List.of()))
                .when(metadataService).requireTableWithColumns("acct_master", List.of());

        ReplayBasePrimaryKeyMissingException error = assertThrows(
                ReplayBasePrimaryKeyMissingException.class,
                () -> service.update(
                        created.id(),
                        new ReplayDbCompareSaveRequest(
                                "acct_master", "存款组", "101", List.of(), created.version(), true),
                        new ReplayIssueOperator("editor", "编辑人")));

        assertEquals("acct_master", error.tableName());
        assertEquals("该表没有主键，请联系 DBA 创建表主键", error.getMessage());
        ReplayDbCompareRegistration unchanged = dao.findByIdIncludingDeleted(created.id());
        assertFalse(unchanged.deleted());
        assertEquals(created.version(), unchanged.version());
        assertEquals(List.of("acct_no"), unchanged.fields().stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertEquals(1, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void independentDeleteRemainsAvailableWhenTableHasNoPrimaryKey() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        ReplayDbCompareRegistration deleted = service.delete(
                created.id(), new ReplayDbCompareDeleteRequest(created.version(), "删除登记"),
                new ReplayIssueOperator("editor", "编辑人"));

        assertTrue(deleted.deleted());
        assertTrue(deleted.fields().isEmpty());
        verify(metadataService, never()).requireTableWithColumns("acct_master", List.of());
    }

    @Test
    void deleteAndReregisterReusePermanentIdWithoutRestoringOldFields() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no", "customer_no"), null),
                new ReplayIssueOperator("creator", "创建人"));

        service.delete(created.id(), new ReplayDbCompareDeleteRequest(created.version(), "停止比对"),
                new ReplayIssueOperator("editor", "编辑人"));
        ReplayDbCompareRegistration deleted = dao.findByIdIncludingDeleted(created.id());
        assertTrue(deleted.deleted());
        assertTrue(deleted.fields().isEmpty());
        assertEquals("editor", deleted.reviserUsername());

        ReplayDbCompareRegistration restored = service.reregister(
                created.id(),
                new ReplayDbCompareReregisterRequest(
                        deleted.version(), "平台组", "102", List.of("status"), "重新启用"),
                new ReplayIssueOperator("other", "其他人"));

        assertEquals(created.id(), restored.id());
        assertFalse(restored.deleted());
        assertEquals(List.of("status"), restored.fields().stream().map(field -> field.columnName()).toList());
        assertEquals("other", restored.reviserUsername());
        assertEquals("其他人", restored.reviserName());
        assertEquals(3, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void cleanupDeletionAuditRemainsQueryableAfterSameTableIsReregistered() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no", "customer_no"), null),
                new ReplayIssueOperator("creator", "创建人"));

        service.delete(created.id(),
                new ReplayDbCompareDeleteRequest(created.version(), "母库表已删除，清理登记"),
                new ReplayIssueOperator("editor", "编辑人"));
        long deleteEventId = dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).items().stream()
                .filter(event -> event.operation() == ReplayDbCompareAuditOperation.DELETE)
                .findFirst()
                .orElseThrow()
                .id();

        ReplayDbCompareRegistration deleted = dao.findByIdIncludingDeleted(created.id());
        service.reregister(created.id(),
                new ReplayDbCompareReregisterRequest(
                        deleted.version(), "存款组", "101", List.of("status"), "重新登记"),
                new ReplayIssueOperator("other", "其他人"));

        assertEquals("母库表已删除，清理登记",
                dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).items().stream()
                        .filter(event -> event.id() == deleteEventId)
                        .findFirst()
                        .orElseThrow()
                        .reason());
        assertEquals(List.of("comparisonFields.acct_no", "comparisonFields.customer_no", "deleted"),
                dao.findAuditDetails(deleteEventId).stream()
                        .map(detail -> detail.fieldCode())
                        .sorted()
                        .toList());
    }

    @Test
    void updateWritesNothingWhenBaseTableDisappearsBeforeSave() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.requireTableWithColumns(anyString(), any(Collection.class)))
                .thenThrow(new ReplayBaseTableNotFoundException("acct_master"));

        assertThrows(ReplayBaseTableNotFoundException.class, () -> service.update(
                created.id(),
                save("acct_master", "公共组", "102", List.of("customer_no"), created.version()),
                new ReplayIssueOperator("editor", "编辑人")));

        ReplayDbCompareRegistration unchanged = dao.findByIdIncludingDeleted(created.id());
        assertEquals(created.version(), unchanged.version());
        assertEquals("存款组", unchanged.domainName());
        assertEquals(List.of("acct_no"), unchanged.fields().stream()
                .map(ReplayDbCompareField::columnName)
                .toList());
        assertEquals(1, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void rejectsBlankOrUnknownOperatorAndStaleVersionWithoutWriting() {
        assertThrows(IllegalStateException.class, () -> service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator(" ", "匿名")));
        assertNull(dao.findBySchemaAndTable("base_schema", "acct_master"));

        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        assertThrows(ReplayDatabaseComparisonVersionConflictException.class, () -> service.update(
                created.id(), save("acct_master", "公共组", "101", List.of("acct_no"), 99L),
                new ReplayIssueOperator("editor", "编辑人")));
        assertEquals(1, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void auditDetailFailureRollsBackRegistrationFieldsAndEvent() {
        ReplayDatabaseComparisonDao failingDao = spy(new ReplayDatabaseComparisonDao(jdbc));
        doThrow(new IllegalStateException("detail insert failed"))
                .when(failingDao).insertAuditDetails(any(Long.class), any(List.class), any());
        ReplayDatabaseComparisonService failingService = new ReplayDatabaseComparisonService(
                metadataService, failingDao, versionDao, new SysUserDao(jdbc),
                new ReplayDatabaseComparisonAuditDiff(), jdbc, CLOCK);

        assertThrows(IllegalStateException.class, () -> failingService.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人")));

        assertNull(dao.findBySchemaAndTable("base_schema", "acct_master"));
        assertEquals(0, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void generationInProgressDoesNotBlockPublicRegistrationWrites() {
        ReplayDbCompareRegistration active = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        ReplayDbCompareRegistration removed = service.create(
                save("loan_master", "贷款组", "101", List.of("loan_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        removed = service.delete(removed.id(),
                new ReplayDbCompareDeleteRequest(removed.version(), "清理"),
                new ReplayIssueOperator("creator", "创建人"));
        assertDoesNotThrow(() -> service.create(
                save("customer_master", "公共组", "101", List.of("customer_no"), null),
                new ReplayIssueOperator("editor", "编辑人")));
        ReplayDbCompareRegistration updated = assertDoesNotThrow(() -> service.update(active.id(),
                save("acct_master", "公共组", "101", List.of("acct_no"), active.version()),
                new ReplayIssueOperator("editor", "编辑人")));
        assertDoesNotThrow(() -> service.delete(updated.id(),
                new ReplayDbCompareDeleteRequest(updated.version(), "删除"),
                new ReplayIssueOperator("editor", "编辑人")));
        ReplayDbCompareRegistration deleted = removed;
        assertDoesNotThrow(() -> service.reregister(deleted.id(),
                new ReplayDbCompareReregisterRequest(
                        deleted.version(), "贷款组", "101", List.of("loan_no"), "重新登记"),
                new ReplayIssueOperator("editor", "编辑人")));
        when(metadataService.inspectTables(any())).thenReturn(Map.of());
        assertDoesNotThrow(() -> {
            service.synchronizePrimaryKeys();
        });
    }

    @Test
    void explicitlyRemovingAllFieldsDeletesWhenBaseTableStillHasPrimaryKey() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        ReplayBaseColumnOption primaryKey =
                new ReplayBaseColumnOption("acct_no", "账号", 1, true, 1);
        doReturn(new ReplayBaseValidatedTable(
                "base_schema", "acct_master", "母库账户主表",
                List.of(primaryKey), List.of(primaryKey)))
                .when(metadataService).requireTableWithColumns("acct_master", List.of());

        ReplayDbCompareRegistration deleted = service.update(
                created.id(),
                new ReplayDbCompareSaveRequest(
                        "acct_master", "存款组", "101", List.of(), created.version(), true),
                new ReplayIssueOperator("editor", "编辑人"));

        assertTrue(deleted.deleted());
        assertTrue(deleted.fields().isEmpty());
        assertEquals(2, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void searchMarksMissingFieldsAndTablesUsingOneBatchLookup() {
        service.create(save("acct_master", "存款组", "101", List.of("acct_no", "customer_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        service.create(save("valid_table", "公共组", "101", List.of("status"), null),
                new ReplayIssueOperator("creator", "创建人"));
        service.create(save("removed_table", "贷款组", "101", List.of("legacy_id"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTables(any(Collection.class))).thenReturn(Map.of(
                "acct_master", new ReplayBaseMetadataSnapshot(true, "acct_master", "账户主表",
                        List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true))),
                "valid_table", new ReplayBaseMetadataSnapshot(true, "valid_table", "有效表",
                        List.of(new ReplayBaseColumnOption("status", "状态", 1, false))),
                "removed_table", new ReplayBaseMetadataSnapshot(false, "removed_table", null, List.of())));

        ReplayDbCompareListPage page = service.search(ReplayDbCompareQuery.empty(0, 50));

        assertEquals(ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                item(page, "acct_master").metadataValidation().status());
        assertEquals(List.of("customer_no"),
                item(page, "acct_master").metadataValidation().missingFieldNames());
        assertEquals(ReplayDbCompareMetadataStatus.VALID,
                item(page, "valid_table").metadataValidation().status());
        assertEquals(ReplayDbCompareMetadataStatus.TABLE_MISSING,
                item(page, "removed_table").metadataValidation().status());
        verify(metadataService, times(1)).inspectTables(any(Collection.class));
        verify(metadataService, never()).inspectTable(anyString());
    }

    @Test
    void searchKeepsRowsAndMarksUnavailableWhenBaseCannotBeReached() {
        service.create(save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTables(any(Collection.class)))
                .thenThrow(new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用"));

        ReplayDbCompareListPage page = service.search(ReplayDbCompareQuery.empty(0, 50));

        assertEquals(1, page.items().size());
        assertEquals(ReplayDbCompareMetadataStatus.UNAVAILABLE,
                page.items().get(0).metadataValidation().status());
    }

    @Test
    void missingFieldStatusFiltersBeforePaginationWithAccurateTotal() {
        for (String tableName : List.of("drift_a", "drift_b", "drift_c", "valid_a", "valid_b")) {
            service.create(save(tableName, "存款组", "101", List.of("acct_no"), null),
                    new ReplayIssueOperator("creator", "创建人"));
        }
        when(metadataService.inspectTables(any(Collection.class))).thenAnswer(invocation -> {
            Collection<String> tableNames = invocation.getArgument(0);
            return tableNames.stream().collect(java.util.stream.Collectors.toMap(
                    name -> name,
                    name -> new ReplayBaseMetadataSnapshot(true, name, null,
                            name.startsWith("drift_")
                                    ? List.of()
                                    : List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true))),
                    (left, right) -> left,
                    java.util.LinkedHashMap::new));
        });

        ReplayDbCompareQuery firstQuery = new ReplayDbCompareQuery(
                null, null, List.of(), List.of(), List.of(), null, null,
                0, 2, List.of(ReplayDbCompareMetadataStatus.MISSING_FIELDS));
        ReplayDbCompareQuery secondQuery = new ReplayDbCompareQuery(
                null, null, List.of(), List.of(), List.of(), null, null,
                1, 2, List.of(ReplayDbCompareMetadataStatus.MISSING_FIELDS));

        ReplayDbCompareListPage first = service.search(firstQuery);
        ReplayDbCompareListPage second = service.search(secondQuery);

        assertEquals(3, first.total());
        assertEquals(List.of("drift_a", "drift_b"), first.items().stream()
                .map(ReplayDbCompareListItem::tableName).toList());
        assertEquals(3, second.total());
        assertEquals(List.of("drift_c"), second.items().stream()
                .map(ReplayDbCompareListItem::tableName).toList());
        verify(dao, never()).search(firstQuery);
        verify(dao, never()).search(secondQuery);
    }

    @Test
    void missingTableStatusSupportsStandaloneAndCombinedFiltering() {
        for (String tableName : List.of("drift_a", "removed_a", "valid_a")) {
            service.create(save(tableName, "存款组", "101", List.of("acct_no"), null),
                    new ReplayIssueOperator("creator", "创建人"));
        }
        when(metadataService.inspectTables(any(Collection.class))).thenReturn(Map.of(
                "drift_a", new ReplayBaseMetadataSnapshot(true, "drift_a", null, List.of()),
                "removed_a", new ReplayBaseMetadataSnapshot(false, "removed_a", null, List.of()),
                "valid_a", new ReplayBaseMetadataSnapshot(true, "valid_a", null,
                        List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true)))));

        ReplayDbCompareListPage missingTables = service.search(new ReplayDbCompareQuery(
                null, null, List.of(), List.of(), List.of(), null, null,
                0, 20, List.of(ReplayDbCompareMetadataStatus.TABLE_MISSING)));
        ReplayDbCompareListPage combined = service.search(new ReplayDbCompareQuery(
                null, null, List.of(), List.of(), List.of(), null, null,
                0, 20, List.of(
                        ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                        ReplayDbCompareMetadataStatus.TABLE_MISSING)));

        assertEquals(List.of("removed_a"), missingTables.items().stream()
                .map(ReplayDbCompareListItem::tableName).toList());
        assertEquals(List.of("drift_a", "removed_a"), combined.items().stream()
                .map(ReplayDbCompareListItem::tableName).toList());
        assertEquals(2, combined.total());
    }

    @Test
    void primaryKeyChangesAreNotExposedAsMetadataStatusOrHeaderOption() {
        service.create(save("drift_key", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        service.create(save("valid_key", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTables(any(Collection.class))).thenReturn(Map.of(
                "drift_key", new ReplayBaseMetadataSnapshot(true, "drift_key", null, List.of(
                        new ReplayBaseColumnOption("acct_no", "账号", 1, true, 1),
                        new ReplayBaseColumnOption("f", "新增联合主键", 6, true, 2))),
                "valid_key", new ReplayBaseMetadataSnapshot(true, "valid_key", null, List.of(
                        new ReplayBaseColumnOption("acct_no", "账号", 1, true, 1)))));

        ReplayDbCompareListPage normal = service.search(ReplayDbCompareQuery.empty(0, 20));
        var validation = item(normal, "drift_key").metadataValidation();
        assertEquals(ReplayDbCompareMetadataStatus.VALID, validation.status());
        assertFalse(validation.primaryKeyChanged());
        assertTrue(validation.missingPrimaryKeyNames().isEmpty());
        assertTrue(validation.formerPrimaryKeyNames().isEmpty());

        var options = service.headerFilterOptions(new ReplayDbCompareHeaderFilterRequest(
                "tableName", "主键", 20, null, null,
                List.of(), List.of(), List.of(), null, null));
        assertTrue(options.options().stream()
                .noneMatch(option -> "母库主键已变更".equals(option.value())));
    }

    @Test
    void detailReportsDeletedFieldWithoutExposingPrimaryKeyDrift() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTable("acct_master")).thenReturn(new ReplayBaseMetadataSnapshot(
                true, "acct_master", "账户主表", List.of()));

        var changed = service.detail(created.id()).metadataValidation();
        assertEquals(ReplayDbCompareMetadataStatus.MISSING_FIELDS, changed.status());
        assertEquals(List.of("acct_no"), changed.missingFieldNames());
        assertFalse(changed.primaryKeyChanged());
        assertTrue(changed.missingPrimaryKeyNames().isEmpty());
        assertTrue(changed.formerPrimaryKeyNames().isEmpty());

        when(metadataService.inspectTable("acct_master")).thenThrow(
                new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用"));
        var unavailable = service.detail(created.id()).metadataValidation();
        assertFalse(unavailable.primaryKeyChanged());
        assertTrue(unavailable.missingPrimaryKeyNames().isEmpty());
        assertTrue(unavailable.formerPrimaryKeyNames().isEmpty());
    }

    @Test
    void existingFormerCompositeKeyBecomesNormalWithoutActionableDrift() {
        List<ReplayBaseColumnOption> savedColumns = List.of(
                new ReplayBaseColumnOption("a", "主键A", 1, true, 1),
                new ReplayBaseColumnOption("b", "主键B", 2, true, 2),
                new ReplayBaseColumnOption("c", "普通字段C", 3, false, null),
                new ReplayBaseColumnOption("d", "普通字段D", 4, false, null));
        when(metadataService.requireTableWithColumns(
                "acct_master", List.of("a", "b", "c", "d")))
                .thenReturn(new ReplayBaseValidatedTable(
                        "base_schema", "acct_master", "母库账户主表", savedColumns,
                        savedColumns.stream().filter(ReplayBaseColumnOption::primaryKey).toList()));
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("a", "b", "c", "d"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTable("acct_master")).thenReturn(new ReplayBaseMetadataSnapshot(
                true, "acct_master", "母库账户主表", List.of(
                        new ReplayBaseColumnOption("a", "主键A", 1, true, 1),
                        new ReplayBaseColumnOption("b", "主键B", 2, false, null),
                        new ReplayBaseColumnOption("c", "普通字段C", 3, false, null),
                        new ReplayBaseColumnOption("d", "普通字段D", 4, false, null))));

        ReplayDbCompareRegistration detail = service.detail(created.id());

        assertFalse(detail.metadataValidation().primaryKeyChanged());
        assertTrue(detail.metadataValidation().missingPrimaryKeyNames().isEmpty());
        assertTrue(detail.metadataValidation().formerPrimaryKeyNames().isEmpty());
        assertEquals(List.of("a", "b", "c", "d"), detail.fields().stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertTrue(detail.fields().get(0).primaryKey());
        assertFalse(detail.fields().get(1).primaryKey());
        assertEquals(created.version(), detail.version());
        assertEquals(1, dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).total());
    }

    @Test
    void existingSelectedFieldPromotedToPrimaryDoesNotRequireSave() {
        List<ReplayBaseColumnOption> savedColumns = List.of(
                new ReplayBaseColumnOption("a", "主键A", 1, true, 1),
                new ReplayBaseColumnOption("f", "普通字段F", 2, false, null));
        when(metadataService.requireTableWithColumns("acct_master", List.of("a", "f")))
                .thenReturn(new ReplayBaseValidatedTable(
                        "base_schema", "acct_master", "母库账户主表", savedColumns,
                        List.of(savedColumns.get(0))));
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("a", "f"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTable("acct_master")).thenReturn(new ReplayBaseMetadataSnapshot(
                true, "acct_master", "母库账户主表", List.of(
                        new ReplayBaseColumnOption("a", "主键A", 1, true, 1),
                        new ReplayBaseColumnOption("f", "新主键F", 2, true, 2))));

        ReplayDbCompareRegistration detail = service.detail(created.id());

        assertFalse(detail.metadataValidation().primaryKeyChanged());
        assertTrue(detail.metadataValidation().missingPrimaryKeyNames().isEmpty());
        assertEquals(List.of("a", "f"), detail.fields().stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertTrue(detail.fields().get(1).primaryKey());
    }

    @Test
    void synchronizePrimaryKeysAddsOnlyMissingKeysWithoutDuplicates() {
        ReplayDbCompareRegistration missing = createWithMetadata(
                "missing_key", List.of("a", "c", "d"), List.of("a"));
        ReplayDbCompareRegistration existing = createWithMetadata(
                "existing_key", List.of("a", "c", "b", "d"), List.of("a"));
        ReplayDbCompareRegistration unchanged = createWithMetadata(
                "unchanged_key", List.of("a", "c"), List.of("a"));
        when(metadataService.inspectTables(any(Collection.class))).thenReturn(Map.of(
                "missing_key", metadata("missing_key", List.of("a", "b"), List.of("a", "b", "c", "d")),
                "existing_key", metadata("existing_key", List.of("a", "b"), List.of("a", "b", "c", "d")),
                "unchanged_key", metadata("unchanged_key", List.of("a"), List.of("a", "c"))));

        ReplayDbComparePrimaryKeySyncResult result = service.synchronizePrimaryKeys();

        assertEquals(3, result.scannedCount());
        assertEquals(1, result.updatedCount());
        assertEquals(1, result.addedFieldCount());
        assertEquals(0, result.conflictCount());
        ReplayDbCompareRegistration synchronizedRegistration =
                dao.findByIdIncludingDeleted(missing.id());
        assertEquals(List.of("a", "b", "c", "d"), synchronizedRegistration.fields().stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertEquals(missing.version() + 1, synchronizedRegistration.version());
        assertEquals("creator", synchronizedRegistration.reviserUsername());
        assertEquals("SYSTEM", synchronizedRegistration.updatedBy());
        assertEquals(List.of("a", "c", "b", "d"), dao.findFields(existing.id()).stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertEquals(existing.version(), dao.findByIdIncludingDeleted(existing.id()).version());
        assertEquals(unchanged.version(), dao.findByIdIncludingDeleted(unchanged.id()).version());
        ReplayDbCompareAuditPage audits = dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50));
        assertEquals(4, audits.total());
        assertEquals("系统", audits.items().get(0).operatorName());
    }

    @Test
    void normalSearchKeepsDatabasePaginationPath() {
        ReplayDbCompareQuery query = ReplayDbCompareQuery.empty(0, 20);

        service.search(query);

        verify(dao).search(query);
        verify(dao, never()).findMetadataCandidates(any());
    }

    @Test
    void tableHeaderOptionsIncludeSyntheticMissingFieldStatusWithAccurateCount() {
        service.create(save("drift_a", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        service.create(save("valid_a", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTables(any(Collection.class))).thenReturn(Map.of(
                "drift_a", new ReplayBaseMetadataSnapshot(true, "drift_a", null, List.of()),
                "valid_a", new ReplayBaseMetadataSnapshot(true, "valid_a", null,
                        List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true)))));

        var result = service.headerFilterOptions(new ReplayDbCompareHeaderFilterRequest(
                "tableName", "母库中不", 20, null, null,
                List.of(), List.of(), List.of(), null, null));

        assertEquals(1, result.options().size());
        assertEquals("比对字段母库中不存在", result.options().get(0).value());
        assertEquals(ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                result.options().get(0).metadataStatus());
        assertEquals(1, result.options().get(0).count());
    }

    @Test
    void tableHeaderOptionsIncludeSyntheticMissingTableStatus() {
        service.create(save("removed_a", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        service.create(save("valid_a", "存款组", "101", List.of("acct_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTables(any(Collection.class))).thenReturn(Map.of(
                "removed_a", new ReplayBaseMetadataSnapshot(false, "removed_a", null, List.of()),
                "valid_a", new ReplayBaseMetadataSnapshot(true, "valid_a", null,
                        List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true)))));

        var result = service.headerFilterOptions(new ReplayDbCompareHeaderFilterRequest(
                "tableName", "已删除", 20, null, null,
                List.of(), List.of(), List.of(), null, null));

        assertEquals(1, result.options().size());
        assertEquals("母库表已删除", result.options().get(0).value());
        assertEquals(ReplayDbCompareMetadataStatus.TABLE_MISSING,
                result.options().get(0).metadataStatus());
        assertEquals(1, result.options().get(0).count());
    }

    @Test
    void detailPreservesSnapshotAcrossMissingFieldsMissingTableAndUnavailableStates() {
        ReplayDbCompareRegistration created = service.create(
                save("acct_master", "存款组", "101", List.of("acct_no", "customer_no"), null),
                new ReplayIssueOperator("creator", "创建人"));
        when(metadataService.inspectTable("acct_master")).thenReturn(new ReplayBaseMetadataSnapshot(
                true, "acct_master", "母库账户主表",
                List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true))));

        ReplayDbCompareRegistration missingFields = service.detail(created.id());
        assertEquals(ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                missingFields.metadataValidation().status());
        assertEquals(List.of(true, false),
                missingFields.fields().stream().map(ReplayDbCompareField::existsInBase).toList());

        when(metadataService.inspectTable("acct_master")).thenReturn(
                new ReplayBaseMetadataSnapshot(false, "acct_master", null, List.of()));
        ReplayDbCompareRegistration missingTable = service.detail(created.id());
        assertEquals(ReplayDbCompareMetadataStatus.TABLE_MISSING,
                missingTable.metadataValidation().status());
        assertTrue(missingTable.fields().stream()
                .allMatch(field -> Boolean.FALSE.equals(field.existsInBase())));

        when(metadataService.inspectTable("acct_master")).thenThrow(
                new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用"));
        ReplayDbCompareRegistration unavailable = service.detail(created.id());
        assertEquals(ReplayDbCompareMetadataStatus.UNAVAILABLE,
                unavailable.metadataValidation().status());
        assertTrue(unavailable.fields().stream().allMatch(field -> field.existsInBase() == null));
        assertEquals(List.of("acct_no", "customer_no"),
                unavailable.fields().stream().map(ReplayDbCompareField::columnName).toList());
    }

    private ReplayDbCompareListItem item(ReplayDbCompareListPage page, String tableName) {
        return page.items().stream()
                .filter(item -> item.tableName().equals(tableName))
                .findFirst()
                .orElseThrow();
    }

    private ReplayDbCompareSaveRequest save(String table, String domain, String owner,
                                            List<String> fields, Long version) {
        return new ReplayDbCompareSaveRequest(table, domain, owner, fields, version, false);
    }

    private ReplayDbCompareRegistration createWithMetadata(
            String tableName,
            List<String> fieldNames,
            List<String> primaryKeyNames) {
        ReplayBaseMetadataSnapshot snapshot = metadata(tableName, primaryKeyNames, fieldNames);
        when(metadataService.requireTableWithColumns(tableName, fieldNames))
                .thenReturn(new ReplayBaseValidatedTable(
                        "base_schema", tableName, "母库测试表", snapshot.columns(),
                        snapshot.columns().stream().filter(ReplayBaseColumnOption::primaryKey).toList()));
        return service.create(
                save(tableName, "存款组", "101", fieldNames, null),
                new ReplayIssueOperator("creator", "创建人"));
    }

    private ReplayBaseMetadataSnapshot metadata(
            String tableName,
            List<String> primaryKeyNames,
            List<String> fieldNames) {
        Set<String> primaryKeys = Set.copyOf(primaryKeyNames);
        List<ReplayBaseColumnOption> columns = java.util.stream.IntStream.range(0, fieldNames.size())
                .mapToObj(index -> {
                    String fieldName = fieldNames.get(index);
                    int keyIndex = primaryKeyNames.indexOf(fieldName);
                    return new ReplayBaseColumnOption(
                            fieldName, fieldName.toUpperCase(), index + 1,
                            primaryKeys.contains(fieldName), keyIndex < 0 ? null : keyIndex + 1);
                })
                .toList();
        return new ReplayBaseMetadataSnapshot(true, tableName, "母库测试表", columns);
    }

    private ReplayBaseValidatedTable validated(String table, Collection<String> fieldNames) {
        List<String> normalizedNames = fieldNames.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .distinct()
                .toList();
        String fallbackPrimaryKey = normalizedNames.contains("acct_no")
                ? "acct_no"
                : normalizedNames.get(0);
        List<ReplayBaseColumnOption> columns = normalizedNames.stream()
                .map(name -> new ReplayBaseColumnOption(
                        name,
                        switch (name) {
                            case "acct_no" -> "账号";
                            case "customer_no" -> "客户号";
                            case "status" -> "状态";
                            default -> null;
                        },
                        switch (name) {
                            case "acct_no" -> 1;
                            case "customer_no" -> 2;
                            default -> 3;
                        },
                        name.equals(fallbackPrimaryKey)))
                .toList();
        return new ReplayBaseValidatedTable(
                "base_schema", table.trim().toLowerCase(), "母库账户主表", columns);
    }

    static void createUsers(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "username VARCHAR(128), real_name VARCHAR(128), emp_no VARCHAR(64), email VARCHAR(128), "
                + "phone VARCHAR(64), department VARCHAR(128), status INT, remark VARCHAR(255), "
                + "creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.batchUpdate("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES (?,?,?,?)",
                List.of(
                        new Object[]{"creator", "创建人", "100", 1},
                        new Object[]{"editor", "编辑人", "200", 1},
                        new Object[]{"other", "其他人", "300", 1},
                        new Object[]{"leader-a", "赵经理", "101", 1},
                        new Object[]{"leader-b", "钱经理", "102", 1}));
    }
}
