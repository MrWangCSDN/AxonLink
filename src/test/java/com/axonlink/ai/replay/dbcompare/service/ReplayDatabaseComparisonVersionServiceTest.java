package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionSummary;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionGateError;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ReplayDatabaseComparisonVersionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-14T03:07:01.318Z"), ZoneId.of("Asia/Shanghai"));

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonDao registrationDao;
    private ReplayDatabaseComparisonVersionDao versionDao;
    private ReplayBaseMetadataService metadataService;
    private ReplayDatabaseComparisonService registrationService;
    private ReplayDatabaseComparisonVersionService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"),
                new ClassPathResource("db/daoindex/V70__replay_db_compare_scope.sql"),
                new ClassPathResource("db/daoindex/V71__replay_db_compare_ordering_primary_key_snapshot.sql"))
                .execute(jdbc.getDataSource());
        ReplayDatabaseComparisonServiceTest.createUsers(jdbc);
        SysUserDao userDao = new SysUserDao(jdbc);
        registrationDao = spy(new ReplayDatabaseComparisonDao(jdbc));
        versionDao = spy(new ReplayDatabaseComparisonVersionDao(jdbc));
        metadataService = mock(ReplayBaseMetadataService.class);
        registrationService = new ReplayDatabaseComparisonService(
                metadataService, registrationDao, versionDao, userDao,
                new ReplayDatabaseComparisonAuditDiff(), jdbc, CLOCK);
        service = new ReplayDatabaseComparisonVersionService(
                registrationService, metadataService, registrationDao, versionDao,
                new ReplayDatabaseComparisonConfigurationHasher(), userDao, jdbc, CLOCK);
    }

    @Test
    void rejectsWrongTokenBeforeReadingMetadata() {
        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("wrong", "secret", operator()));

        assertEquals("INVALID_TRIGGER_TOKEN", error.errorCode());
        verify(metadataService, never()).inspectTables(any());
    }

    @Test
    void versionQueryConditionOptionsUseReadableLabelsAndLabelKeywordSearch() {
        long versionId = versionDao.insertVersion(
                "20260914-121900", "h".repeat(64), 2, 2,
                "100", "张三", LocalDateTime.now(CLOCK));
        List<ReplayDbCompareField> fields = List.of(field("acct_no", "账号", 1, true, 1));
        ReplayDbCompareRegistration fullTable = new ReplayDbCompareRegistration(
                7L, "base_schema", "acct_full", "全表账户", "存款组",
                "100", "zhangsan", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 1L,
                "100", "张三", LocalDateTime.now(CLOCK), "100", "张三", LocalDateTime.now(CLOCK),
                fields);
        ReplayDbCompareConditionTree condition = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(
                                "status_cd", ReplayDbCompareConditionOperator.EQ, List.of("1"))))));
        ReplayDbCompareRegistration scoped = new ReplayDbCompareRegistration(
                8L, "base_schema", "acct_scoped", "条件账户", "存款组",
                "101", "lisi", "李四", "101", "赵经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 1L,
                "100", "张三", LocalDateTime.now(CLOCK), "100", "张三", LocalDateTime.now(CLOCK),
                fields, condition, null, null);
        versionDao.insertVersionTable(versionId, fullTable);
        versionDao.insertVersionTable(versionId, scoped);

        var configured = service.versionHeaderFilterOptions(
                "20260914-121900",
                new ReplayDbCompareHeaderFilterRequest(
                        "whereCondition", "status_cd", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));
        assertEquals(1, configured.options().size());
        assertEquals("(status_cd = '1')", configured.options().get(0).label());

        var allRows = service.versionHeaderFilterOptions(
                "20260914-121900",
                new ReplayDbCompareHeaderFilterRequest(
                        "whereCondition", "全表", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));
        assertEquals(1, allRows.options().size());
        assertEquals("__FULL_TABLE__", allRows.options().get(0).value());
        assertEquals("全表", allRows.options().get(0).label());
    }

    @Test
    void blocksEmptyConfigurationWithoutCreatingVersion() {
        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals("VERSION_GATE_BLOCKED", error.errorCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void returnsEveryMissingFieldAndMissingTableInOneGateFailure() {
        insertRegistration("acct_master", "账户主表", List.of(
                field("acct_no", "账号", 1, true, 1),
                field("legacy_id", "历史字段", 2, false, 2)));
        insertRegistration("removed_table", "已删除母库表", List.of(
                field("id", "主键", 1, true, 1)));
        Map<String, ReplayBaseMetadataSnapshot> metadata = new LinkedHashMap<>();
        metadata.put("acct_master", new ReplayBaseMetadataSnapshot(
                true, "acct_master", "账户主表",
                List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true, 1))));
        metadata.put("removed_table", new ReplayBaseMetadataSnapshot(
                false, "removed_table", null, List.of()));
        when(metadataService.inspectTables(any())).thenReturn(metadata);

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals("VERSION_GATE_BLOCKED", error.errorCode());
        @SuppressWarnings("unchecked")
        List<ReplayDbCompareVersionGateError> errors =
                (List<ReplayDbCompareVersionGateError>)
                        ((Map<String, Object>) error.data()).get("errors");
        assertEquals(2, errors.size());
        assertEquals("MISSING_FIELDS", errors.get(0).status().name());
        assertEquals(List.of("legacy_id"), errors.get(0).missingFieldNames());
        assertEquals("TABLE_MISSING", errors.get(1).status().name());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void blocksLimitedComparisonWhenOrderedPrimaryKeySnapshotChanged() {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        registrationDao.insertRegistration(new ReplayDbCompareRegistration(
                null, "base_schema", "limited_acct", "限量账户表", "存款组",
                "100", "zhangsan", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 0,
                "100", "张三", now, "100", "张三", now,
                List.of(
                        field("acct_no", "账号", 1, true, 1),
                        field("tenant_id", "租户", 2, true, 2)),
                null, 1000L, List.of("acct_no", "tenant_id"), null));
        when(metadataService.inspectTables(any())).thenReturn(Map.of(
                "limited_acct", new ReplayBaseMetadataSnapshot(
                        true, "limited_acct", "限量账户表", List.of(
                                new ReplayBaseColumnOption("acct_no", "账号", 1, true, 2),
                                new ReplayBaseColumnOption("tenant_id", "租户", 2, true, 1)))));

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals("VERSION_GATE_BLOCKED", error.errorCode());
        @SuppressWarnings("unchecked")
        List<ReplayDbCompareVersionGateError> errors =
                (List<ReplayDbCompareVersionGateError>)
                        ((Map<String, Object>) error.data()).get("errors");
        assertEquals(1, errors.size());
        assertEquals("ORDERING_PRIMARY_KEY_CHANGED", errors.get(0).status().name());
        assertTrue(errors.get(0).reason().contains("原顺序：acct_no、tenant_id"));
        assertTrue(errors.get(0).reason().contains("当前顺序：tenant_id、acct_no"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void generatesAShanghaiVersionAndImmutableSnapshot() {
        insertRegistration("acct_master", "旧中文名", List.of(
                field("acct_no", "旧账号", 1, true, 1),
                field("customer_no", "旧客户号", 2, false, 2)));
        Map<String, ReplayBaseMetadataSnapshot> metadata = Map.of(
                "acct_master", new ReplayBaseMetadataSnapshot(
                        true, "acct_master", "账户主表",
                        List.of(
                                new ReplayBaseColumnOption("acct_no", "账号", 1, true, 1),
                                new ReplayBaseColumnOption("customer_no", "客户号", 2, false, null))));
        when(metadataService.inspectTables(any())).thenReturn(metadata);

        ReplayDbCompareVersionSummary generated = service.generate("secret", "secret", operator());

        assertEquals("20260914-110701", generated.versionNo());
        assertEquals(1, generated.tableCount());
        assertEquals(2, generated.fieldCount());
        var snapshot = versionDao.searchVersion(
                generated.versionNo(), com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionQuery.empty(0, 50));
        assertEquals("账户主表", snapshot.items().get(0).tableComment());
        assertEquals("leader-a", snapshot.items().get(0).groupOwnerUsername());
        assertEquals("账号", snapshot.items().get(0).fields().get(0).columnComment());
        assertTrue(snapshot.items().get(0).fields().get(0).primaryKey());
        assertEquals(1, snapshot.items().get(0).fields().get(0).primaryKeyOrder());
    }

    @Test
    void returnsAllScopeAndPrimaryKeyGateErrorsWithoutCreatingAPartialVersion() {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        ReplayDbCompareConditionTree condition = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(
                                new ReplayDbCompareCondition(
                                        "missing_status", ReplayDbCompareConditionOperator.EQ,
                                        List.of("1")),
                                new ReplayDbCompareCondition(
                                        "amount", ReplayDbCompareConditionOperator.BETWEEN,
                                        List.of("bad"))))));
        registrationDao.insertRegistration(new ReplayDbCompareRegistration(
                null, "base_schema", "acct_master", "账户主表", "存款组",
                "100", "zhangsan", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 0,
                "100", "张三", now, "100", "张三", now,
                List.of(field("acct_no", "账号", 1, false, 1)),
                condition, 1000L, null));
        when(metadataService.inspectTables(any())).thenReturn(Map.of(
                "acct_master", new ReplayBaseMetadataSnapshot(
                        true, "acct_master", "账户主表",
                        List.of(
                                new ReplayBaseColumnOption(
                                        "acct_no", "账号", "character varying", 1, false, null),
                                new ReplayBaseColumnOption(
                                        "amount", "金额", "numeric", 2, false, null)))));

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        @SuppressWarnings("unchecked")
        List<ReplayDbCompareVersionGateError> errors =
                (List<ReplayDbCompareVersionGateError>)
                        ((Map<String, Object>) error.data()).get("errors");
        assertEquals(4, errors.size());
        assertTrue(errors.stream().anyMatch(item -> item.reason().contains("missing_status")));
        assertTrue(errors.stream().anyMatch(item -> item.reason().contains("BETWEEN")));
        assertTrue(errors.stream().anyMatch(item -> item.reason().contains("完整且有序的主键")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void rejectsAnUnchangedLatestConfigurationWithoutAddingAnotherVersion() {
        insertRegistration("acct_master", "账户主表", List.of(
                field("acct_no", "账号", 1, true, 1)));
        when(metadataService.inspectTables(any())).thenReturn(Map.of(
                "acct_master", new ReplayBaseMetadataSnapshot(
                        true, "acct_master", "账户主表",
                        List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true, 1)))));
        service.generate("secret", "secret", operator());

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals("CONFIGURATION_UNCHANGED", error.errorCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void returnsEveryRegistrationWhenBaseMetadataIsGloballyUnavailable() {
        insertRegistration("acct_master", "账户主表", List.of(
                field("acct_no", "账号", 1, true, 1)));
        insertRegistration("loan_master", "贷款主表", List.of(
                field("loan_no", "贷款账号", 1, true, 1)));
        when(metadataService.inspectTables(any()))
                .thenThrow(new ReplayBaseDatabaseUnavailableException("BASE unavailable"));

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals("VERSION_GATE_BLOCKED", error.errorCode());
        @SuppressWarnings("unchecked")
        List<ReplayDbCompareVersionGateError> errors =
                (List<ReplayDbCompareVersionGateError>)
                        ((Map<String, Object>) error.data()).get("errors");
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(item -> "UNAVAILABLE".equals(item.status().name())));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void rejectsGenerationWhenARegistrationChangesAfterValidation() {
        insertRegistration("acct_master", "账户主表", List.of(
                field("acct_no", "账号", 1, true, 1)));
        List<ReplayDbCompareRegistration> validated = registrationDao.findAllActiveWithFields();
        ReplayDbCompareRegistration original = validated.get(0);
        ReplayDbCompareRegistration changed = withVersion(original, original.version() + 1);
        doReturn(validated, List.of(changed)).when(registrationDao).findAllActiveWithFields();
        when(metadataService.inspectTables(any())).thenReturn(Map.of(
                "acct_master", metadataFor(original)));

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals("REGISTRATION_CHANGED", error.errorCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void reportsVersionNumberConflictWhenConfigurationChangesWithinTheSameSecond() {
        insertRegistration("acct_master", "账户主表", List.of(
                field("acct_no", "账号", 1, true, 1)));
        when(metadataService.inspectTables(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> tableNames = invocation.getArgument(0);
            Map<String, ReplayBaseMetadataSnapshot> result = new LinkedHashMap<>();
            for (String tableName : tableNames) {
                List<ReplayDbCompareRegistration> registrations = registrationDao.findAllActiveWithFields();
                registrations.stream()
                        .filter(item -> item.tableName().equals(tableName))
                        .findFirst()
                        .ifPresent(item -> result.put(tableName, metadataFor(item)));
            }
            return result;
        });
        service.generate("secret", "secret", operator());
        insertRegistration("loan_master", "贷款主表", List.of(
                field("loan_no", "贷款账号", 1, true, 1)));

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals("VERSION_NUMBER_CONFLICT", error.errorCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    @Test
    void rollsBackTheWholeSnapshotWhenFieldPersistenceFails() {
        insertRegistration("acct_master", "账户主表", List.of(
                field("acct_no", "账号", 1, true, 1)));
        ReplayDbCompareRegistration registration = registrationDao.findAllActiveWithFields().get(0);
        when(metadataService.inspectTables(any())).thenReturn(Map.of(
                "acct_master", metadataFor(registration)));
        doThrow(new IllegalStateException("field snapshot failed"))
                .when(versionDao).insertVersionFields(any(Long.class), any());

        assertThrows(IllegalStateException.class,
                () -> service.generate("secret", "secret", operator()));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version_table", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version_field", Integer.class));
    }

    @Test
    void allowsReturningToAnOlderConfigurationWhenItDiffersFromTheLatestVersion() {
        insertRegistration("acct_master", "账户主表", List.of(
                field("acct_no", "账号", 1, true, 1)));
        ReplayDbCompareRegistration registration = registrationDao.findAllActiveWithFields().get(0);
        ReplayDatabaseComparisonConfigurationHasher configurationHasher =
                new ReplayDatabaseComparisonConfigurationHasher();
        String restoredHash = configurationHasher.hash(List.of(registration));
        LocalDateTime now = LocalDateTime.now(CLOCK);
        versionDao.insertVersion("20260912-110701", restoredHash, 1, 1,
                "100", "张三", now.minusDays(2));
        versionDao.insertVersion("20260913-110701", "f".repeat(64), 1, 1,
                "100", "张三", now.minusDays(1));
        when(metadataService.inspectTables(any())).thenReturn(Map.of(
                "acct_master", metadataFor(registration)));

        ReplayDbCompareVersionSummary generated = service.generate(
                "secret", "secret", operator());

        assertEquals("20260914-110701", generated.versionNo());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version", Integer.class));
    }

    private void insertRegistration(String tableName, String tableComment, List<ReplayDbCompareField> fields) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        registrationDao.insertRegistration(new ReplayDbCompareRegistration(
                null, "base_schema", tableName, tableComment, "存款组",
                "100", "zhangsan", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 0,
                "100", "张三", now, "100", "张三", now, fields));
    }

    private ReplayDbCompareField field(
            String name,
            String comment,
            int ordinal,
            boolean primaryKey,
            int order) {
        return new ReplayDbCompareField(name, comment, ordinal, primaryKey, order);
    }

    private ReplayBaseMetadataSnapshot metadataFor(ReplayDbCompareRegistration registration) {
        return new ReplayBaseMetadataSnapshot(
                true, registration.tableName(), registration.tableComment(),
                registration.fields().stream()
                        .map(item -> new ReplayBaseColumnOption(
                                item.columnName(), item.columnComment(), item.ordinalPosition(),
                                item.primaryKey(), item.primaryKey() ? item.comparisonOrder() : null))
                        .toList());
    }

    private ReplayDbCompareRegistration withVersion(
            ReplayDbCompareRegistration registration,
            long version) {
        return new ReplayDbCompareRegistration(
                registration.id(), registration.schemaName(), registration.tableName(),
                registration.tableComment(), registration.domainName(), registration.reviserEmpNo(),
                registration.reviserUsername(), registration.reviserName(),
                registration.groupOwnerEmpNo(), registration.groupOwnerName(),
                registration.registeredDate(), registration.deleted(), registration.deletedReason(),
                registration.deletedBy(), registration.deletedAt(), version,
                registration.createdBy(), registration.createdName(), registration.createdAt(),
                registration.updatedBy(), registration.updatedName(), registration.updatedAt(),
                registration.fields());
    }

    private ReplayIssueOperator operator() {
        return new ReplayIssueOperator("100", "张三");
    }
}
