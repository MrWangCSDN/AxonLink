package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.ReplayDbCompareSampleLimitMigrationFixture;
import com.axonlink.ai.replay.dbcompare.dto.*;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayDatabaseTablePersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReplayDatabaseComparisonCustomSqlServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String SQL = "select id, sum(amount) as total_amt from source_table group by id";
    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonDao dao;
    private ReplayDatabaseComparisonVersionDao versions;
    private ReplayBaseMetadataService metadata;
    private ReplayDatabaseComparisonCustomSqlAnalyzer analyzer;
    private ReplayDatabaseComparisonService service;
    private ReplayDatabaseComparisonVersionService versionService;

    @Test
    void multipleSqlConfigurationsKeepIndependentFixedCodesAndNeverReuseDeletedSuffixes() throws Exception {
        var first = service.createCustomSql(request("条件一", SQL, null, "source_table", 1), operator());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var values = mapper.valueToTree(request("条件二", SQL.replace("sum(amount)", "sum(amount * 2)"), null, "source_table", 1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) values).put("createAdditionalConfiguration", true);
        var second = service.createCustomSql(mapper.treeToValue(values, ReplayDbCompareCustomSqlSaveRequest.class), operator());
        assertEquals("source_table_1", second.tableName());
        assertEquals("source_table", second.sourceTableName());
        assertEquals("条件二", second.configurationName());
        var renamed = service.updateCustomSql(second.id(), request("重命名", second.customSql().sql(), second.version(), "source_table", 1), operator());
        assertEquals(second.tableName(), renamed.tableName());
        assertEquals("重命名", renamed.configurationName());
        service.delete(renamed.id(), new ReplayDbCompareDeleteRequest(renamed.version(), "删除"), operator());
        assertEquals("source_table_2", service.createCustomSql(mapper.treeToValue(values, ReplayDbCompareCustomSqlSaveRequest.class), operator()).tableName());
        assertEquals(first, dao.findByIdIncludingDeleted(first.id()));
    }

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"),
                new ClassPathResource("db/daoindex/V70__replay_db_compare_scope.sql"),
                new ClassPathResource("db/daoindex/V71__replay_db_compare_ordering_primary_key_snapshot.sql"),
                new ClassPathResource("db/daoindex/V74__replay_db_compare_partition_num.sql"),
                new ClassPathResource("db/daoindex/V75__replay_db_compare_sample_limit.sql"),
                new ClassPathResource("db/daoindex/V81__replay_db_compare_comparison_key.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),
                new ClassPathResource("db/daoindex/V91__replay_db_compare_custom_sql.sql"),
                new ClassPathResource("db/daoindex/V96__replay_db_compare_max_partition_num.sql"))
                .execute(jdbc.getDataSource());
        com.axonlink.ai.replay.dbcompare.ReplayDbCompareMultiConfigurationFixture.install(jdbc);
        ReplayDatabaseComparisonServiceTest.createUsers(jdbc);
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,developer_display,developer_usernames,developer_emp_nos,technology_owner_display,technology_owner_emp_nos,imported_at) VALUES ('source_table','存款组','张三(zhangsan)','zhangsan','100','赵经理','101',CURRENT_TIMESTAMP)");
        dao = new ReplayDatabaseComparisonDao(jdbc);
        versions = new ReplayDatabaseComparisonVersionDao(jdbc);
        metadata = mock(ReplayBaseMetadataService.class);
        analyzer = mock(ReplayDatabaseComparisonCustomSqlAnalyzer.class);
        when(analyzer.analyze(any())).thenAnswer(call -> analysis(call.getArgument(0)));
        when(analyzer.prepare(any(), any(), any(), any())).thenAnswer(call -> new ReplayDatabaseComparisonCustomSqlAnalyzer.PreparedSql(
                analysis(call.getArgument(0)), List.of(new ReplayDbCompareField("id", "标识", 1, true, 1),
                new ReplayDbCompareField("total_amt", "总金额", 2, false, 2)),
                new ReplayDbCompareKeySelection("SQL_RESULT_KEY", "RESULT_KEY", List.of("id"))));
        var base = new ReplayBaseMetadataSnapshot(true, "source_table", "来源表", List.of(
                new ReplayBaseColumnOption("id", "标识", 1, true, 1),
                new ReplayBaseColumnOption("amount", "金额", 2, false, null)));
        when(metadata.inspectTables(any())).thenReturn(Map.of("source_table", base));
        when(metadata.inspectTable("source_table")).thenReturn(base);
        SysUserDao users = new SysUserDao(jdbc);
        service = new ReplayDatabaseComparisonService(metadata, dao, versions, users,
                new ReplayDatabaseComparisonAuditDiff(), jdbc, CLOCK);
        service.setTablePersonDao(new ReplayDatabaseTablePersonDao(jdbc));
        service.setCustomSqlAnalyzer(analyzer);
        versionService = new ReplayDatabaseComparisonVersionService(service, metadata, dao, versions,
                new ReplayDatabaseComparisonConfigurationHasher(), users, jdbc, CLOCK);
        versionService.setTablePersonDao(new ReplayDatabaseTablePersonDao(jdbc));
        versionService.setCustomSqlAnalyzer(analyzer);
    }

    @Test
    void maximumHashPartitionChangesUseOrdinarySavePermissionAndFreezeSqlSnapshots() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var values = json.valueToTree(request("来源表", SQL, null, "source_table", 1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) values).put("maxPartitionNum", 100);
        var created = service.createCustomSql(json.treeToValue(values, ReplayDbCompareCustomSqlSaveRequest.class), operator());
        assertEquals(100, created.maxPartitionNum());
        assertEquals(100, service.detail(created.id()).maxPartitionNum());
        assertEquals(100, service.search(ReplayDbCompareQuery.empty(0, 20)).items().get(0).maxPartitionNum());
        var generated = versionService.generate("secret", "secret", operator());
        assertEquals(100, versions.findCompleteSnapshot(versions.findStoredVersion(generated.versionNo()).id()).get(0).maxPartitionNum());
        var updatedValues = json.valueToTree(request("来源表", SQL, created.version(), "source_table", 1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) updatedValues).put("maxPartitionNum", 256);
        var updated = service.updateCustomSql(created.id(), json.treeToValue(updatedValues, ReplayDbCompareCustomSqlSaveRequest.class), operator());
        assertEquals(256, updated.maxPartitionNum());
        assertEquals(created.version() + 1, updated.version());
        assertEquals(256, service.updateCustomSql(updated.id(), request("来源表", SQL, updated.version(), "source_table", 1), operator()).maxPartitionNum());
        assertEquals(100, versions.findCompleteSnapshot(versions.findStoredVersion(generated.versionNo()).id()).get(0).maxPartitionNum());
        assertEquals("256", jdbc.queryForObject("SELECT after_value FROM dii_replay_db_compare_audit_detail WHERE field_code='maxPartitionNum' AND before_value='100'", String.class));
    }

    @Test
    void customSqlFullDifferenceSampleIsStoredAndFrozenIndependentlyOfLaterEdits() throws Exception {
        var properties = new com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties();
        properties.setPartitionAdminEmpNos(List.of("100"));
        service.setPartitionProperties(properties);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                json.valueToTree(request("来源表", SQL, null, "source_table", 1));
        payload.put("sampleLimit", -1);
        var full = assertDoesNotThrow(() -> json.treeToValue(payload, ReplayDbCompareCustomSqlSaveRequest.class));
        ReplayDbCompareSampleLimitMigrationFixture.apply(jdbc);
        var saved = service.createCustomSql(full, operator());
        assertEquals(-1, saved.sampleLimit());

        var version = versionService.generate("secret", "secret", operator());
        long snapshotId = versions.findStoredVersion(version.versionNo()).id();
        var frozen = versions.findCompleteSnapshot(snapshotId).get(0);
        assertEquals(-1, frozen.sampleLimit());

        payload.put("version", saved.version()).put("sampleLimit", 0);
        var summaryOnly = service.updateCustomSql(saved.id(),
                json.treeToValue(payload, ReplayDbCompareCustomSqlSaveRequest.class), operator());
        assertEquals(0, summaryOnly.sampleLimit());
        assertEquals("0", jdbc.queryForObject("SELECT after_value FROM dii_replay_db_compare_audit_detail "
                + "WHERE field_code='sampleLimit' AND before_value='-1'", String.class));
        assertEquals(-1, versions.findCompleteSnapshot(snapshotId).get(0).sampleLimit());
    }

    @Test
    void savesPhysicalTableIdentityAndResolvesPersonnelByThePhysicalSource() {
        var first = service.createCustomSql(request("客户端伪造名称", SQL, null, "source_table", 1), operator());
        assertEquals(64, first.maxPartitionNum());
        assertEquals("source_table", first.tableName());
        assertEquals("来源表", first.tableComment());
        assertEquals("CUSTOM_SQL", first.registrationType());
        assertEquals("source_table", first.sourceTableName());
        var page = service.search(ReplayDbCompareQuery.empty(0, 50));
        assertEquals(1, page.globalCustomSqlCount());
        assertEquals(1, page.items().size());
        assertTrue(page.items().stream().allMatch(item -> item.customSql() != null && "存款组".equals(item.domainName())));
        var detail = service.detail(first.id());
        assertNotNull(detail.customSql());
        assertEquals(ReplayDbCompareMetadataStatus.VALID, detail.metadataValidation().status());
        assertTrue(detail.fields().stream().anyMatch(field -> field.columnName().equals("total_amt")));
        assertEquals("表中文名", jdbc.queryForObject("SELECT field_label FROM dii_replay_db_compare_audit_detail WHERE field_code='tableComment'", String.class));
    }

    @Test
    void allowsMissingDisplayNameAndMissingDatabaseComment() {
        var original = analysis(SQL);
        when(analyzer.prepare(any(), any(), any(), any())).thenReturn(new ReplayDatabaseComparisonCustomSqlAnalyzer.PreparedSql(
                new ReplayDbCompareSqlAnalysis(SQL, original.schemaName(), original.sourceTableName(), null,
                        original.resultColumns(), original.candidateKeyColumns()),
                List.of(new ReplayDbCompareField("id", "标识", 1, true, 1)),
                new ReplayDbCompareKeySelection("SQL_RESULT_KEY", "RESULT_KEY", List.of("id"))));
        var saved = service.createCustomSql(request(null, SQL, null, "source_table", 1), operator());
        assertEquals("source_table", saved.tableName());
        assertNull(saved.tableComment());
    }

    @Test
    void duplicateSqlRegistrationIsRejectedWithoutChangingTheOriginal() {
        var first = service.createCustomSql(request("来源表", SQL, null, "source_table", 1), operator());
        var error = assertThrows(ReplayDatabaseComparisonGenerationException.class, () ->
                service.createCustomSql(request("另一名称", SQL.replace("sum(amount)", "sum(amount * 2)"), null, "source_table", 1), operator()));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.status());
        assertEquals("DUPLICATE_REGISTRATION", error.errorCode());
        assertTrue(error.getMessage().contains("原登记"));
        assertEquals(first, dao.findByIdIncludingDeleted(first.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_registration", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event", Integer.class));
    }

    @Test
    void ordinaryRegistrationMustBeEditedAndConvertsInPlaceWithCreationHistoryIntact() {
        var first = ordinaryRegistration();
        assertThrows(ReplayDatabaseComparisonGenerationException.class, () ->
                service.createCustomSql(request("来源表", SQL, null, "source_table", 1), operator()));
        assertNull(dao.findByIdIncludingDeleted(first.id()).customSql());
        var updated = service.updateCustomSql(first.id(), request("来源表", SQL, first.version(), "source_table", 1), operator());
        assertEquals(first.id(), updated.id());
        assertEquals(first.createdBy(), updated.createdBy());
        assertEquals(first.createdAt(), updated.createdAt());
        assertEquals(first.version() + 1, updated.version());
        assertEquals("source_table", updated.tableName());
        assertEquals("来源表", updated.tableComment());
        assertEquals("CUSTOM_SQL", updated.registrationType());
        assertEquals(SQL, updated.customSql().sql());
        assertEquals(List.of("id", "total_amt"), updated.fields().stream().map(ReplayDbCompareField::columnName).toList());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event WHERE operation='UPDATE'", Integer.class));
        assertThrows(ReplayDatabaseComparisonVersionConflictException.class, () ->
                service.updateCustomSql(first.id(), request("来源表", SQL, first.version(), "source_table", 1), operator()));
    }

    @Test
    void legacyAliasEditKeepsTheFixedCodeAndIsIdempotent() {
        var first = service.createCustomSql(request("来源表", SQL, null, "source_table", 1), operator());
        jdbc.update("UPDATE dii_replay_db_compare_registration SET table_name='sql_legacy' WHERE id=?", first.id());
        var updated = service.updateCustomSql(first.id(), request("来源表", SQL, first.version(), "source_table", 1), operator());
        assertEquals("sql_legacy", updated.tableName());
        assertEquals(first.version(), updated.version());
        assertEquals("source_table", updated.sourceTableName());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_detail WHERE field_code='tableName'", Integer.class));
    }

    @Test
    void failedFieldReplacementRollsBackTheConversionAndAudit() {
        var first = ordinaryRegistration();
        var failingDao = spy(dao);
        doThrow(new IllegalStateException("field write failed")).when(failingDao).replaceFields(eq(first.id()), any(), any());
        var failingService = new ReplayDatabaseComparisonService(metadata, failingDao, versions, new SysUserDao(jdbc),
                new ReplayDatabaseComparisonAuditDiff(), jdbc, CLOCK);
        failingService.setTablePersonDao(new ReplayDatabaseTablePersonDao(jdbc));
        failingService.setCustomSqlAnalyzer(analyzer);
        assertThrows(IllegalStateException.class, () ->
                failingService.updateCustomSql(first.id(), request("来源表", SQL, first.version(), "source_table", 1), operator()));
        assertEquals(first, dao.findByIdIncludingDeleted(first.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event", Integer.class));
    }

    @Test
    void duplicateDiscoveredByTheUniqueKeyReturnsTheSameConflictAfterRollback() {
        var first = ordinaryRegistration();
        var racedDao = spy(dao);
        doReturn(null).doCallRealMethod().when(racedDao).findBySchemaAndTable("base_schema", "source_table");
        var racedService = serviceWithDao(racedDao);
        var error = assertThrows(ReplayDatabaseComparisonGenerationException.class, () ->
                racedService.createCustomSql(request(null, SQL, null, "source_table", 1), operator()));
        assertEquals("DUPLICATE_REGISTRATION", error.errorCode());
        assertEquals(Map.of("existingId", first.id(), "schema", "base_schema", "tableName", "source_table"), error.data());
        assertEquals(first, dao.findByIdIncludingDeleted(first.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_registration", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event", Integer.class));
    }

    @Test
    void legacyAliasEditDoesNotOverwriteAnExistingPhysicalRegistration() {
        var sql = service.createCustomSql(request(null, SQL, null, "source_table", 1), operator());
        jdbc.update("UPDATE dii_replay_db_compare_registration SET table_name='sql_legacy' WHERE id=?", sql.id());
        var legacy = dao.findByIdIncludingDeleted(sql.id());
        var physical = ordinaryRegistration();
        var updated = service.updateCustomSql(sql.id(), request(null, SQL, sql.version(), "source_table", 1), operator());
        assertEquals("sql_legacy", updated.tableName());
        assertEquals(legacy, dao.findByIdIncludingDeleted(sql.id()));
        assertEquals(physical, dao.findByIdIncludingDeleted(physical.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event", Integer.class));
    }

    @Test
    void oldVersionIdentityRemainsFrozenAfterExplicitLegacyAliasEdit() {
        var first = service.createCustomSql(request(null, SQL, null, "source_table", 1), operator());
        jdbc.update("UPDATE dii_replay_db_compare_registration SET table_name='sql_legacy' WHERE id=?", first.id());
        var generated = versionService.generate("secret", "secret", operator());
        service.updateCustomSql(first.id(), request(null, SQL, first.version(), "source_table", 1), operator());
        var oldSnapshot = versions.findCompleteSnapshot(versions.findStoredVersion(generated.versionNo()).id());
        assertEquals("sql_legacy", oldSnapshot.get(0).tableName());
        assertEquals(SQL, oldSnapshot.get(0).customSql().sql());
        assertEquals("sql_legacy", dao.findByIdIncludingDeleted(first.id()).tableName());
    }

    @Test
    void deletedSqlCanBeExplicitlyReregisteredUsingItsOriginalIdAndVersion() {
        var first = service.createCustomSql(request(null, SQL, null, "source_table", 1), operator());
        var deleted = service.delete(first.id(), new ReplayDbCompareDeleteRequest(first.version(), "停用"), operator());
        var duplicate = assertThrows(ReplayDatabaseComparisonGenerationException.class, () ->
                service.createCustomSql(request(null, SQL, null, "source_table", 1), operator()));
        assertEquals("DUPLICATE_REGISTRATION", duplicate.errorCode());
        assertTrue(dao.findByIdIncludingDeleted(first.id()).deleted());
        String changedSql = SQL.replace("sum(amount)", "sum(amount * 2)");
        var restored = service.updateCustomSql(first.id(), request(null, changedSql, deleted.version(), "source_table", 1), operator());
        assertEquals(first.id(), restored.id());
        assertFalse(restored.deleted());
        assertNull(restored.deletedAt());
        assertNull(restored.deletedReason());
        assertNull(restored.deletedBy());
        assertEquals(deleted.version() + 1, restored.version());
        assertEquals(first.createdAt(), restored.createdAt());
        assertEquals(SQL.replace("sum(amount)", "sum(amount * 2)"), restored.customSql().sql());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event WHERE operation='REREGISTER'", Integer.class));
        assertThrows(ReplayDatabaseComparisonVersionConflictException.class, () ->
                service.updateCustomSql(first.id(), request(null, SQL, deleted.version(), "source_table", 1), operator()));
    }

    @Test
    void failedSqlReregistrationRollsBackDeletionMarkersFieldsAndAudit() {
        var first = service.createCustomSql(request(null, SQL, null, "source_table", 1), operator());
        var deleted = service.delete(first.id(), new ReplayDbCompareDeleteRequest(first.version(), "停用"), operator());
        var failingDao = spy(dao);
        doThrow(new IllegalStateException("field write failed")).when(failingDao).replaceFields(eq(first.id()), any(), any());
        var failure = assertThrows(IllegalStateException.class, () -> serviceWithDao(failingDao).updateCustomSql(
                first.id(), request(null, SQL, deleted.version(), "source_table", 1), operator()));
        assertEquals("field write failed", failure.getMessage());
        assertEquals(deleted, dao.findByIdIncludingDeleted(first.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event WHERE operation='REREGISTER'", Integer.class));
    }

    @Test
    void sqlEditIsAuditedAndCannotChangeTheSourceOrLogicalIdentity() {
        var first = service.createCustomSql(request("汇总", SQL, null, "source_table", 1), operator());
        String changedSql = SQL.replace("sum(amount)", "sum(amount * 2)");
        var updated = service.updateCustomSql(first.id(), request("新名称", changedSql, first.version(), "source_table", 1), operator());
        assertEquals(first.tableName(), updated.tableName());
        assertEquals(first.version() + 1, updated.version());
        assertEquals(changedSql, updated.customSql().sql());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_detail WHERE field_label='自定义 SQL'", Integer.class) > 0);
        assertThrows(ReplayDatabaseComparisonVersionConflictException.class, () ->
                service.updateCustomSql(first.id(), request("旧编辑", changedSql, first.version(), "source_table", 1), operator()));
        assertThrows(IllegalArgumentException.class, () ->
                service.updateCustomSql(first.id(), request("迁移来源", changedSql, updated.version(), "other_table", 1), operator()));
        assertEquals(changedSql, dao.findByIdIncludingDeleted(first.id()).customSql().sql());
    }

    @Test
    void fullVersionFreezesCustomQueryAndBothTargetExportsUseItsProjection() {
        var first = service.createCustomSql(request("汇总", SQL, null, "source_table", 1), operator());
        var generated = versionService.generate("secret", "secret", operator());
        assertEquals(1, generated.customSqlCount());
        var snapshot = versions.findCompleteSnapshot(versions.findStoredVersion(generated.versionNo()).id());
        assertEquals("source_table", snapshot.get(0).tableName());
        assertEquals(SQL, snapshot.get(0).customSql().sql());
        var generator = new ReplayDatabaseComparisonConfigScriptGenerator();
        assertTrue(new String(generator.generate(generated.versionNo(), snapshot).sqlContent(), java.nio.charset.StandardCharsets.UTF_8).contains("," + "'source_table','来源表比对'"));
        assertTrue(new String(generator.generateNew(generated.versionNo(), snapshot).sqlContent(), java.nio.charset.StandardCharsets.UTF_8).contains("sum(amount)"));
        service.updateCustomSql(first.id(), request("变更", SQL.replace("sum(amount)", "sum(amount * 2)"), first.version(), "source_table", 1), operator());
        assertEquals(SQL, versions.findCompleteSnapshot(versions.findStoredVersion(generated.versionNo()).id()).get(0).customSql().sql());
    }

    @Test
    void invalidQueriesAndUnauthorisedLimitsLeaveNoRegistrationOrAudit() {
        when(analyzer.prepare(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("SQL不合法"));
        assertThrows(ReplayDatabaseComparisonGenerationException.class, () -> service.createCustomSql(request("错误", SQL, null, "source_table", 1), operator()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_registration", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_audit_event", Integer.class));
        reset(analyzer);
        assertThrows(ReplayDatabaseComparisonPartitionForbiddenException.class, () -> service.createCustomSql(request("越权分区", SQL, null, "source_table", 2), operator()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_registration", Integer.class));
    }

    @Test
    void customSqlFilterHasReadableLabelAndOrdinaryUpdateCannotOverwriteIt() {
        var item = service.createCustomSql(request("汇总", SQL, null, "source_table", 1), operator());
        var options = service.headerFilterOptions(new ReplayDbCompareHeaderFilterRequest(
                "whereCondition", "", 100, null, null, List.of(), List.of(), List.of(), null, null));
        assertTrue(options.options().stream().anyMatch(option -> "__CUSTOM_SQL__".equals(option.value())
                && "自定义 SQL".equals(option.label())));
        assertThrows(IllegalArgumentException.class, () -> service.update(item.id(), new ReplayDbCompareSaveRequest(
                "source_table", "存款组", "101", List.of("id"), item.version(), false), operator()));
        assertEquals(SQL, dao.findByIdIncludingDeleted(item.id()).customSql().sql());
    }

    @Test
    void configurationHashTracksSqlEvenWhenTheRegistrationVersionIsUnchanged() {
        var first = service.createCustomSql(request("汇总", SQL, null, "source_table", 1), operator());
        var changed = first.withCustomSql(new ReplayDbCompareCustomSql(SQL.replace("sum(amount)", "sum(amount * 2)"),
                "source_table", first.customSql().resultColumns()));
        var hasher = new ReplayDatabaseComparisonConfigurationHasher();
        assertNotEquals(hasher.hash(List.of(first)), hasher.hash(List.of(changed)));
    }

    private ReplayDbCompareSqlAnalysis analysis(String sql) {
        return new ReplayDbCompareSqlAnalysis(sql, "base_schema", "source_table", "来源表", List.of(
                new ReplayDbCompareSqlColumn("id", "id", "VARCHAR", 1),
                new ReplayDbCompareSqlColumn("total_amt", "total_amt", "NUMERIC", 2)), List.of("id"));
    }
    private ReplayDbCompareRegistration ordinaryRegistration() {
        var now = java.time.LocalDateTime.now(CLOCK).minusDays(2);
        var item = new ReplayDbCompareRegistration(null, "base_schema", "source_table", "旧中文名", "存款组",
                "100", "zhangsan", "张三", "101", "赵经理", now.toLocalDate(), false, null, null, null,
                3, "100", "张三", now, "100", "张三", now,
                List.of(new ReplayDbCompareField("id", "标识", 1, true, 1)), null, null, List.of("id"), null);
        return dao.findByIdIncludingDeleted(dao.insertRegistration(item));
    }
    private ReplayDatabaseComparisonService serviceWithDao(ReplayDatabaseComparisonDao targetDao) {
        var target = new ReplayDatabaseComparisonService(metadata, targetDao, versions, new SysUserDao(jdbc),
                new ReplayDatabaseComparisonAuditDiff(), jdbc, CLOCK);
        target.setTablePersonDao(new ReplayDatabaseTablePersonDao(jdbc));
        target.setCustomSqlAnalyzer(analyzer);
        return target;
    }
    private ReplayDbCompareCustomSqlSaveRequest request(String title, String sql, Long version, String source, int partitions) {
        return new ReplayDbCompareCustomSqlSaveRequest(title, source, sql, List.of("id", "total_amt"), List.of("id"), version, null, partitions, 100);
    }
    private ReplayIssueOperator operator() { return new ReplayIssueOperator("100", "张三"); }
}
