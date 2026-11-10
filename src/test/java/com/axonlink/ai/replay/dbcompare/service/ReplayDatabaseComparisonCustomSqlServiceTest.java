package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
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
                new ClassPathResource("db/daoindex/V91__replay_db_compare_custom_sql.sql"))
                .execute(jdbc.getDataSource());
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
    void savesMultipleQueriesAndResolvesPersonnelByThePhysicalSource() {
        var first = service.createCustomSql(request("金额汇总", SQL, null, "source_table", 1), operator());
        var second = service.createCustomSql(request("另一口径", SQL, null, "source_table", 1), operator());
        assertNotEquals(first.tableName(), second.tableName());
        assertTrue(first.tableName().startsWith("sql_"));
        assertEquals("CUSTOM_SQL", first.registrationType());
        assertEquals("source_table", first.sourceTableName());
        var page = service.search(ReplayDbCompareQuery.empty(0, 50));
        assertEquals(2, page.globalCustomSqlCount());
        assertEquals(2, page.items().size());
        assertTrue(page.items().stream().allMatch(item -> item.customSql() != null && "存款组".equals(item.domainName())));
        var detail = service.detail(first.id());
        assertNotNull(detail.customSql());
        assertEquals(ReplayDbCompareMetadataStatus.VALID, detail.metadataValidation().status());
        assertTrue(detail.fields().stream().anyMatch(field -> field.columnName().equals("total_amt")));
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
        assertEquals(SQL, snapshot.get(0).customSql().sql());
        var generator = new ReplayDatabaseComparisonConfigScriptGenerator();
        assertTrue(new String(generator.generate(generated.versionNo(), snapshot).sqlContent(), java.nio.charset.StandardCharsets.UTF_8).contains(first.tableName()));
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
    private ReplayDbCompareCustomSqlSaveRequest request(String title, String sql, Long version, String source, int partitions) {
        return new ReplayDbCompareCustomSqlSaveRequest(title, source, sql, List.of("id", "total_amt"), List.of("id"), version, null, partitions, 100);
    }
    private ReplayIssueOperator operator() { return new ReplayIssueOperator("100", "张三"); }
}
