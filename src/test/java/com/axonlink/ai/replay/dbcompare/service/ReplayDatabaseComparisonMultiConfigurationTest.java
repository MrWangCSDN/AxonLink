package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.*;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplayDatabaseComparisonMultiConfigurationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Set<String> physicalTables = new HashSet<>(Set.of("a", "b"));
    private final ReplayIssueOperator actor = new ReplayIssueOperator("creator", "创建人");
    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonService service;
    private ReplayBaseMetadataService metadata;

    @BeforeEach
    void setup() {
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
        metadata = mock(ReplayBaseMetadataService.class);
        var columns = List.of(new ReplayBaseColumnOption("id", "标识", 1, true, 1),
                new ReplayBaseColumnOption("amount", "金额", 2, false, null),
                new ReplayBaseColumnOption("flag_a", "条件一", "integer", 3, false, null),
                new ReplayBaseColumnOption("flag_b", "条件二", "integer", 4, false, null));
        when(metadata.schemaName()).thenReturn("base");
        when(metadata.requireTableWithColumns(anyString(), anyList())).thenAnswer(call -> {
            String table = call.getArgument(0);
            assertTrue(physicalTables.contains(table), "Metadata must be queried with the real table: " + table);
            return new ReplayBaseValidatedTable("base", table, "真实表", columns);
        });
        when(metadata.inspectTable(anyString())).thenAnswer(call -> {
            String table = call.getArgument(0);
            return new ReplayBaseMetadataSnapshot(physicalTables.contains(table), table, "真实表", columns);
        });
        when(metadata.inspectTables(anyCollection())).thenAnswer(call -> {
            var values = new java.util.LinkedHashMap<String, ReplayBaseMetadataSnapshot>();
            for (String table : (java.util.Collection<String>) call.getArgument(0)) {
                values.put(table, new ReplayBaseMetadataSnapshot(physicalTables.contains(table), table, "真实表", columns));
            }
            return values;
        });
        var dao = new ReplayDatabaseComparisonDao(jdbc);
        service = new ReplayDatabaseComparisonService(metadata, dao,
                new ReplayDatabaseComparisonVersionDao(jdbc), new SysUserDao(jdbc),
                new ReplayDatabaseComparisonAuditDiff(), jdbc, java.time.Clock.systemUTC());
    }

    @Test
    void defaultCodeRemainsTheTableAndAdditionalConfigurationsGetIndependentCodes() throws Exception {
        var first = service.create(request("a", "条件一", null, false), actor);
        var second = assertDoesNotThrow(() -> service.create(request("a", "条件二", null, true), actor));

        assertEquals("a", first.tableName());
        assertEquals("a_1", second.tableName());
        assertNotEquals(first.id(), second.id());
        assertEquals("a", second.sourceTableName());
        assertEquals("条件二", json.valueToTree(second).path("configurationName").asText());
        assertEquals("a_1", new ReplayDatabaseComparisonDao(jdbc).findByIdIncludingDeleted(second.id()).tableName());
    }

    @Test
    void editingTheAliasUsesThePhysicalTableAndKeepsItsFixedCode() throws Exception {
        service.create(request("a", "默认", null, false), actor);
        var second = service.create(request("a", "条件二", null, true), actor);
        var changed = service.update(second.id(), request("a_1", "新名称", second.version(), false), actor);

        assertEquals("a_1", changed.tableName());
        assertEquals("a", changed.sourceTableName());
        assertEquals("新名称", json.valueToTree(changed).path("configurationName").asText());
        assertThrows(IllegalArgumentException.class,
                () -> service.update(changed.id(), request("b", "换来源", changed.version(), false), actor));
    }

    @Test
    void sequenceDoesNotReuseDeletedCodesOrCodesRemovedFromTheRegistrationTable() throws Exception {
        service.create(request("a", "默认", null, false), actor);
        var one = service.create(request("a", "一", null, true), actor);
        var two = service.create(request("a", "二", null, true), actor);
        service.delete(one.id(), new ReplayDbCompareDeleteRequest(one.version(), "删除配置"), actor);
        jdbc.update("DELETE FROM dii_replay_db_compare_registration WHERE id=?", two.id());

        assertEquals("a_3", service.create(request("a", "三", null, true), actor).tableName());
    }

    @Test
    void realTableNamesAreReservedAndAllocationCanSkipNumbers() throws Exception {
        physicalTables.addAll(Set.of("a_1", "a_2", "a_3"));
        service.create(request("a", "默认", null, false), actor);
        var fourth = service.create(request("a", "四", null, true), actor);
        assertEquals("a_4", fourth.tableName());
        jdbc.update("DELETE FROM dii_replay_db_compare_registration WHERE id=?", fourth.id());
        assertEquals("a_5", service.create(request("a", "五", null, true), actor).tableName());
    }

    @Test
    void legacyDuplicateCreateStillRequiresExplicitAdditionalConfigurationIntent() throws Exception {
        var first = service.create(request("a", "默认", null, false), actor);
        assertThrows(IllegalStateException.class, () -> service.create(request("a", "重复", null, false), actor));
        assertEquals(first.id(), new ReplayDatabaseComparisonDao(jdbc).findBySchemaAndTable("base", "a").id());
    }

    @Test
    void allDtoCopyMethodsPreservePhysicalIdentityAndConfigurationName() throws Exception {
        service.create(request("a", "默认", null, false), actor);
        var second = service.create(request("a", "二", null, true), actor);
        var copied = second.withPartitionNum(2).withSampleLimit(20).withMaxPartitionNum(100)
                .withComparisonKey(second.comparisonKey()).withCustomSql(null);
        assertEquals("a", copied.sourceTableName());
        assertEquals("二", json.valueToTree(copied).path("configurationName").asText());
        assertEquals("a_1", copied.comparisonCode());
    }

    @Test
    void detailPreservesIdentityWithAndWithoutPersonnelMapping() throws Exception {
        service.create(request("a", "默认", null, false), actor);
        var second = service.create(request("a", "二", null, true), actor);
        for (boolean mapped : List.of(false, true)) {
            if (mapped) service.setTablePersonDao(new com.axonlink.ai.replay.persistence.ReplayDatabaseTablePersonDao(jdbc));
            var detail = service.detail(second.id());
            assertEquals("a", detail.sourceTableName());
            assertEquals("二", detail.configurationName());
            assertEquals(ReplayDbCompareMetadataStatus.VALID, detail.metadataValidation().status());
        }
    }

    @Test
    void physicalTableAddedAfterAliasGetsItsOwnRegistrationStateAndCode() throws Exception {
        service.create(request("a", "默认", null, false), actor);
        service.create(request("a", "二", null, true), actor);
        physicalTables.add("a_1");
        assertEquals("UNREGISTERED", service.registrationState("a_1").status());
        var actual = service.create(request("a_1", "另一张真实表", null, false), actor);
        assertEquals("a_1", actual.sourceTableName());
        assertEquals("a_1_1", actual.comparisonCode());
        assertEquals(actual.id(), service.registrationState("a_1").registrationId());
    }

    @Test
    void historicalIssueAndTableStateCodesAreNeverReallocated() throws Exception {
        jdbc.execute("CREATE TABLE dii_replay_database_issue(table_name VARCHAR(256))");
        jdbc.execute("CREATE TABLE dii_replay_database_issue_table_state(table_name VARCHAR(256))");
        jdbc.update("INSERT INTO dii_replay_database_issue VALUES('a_1')");
        jdbc.update("INSERT INTO dii_replay_database_issue_table_state VALUES('a_2')");
        service.create(request("a", "默认", null, false), actor);
        assertEquals("a_3", service.create(request("a", "三", null, true), actor).comparisonCode());
    }

    @Test
    void reregisterCanRenameConfigurationAndMissingNamePreservesIt() throws Exception {
        service.create(request("a", "默认", null, false), actor);
        var second = service.create(request("a", "二", null, true), actor);
        var deleted = service.delete(second.id(), new ReplayDbCompareDeleteRequest(second.version(), "删除"), actor);
        var node = json.valueToTree(new ReplayDbCompareReregisterRequest(deleted.version(), "存款组", "101", List.of("id", "amount"), "重新登记"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("configurationName", "新名称");
        var renamed = service.reregister(second.id(), json.treeToValue(node, ReplayDbCompareReregisterRequest.class), actor);
        assertEquals("新名称", renamed.configurationName());
        var deletedAgain = service.delete(renamed.id(), new ReplayDbCompareDeleteRequest(renamed.version(), "删除"), actor);
        assertEquals("新名称", service.reregister(renamed.id(), new ReplayDbCompareReregisterRequest(deletedAgain.version(), "存款组", "101", List.of("id", "amount"), "重新登记"), actor).configurationName());
    }

    @Test
    void concurrentCreatesAllocateDifferentSuffixes() throws Exception {
        service.create(request("a", "默认", null, false), actor);
        var futures = java.util.stream.IntStream.range(0, 4).mapToObj(index ->
                CompletableFuture.supplyAsync(() -> {
                    try { return service.create(request("a", "并发" + index, null, true), actor).tableName(); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                })).toList();
        Set<String> codes = new HashSet<>();
        for (var future : futures) codes.add(future.get(10, TimeUnit.SECONDS));
        assertEquals(Set.of("a_1", "a_2", "a_3", "a_4"), codes);
    }

    @Test
    void producesRealFrozenScriptsForIndependentConditionsAndLaterPackageReordering() throws Exception {
        service.create(conditionalRequest("条件一", "flag_a", false), actor);
        service.create(conditionalRequest("条件二", "flag_b", true), actor);
        var versions = new ReplayDatabaseComparisonVersionDao(jdbc);
        var dao = new ReplayDatabaseComparisonDao(jdbc);
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-26T12:00:00Z"), java.time.ZoneId.of("Asia/Shanghai"));
        var versionService = new ReplayDatabaseComparisonVersionService(service, metadata, dao, versions,
                new ReplayDatabaseComparisonConfigurationHasher(), new SysUserDao(jdbc), jdbc, clock);
        var first = versionService.generate("token", "token", actor);
        var snapshot = versions.findCompleteSnapshot(versions.findStoredVersion(first.versionNo()).id());
        var generator = new ReplayDatabaseComparisonConfigScriptGenerator();
        byte[] firstScript = generator.generateNew(first.versionNo(), snapshot).sqlContent();
        String firstText = new String(firstScript, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(List.of("a", "a_1"), snapshot.stream().map(ReplayDbCompareVersionTableItem::tableName).toList());
        assertTrue(snapshot.stream().allMatch(row -> row.sourceTableName().equals("a")));
        assertTrue(snapshot.stream().anyMatch(row -> row.whereSql().contains("flag_a")));
        assertTrue(snapshot.stream().anyMatch(row -> row.whereSql().contains("flag_b")));
        assertTrue(firstText.contains("source_table_name,configuration_name,configuration_scope"));

        physicalTables.add("a0");
        service.create(request("a0", "插入排序中间的配置", null, false), actor);
        var secondService = new ReplayDatabaseComparisonVersionService(service, metadata, dao, versions,
                new ReplayDatabaseComparisonConfigurationHasher(), new SysUserDao(jdbc), jdbc,
                java.time.Clock.offset(clock, java.time.Duration.ofSeconds(1)));
        var second = secondService.generate("token", "token", actor);
        var secondSnapshot = versions.findCompleteSnapshot(versions.findStoredVersion(second.versionNo()).id());
        byte[] reordered = generator.generateNew(second.versionNo(), secondSnapshot).sqlContent();
        assertEquals(3, secondSnapshot.size());
        var output = java.nio.file.Path.of(System.getProperty("axon.multi.fixture.dir", "target/multi-config-fixture"));
        java.nio.file.Files.createDirectories(output);
        java.nio.file.Files.write(output.resolve("axon-initial.sql"), firstScript);
        java.nio.file.Files.write(output.resolve("axon-reordered.sql"), reordered);
        java.nio.file.Files.writeString(output.resolve("axon-frozen-configurations.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    }

    private ReplayDbCompareSaveRequest conditionalRequest(String name, String column, boolean additional) throws Exception {
        var node = json.valueToTree(request("a", name, null, additional));
        var condition = new ReplayDbCompareConditionTree(ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(column, ReplayDbCompareConditionOperator.EQ, List.of(column.equals("flag_b") ? "2" : "1"))))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("whereCondition", json.valueToTree(condition));
        return json.treeToValue(node, ReplayDbCompareSaveRequest.class);
    }

    private ReplayDbCompareSaveRequest request(String table, String name, Long version, boolean additional) throws Exception {
        var node = json.createObjectNode().put("tableName", table).put("domainName", "存款组")
                .put("groupOwnerEmpNo", "101").put("configurationName", name)
                .put("createAdditionalConfiguration", additional);
        node.putArray("fieldNames").add("id").add("amount");
        if (version != null) node.put("version", version);
        return json.treeToValue(node, ReplayDbCompareSaveRequest.class);
    }
}
