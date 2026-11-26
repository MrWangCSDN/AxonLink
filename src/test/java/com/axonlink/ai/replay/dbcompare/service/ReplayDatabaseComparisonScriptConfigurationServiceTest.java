package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.*;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.*;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonScriptConfigurationDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplayDatabaseComparisonScriptConfigurationServiceTest {
    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonScriptConfigurationDao dao;
    private ReplayBaseMetadataService metadata;
    private ReplayDatabaseComparisonScriptConfigurationService service;
    private ReplayDatabaseComparisonProperties properties;
    private Driver driver;
    private Connection sourceConnection;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private static final String PASSWORD = "  测试密码  ";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final ReplayIssueOperator actor = new ReplayIssueOperator("operator", "操作人");

    @BeforeEach void setup() throws SQLException {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V95__replay_named_script_configuration.sql"), new ClassPathResource(
                "db/daoindex/V97__replay_script_config_datasource.sql")).execute(jdbc.getDataSource());
        dao = new ReplayDatabaseComparisonScriptConfigurationDao(jdbc);
        metadata = mock(ReplayBaseMetadataService.class);
        when(metadata.schemaName()).thenReturn("base_schema");
        when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("acct_master", snapshot("acct_master")));
        properties = new ReplayDatabaseComparisonProperties();
        driver = mock(Driver.class);
        sourceConnection = mock(Connection.class);
        when(driver.connect(anyString(), any(Properties.class))).thenReturn(sourceConnection);
        service = service(dao);
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    private ReplayDatabaseComparisonScriptConfigurationService service(
            ReplayDatabaseComparisonScriptConfigurationDao configurationDao) {
        return new ReplayDatabaseComparisonScriptConfigurationService(configurationDao, metadata,
                new ReplayDatabaseComparisonConfigScriptGenerator(), new ReplayDatabaseComparisonTablesExcelParser(),
                new ReplayScriptConfigDataSource(driver), new ReplayScriptConfigPasswordCipher(properties),
                Clock.fixed(Instant.parse("2026-11-24T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test void savesIndependentSourceMetadataWithoutExposingEitherPassword() throws Exception {
        properties.setScriptConfigPasswordKey(KEY);
        customMetadata("staging", "staging_id");
        customMetadata("public", "live_id");
        var staging = connection("DB.Example.Test", "Migration", "staging", "Reader", PASSWORD);
        var live = connection("db2.example.test", "Production", "public", "Reader", PASSWORD);

        Detail first = service.create(sourceRequest("迁移比对", null, staging), actor);
        Detail second = service.create(sourceRequest("线上比对", null, live), actor);

        assertEquals("db.example.test", first.connection().host());
        assertTrue(first.connection().passwordConfigured());
        assertEquals("staging_id", first.tables().get(0).fields().get(0).columnName());
        assertEquals("live_id", second.tables().get(0).fields().get(0).columnName());
        var stored = dao.find(first.id());
        assertTrue(stored.passwordCiphertext().startsWith("v1:"));
        assertNotEquals(stored.passwordCiphertext(), dao.find(second.id()).passwordCiphertext());
        assertFalse(stored.configJson().contains("connection"));
        assertFalse(stored.connectionJson().contains("password"));
        assertFalse(stored.toString().contains(stored.passwordCiphertext()));
        assertFalse(stored.toString().contains(PASSWORD));
        assertFalse(staging.toString().contains(PASSWORD));
        assertFalse(json.readTree(json.writeValueAsString(staging)).has("password"));
        var detail = json.readTree(json.writeValueAsString(service.detail(first.id())));
        assertFalse(detail.path("connection").has("password"));
        assertFalse(detail.toString().contains(stored.passwordCiphertext()));
        assertFalse(detail.toString().contains(PASSWORD));
        var sql = new String(service.download(first.id(), 1L).content(), StandardCharsets.UTF_8);
        assertTrue(sql.contains("select staging_id from acct_master"));
        assertFalse(sql.contains("live_id"));
        assertFalse(sql.contains(PASSWORD));
        assertFalse(sql.contains(stored.passwordCiphertext()));
        verify(driver).connect(eq("jdbc:postgresql://db.example.test:5432/Migration"), argThat(
                options -> PASSWORD.equals(options.getProperty("password")) && "Reader".equals(options.getProperty("user"))));
        verify(metadata, never()).inspectTables(anyCollection());
    }

    @Test void onlyReusesPasswordWithMatchingAuthenticationAndClearsItWhenSavingBase() throws Exception {
        properties.setScriptConfigPasswordKey(KEY);
        customMetadata("staging", "staging_id");
        customMetadata("public", "live_id");
        Detail first = service.create(sourceRequest("迁移比对", null,
                connection("Db.Example.Test", "Migration", "staging", "Reader", PASSWORD)), actor);
        String original = dao.find(first.id()).passwordCiphertext();
        // The database and role identify different PostgreSQL credentials when case changes.
        assertThrows(IllegalArgumentException.class, () -> service.update(first.id(), sourceRequest("迁移比对", 1L,
                connection("db.example.test", "migration", "public", "Reader", null)), actor));
        assertThrows(IllegalArgumentException.class, () -> service.update(first.id(), sourceRequest("迁移比对", 1L,
                connection("db.example.test", "Migration", "public", "reader", "")), actor));
        assertEquals(1L, service.detail(first.id()).revision());

        Detail updated = service.update(first.id(), sourceRequest("迁移比对", 1L,
                connection(" db.example.test ", "Migration", "public", "Reader", "")), actor);
        assertEquals("public", updated.connection().schema());
        assertEquals("live_id", updated.tables().get(0).fields().get(0).columnName());
        assertEquals(original, dao.find(first.id()).passwordCiphertext());

        Detail inherited = service.update(first.id(), sourceRequest("迁移比对", 2L, null), actor);
        assertEquals("public", inherited.connection().schema());
        assertEquals(original, dao.find(first.id()).passwordCiphertext());
        verify(driver, times(3)).connect(anyString(), argThat(options -> PASSWORD.equals(options.getProperty("password"))));

        Detail replaced = service.update(first.id(), sourceRequest("迁移比对", 3L,
                connection("db.example.test", "Migration", "public", "reader", "replacement")), actor);
        assertEquals("reader", replaced.connection().username());
        assertNotEquals(original, dao.find(first.id()).passwordCiphertext());

        var base = new ConnectionRequest("BASE", null, null, null, null, null, null);
        properties.setScriptConfigPasswordKey(null);
        Detail cleared = service.update(first.id(), sourceRequest("迁移比对", 4L, base), actor);
        assertEquals("BASE", cleared.connection().mode());
        assertNull(dao.find(first.id()).connectionJson());
        assertNull(dao.find(first.id()).passwordCiphertext());
    }

    @Test void missingKeyWrongKeyAndTamperingLeaveFrozenDownloadUsable() throws Exception {
        var custom = connection("db.example.test", "Migration", "public", "Reader", PASSWORD);
        for (String key : List.of("", "not-base64", "AQID")) {
            properties.setScriptConfigPasswordKey(key);
            assertEquals(503, error(() -> service.create(sourceRequest("迁移比对", null, custom), actor)));
            assertTrue(dao.list().isEmpty());
        }
        verifyNoInteractions(driver);

        properties.setScriptConfigPasswordKey(KEY);
        customMetadata("public", "custom_id");
        Detail saved = service.create(sourceRequest("迁移比对", null, custom), actor);
        var file = service.download(saved.id(), 1L);
        byte[] wrong = new byte[32];
        Arrays.fill(wrong, (byte) 1);
        properties.setScriptConfigPasswordKey(Base64.getEncoder().encodeToString(wrong));
        assertEquals(503, error(() -> service.update(saved.id(), sourceRequest("迁移比对", 1L, null), actor)));

        properties.setScriptConfigPasswordKey(KEY);
        jdbc.update("UPDATE dii_replay_db_compare_script_config SET password_ciphertext=? WHERE id=?",
                "v1:" + Base64.getEncoder().encodeToString(new byte[40]), saved.id());
        assertEquals(503, error(() -> service.update(saved.id(), sourceRequest("迁移比对", 1L, null), actor)));
        verify(driver, times(1)).connect(anyString(), any(Properties.class));
        assertEquals(1L, service.detail(saved.id()).revision());
        assertArrayEquals(file.content(), service.download(saved.id(), 1L).content());
    }

    @Test void sourceFailureClosesConnectionAndKeepsTheSelectedRevisionAndFrozenBytes() throws Exception {
        properties.setScriptConfigPasswordKey(KEY);
        customMetadata("public", "custom_id");
        Detail saved = service.create(sourceRequest("迁移比对", null,
                connection("db.example.test", "Migration", "public", "Reader", PASSWORD)), actor);
        var before = dao.find(saved.id());
        clearInvocations(sourceConnection, metadata, driver);
        when(metadata.inspectTables(eq(sourceConnection), eq("public"), anyCollection()))
                .thenThrow(new SQLException("driver diagnostic " + PASSWORD));

        var failure = assertThrows(ReplayDatabaseComparisonGenerationException.class, () ->
                service.update(saved.id(), sourceRequest("修改方案", 1L, null), actor));

        assertEquals(503, failure.status().value());
        assertEquals("SCRIPT_CONFIG_DATABASE_UNAVAILABLE", failure.errorCode());
        assertFalse(failure.toString().contains(PASSWORD));
        assertNull(failure.getCause());
        verify(sourceConnection).close();
        verify(metadata, never()).inspectTables(anyCollection());
        var after = dao.find(saved.id());
        assertEquals(before.revision(), after.revision());
        assertEquals(before.scriptSha256(), after.scriptSha256());
        assertEquals(before.passwordCiphertext(), after.passwordCiphertext());
        assertEquals(before.connectionJson(), after.connectionJson());
        assertArrayEquals(before.scriptContentGzip(), after.scriptContentGzip());
    }

    @Test void reusesOneReadOnlyConnectionAcrossBothPreviewBatches() throws Exception {
        properties.setScriptConfigPasswordKey(KEY);
        List<TableRequest> tables = new ArrayList<>();
        for (int i = 1; i <= 201; i++) tables.add(table("acct_" + i, null, List.of("undo_removed_ignore")));
        when(metadata.inspectTables(eq(sourceConnection), eq("staging"), anyCollection())).thenAnswer(invocation -> {
            Collection<String> names = invocation.getArgument(2);
            Map<String, ReplayBaseMetadataSnapshot> result = new LinkedHashMap<>();
            for (String name : names) result.put(name, snapshot(name));
            return result;
        });

        var preview = service.previewMetadata(new PreviewRequest(null, null,
                connection("db.example.test", "Migration", "staging", "Reader", PASSWORD), tables));

        assertEquals(201, preview.tables().size());
        assertTrue(preview.tables().get(200).ignoredFields().isEmpty());
        assertTrue(dao.list().isEmpty());
        verify(driver).connect(anyString(), argThat(options -> "10".equals(options.getProperty("connectTimeout"))
                && "30".equals(options.getProperty("socketTimeout"))));
        var queries = inOrder(sourceConnection, metadata);
        queries.verify(sourceConnection).setReadOnly(true);
        queries.verify(metadata).inspectTables(eq(sourceConnection), eq("staging"),
                argThat(names -> names.size() == 200 && names.contains("acct_200")));
        queries.verify(metadata).inspectTables(sourceConnection, "staging", List.of("acct_201"));
        queries.verify(sourceConnection).close();
    }

    @Test void readOnlySetupFailureClosesTheSourceAndSanitizesTheDriverError() throws Exception {
        properties.setScriptConfigPasswordKey(KEY);
        doThrow(new SQLException("driver diagnostic " + PASSWORD)).when(sourceConnection).setReadOnly(true);

        var failure = assertThrows(ReplayDatabaseComparisonGenerationException.class, () ->
                service.create(sourceRequest("迁移比对", null,
                        connection("db.example.test", "Migration", "public", "Reader", PASSWORD)), actor));

        assertEquals(503, failure.status().value());
        assertFalse(failure.toString().contains(PASSWORD));
        assertNull(failure.getCause());
        verify(sourceConnection).close();
        verifyNoInteractions(metadata);
        assertTrue(dao.list().isEmpty());
    }

    @Test void saveReturnsItsOwnCommittedRevisionEvenIfTheNextEditorWinsImmediately() {
        Detail initial = service.create(sourceRequest("迁移比对", null, null), actor);
        var observed = spy(dao);
        doAnswer(invocation -> {
            int written = (int) invocation.callRealMethod();
            assertEquals(1, written);
            service.update(initial.id(), sourceRequest("后来编辑", 2L, null), actor);
            return written;
        }).when(observed).update(eq(initial.id()), eq(1L), any());

        Detail result = service(observed).update(initial.id(), sourceRequest("本次编辑", 1L, null), actor);

        assertEquals(2L, result.revision());
        assertEquals("本次编辑", result.name());
        assertEquals("后来编辑", service.detail(initial.id()).name());
        assertEquals(3L, service.detail(initial.id()).revision());
    }

    private void customMetadata(String schema, String primaryKey) throws SQLException {
        when(metadata.inspectTables(sourceConnection, schema, List.of("acct_master")))
                .thenReturn(Map.of("acct_master", new ReplayBaseMetadataSnapshot(true, "acct_master", schema,
                        List.of(new ReplayBaseColumnOption(primaryKey, "主键", "bigint", 1, true, 1)))));
    }

    private ConnectionRequest connection(String host, String database, String schema, String username, String password) {
        return new ConnectionRequest("CUSTOM", host, null, database, schema, username, password);
    }

    private SaveRequest sourceRequest(String name, Long revision, ConnectionRequest connection) {
        return new SaveRequest(name, revision, List.of(), List.of(table("acct_master", null, List.of())), connection);
    }

    @Test void createsIndependentFrozenScriptWithProtectedPrimaryKeyAndExplicitMaximum() {
        var saved = service.create(request("方案一",null,List.of("id","audit","not_here"),
                table("acct_master",new BigDecimal("100"),List.of("memo"))),actor);
        assertNotNull(saved);
        assertEquals(1,saved.revision());
        assertEquals(2,saved.fieldCount());
        assertEquals(4,saved.tables().get(0).fields().size());
        assertEquals(List.of("id","audit","not_here"),saved.globalIgnoredFields());
        assertEquals(List.of("memo"),saved.tables().get(0).ignoredFields());
        var file = service.download(saved.id(),1L);
        String sql = new String(file.content(),StandardCharsets.UTF_8);
        assertTrue(sql.contains("bcomp_max_partition_num"));
        assertTrue(sql.contains("1,'HASH',100,'Owner',NULL,100)"));
        assertTrue(sql.contains("(select id,balance from acct_master) orig"));
        assertFalse(sql.contains("field_name") && sql.contains("'audit','1'"));
        assertEquals("方案一",service.list().items().get(0).name());
        reset(metadata);
        assertArrayEquals(file.content(),service.download(saved.id(),1L).content());
        assertEquals("方案一",service.detail(saved.id()).name());
        verifyNoInteractions(metadata);
    }

    @Test void updateRefreshesMetadataAndEnforcesNameAndRevisionConflicts() {
        var first = service.create(request(" First ",null,List.of(),table("acct_master",null,List.of())),actor);
        var second = service.create(request("Second",0L,List.of(),table("acct_master",null,List.of())),actor);
        assertEquals(64,first.tables().get(0).maxPartitionNum());
        assertEquals(409,error(() -> service.create(request("FIRST",null,List.of(),table("acct_master",null,List.of())),actor)));
        assertEquals(409,error(() -> service.update(second.id(),request("first",1L,List.of(),table("acct_master",null,List.of())),actor)));
        assertEquals(1,service.detail(second.id()).revision());
        var updated = service.update(first.id(),request("Renamed",1L,List.of("audit"),table("acct_master",new BigDecimal("256"),List.of())),actor);
        assertEquals(2,updated.revision());
        assertEquals(3,updated.fieldCount());
        assertEquals(409,error(() -> service.update(first.id(),request("stale",1L,List.of(),table("acct_master",null,List.of())),actor)));
        assertEquals(409,error(() -> service.download(first.id(),1L)));
        assertEquals("Renamed",service.detail(first.id()).name());
        assertEquals(404,error(() -> service.detail(999999)));
    }

    @Test void rejectsExplicitPrimaryKeyAndUnknownTableIgnoreFieldsWithoutSaving() {
        for (String field : List.of("ID","missing")) {
            assertEquals(422,error(() -> service.create(request("bad",null,List.of(),
                    table("acct_master",null,List.of(field))),actor)));
            assertTrue(service.list().items().isEmpty());
        }
    }

    @Test void rejectsFractionalOutOfRangeAndInvalidTableInputsAtomically() {
        for (String max : List.of("0","257","64.5")) {
            assertEquals(422,error(() -> service.create(request("bad",null,List.of(),
                    table("acct_master",new BigDecimal(max),List.of())),actor)));
        }
        assertEquals(422,error(() -> service.create(request("bad",null,List.of("id;delete"),table("acct_master",null,List.of())),actor)));
        assertEquals(422,error(() -> service.create(request("bad",null,List.of(),table("acct_master",null,List.of()),table("ACCT_MASTER",null,List.of())),actor)));
        assertEquals(422,error(() -> service.create(request("bad",null,List.of()),actor)));
        assertTrue(service.list().items().isEmpty());
    }

    @Test void rejectsMissingTablesMissingOrBrokenPrimaryKeysAndNeverFallsBackToUniqueIndex() {
        var noPrimary = new ReplayBaseMetadataSnapshot(true,"acct_master","账户",List.of(
                new ReplayBaseColumnOption("id","编号","bigint",1,false,null)),
                List.of(new ReplayBaseKeyOption("unique_id","UNIQUE_INDEX",List.of("id"))));
        var broken = new ReplayBaseMetadataSnapshot(true,"acct_master","账户",List.of(
                new ReplayBaseColumnOption("id","编号","bigint",1,true,2)));
        for (var snapshot : List.of(noPrimary,broken,new ReplayBaseMetadataSnapshot(false,"acct_master",null,List.of()))) {
            when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("acct_master",snapshot));
            assertEquals(422,error(() -> service.create(request("bad",null,List.of(),table("acct_master",null,List.of())),actor)));
        }
        assertTrue(service.list().items().isEmpty());
    }

    @Test void preservesPrimaryKeyOrderWhileComparisonFieldsFollowColumnOrder() {
        when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("acct_master",new ReplayBaseMetadataSnapshot(
                true,"acct_master","账户",List.of(
                new ReplayBaseColumnOption("id","编号","bigint",1,true,2),
                new ReplayBaseColumnOption("tenant","租户","varchar",2,true,1),
                new ReplayBaseColumnOption("audit","审计","varchar",3,false,null)))));
        var saved=service.create(request("composite",null,List.of("id","tenant","audit"),table("acct_master",null,List.of())),actor);
        assertEquals(2,saved.fieldCount());
        String sql=new String(service.download(saved.id(),1L).content(),StandardCharsets.UTF_8);
        assertTrue(sql.contains("(select id,tenant from acct_master) orig"));
        assertTrue(sql.contains("(1,'id','1','编号','1','1','1','0')"));
        assertTrue(sql.contains("(1,'tenant','1','租户','2','2','1','0')"));
    }

    @Test void enforcesMetadataBatchSizeAndRollsBackBeforeWritingOnBaseFailure() {
        var tables = new ArrayList<TableRequest>();
        for(int i=0;i<201;i++) tables.add(table("table_"+i,null,List.of()));
        when(metadata.inspectTables(anyCollection())).thenAnswer(invocation -> {
            Collection<String> names=invocation.getArgument(0);
            assertTrue(names.size()<=200);
            Map<String,ReplayBaseMetadataSnapshot> result=new LinkedHashMap<>();
            names.forEach(name->result.put(name,snapshot(name)));
            return result;
        });
        var saved=service.create(new SaveRequest("many",null,List.of(),tables),actor);
        assertEquals(201,saved.tableCount());
        verify(metadata,times(2)).inspectTables(anyCollection());
        when(metadata.inspectTables(anyCollection())).thenThrow(new ReplayBaseDatabaseUnavailableException("BASE unavailable"));
        assertThrows(ReplayBaseDatabaseUnavailableException.class,()->service.create(request("failed",null,List.of(),table("acct_master",null,List.of())),actor));
        assertEquals(1,service.list().items().size());
    }

    @Test void refusesCorruptedFrozenScriptsAndUsesSafeArtifactNames() {
        var saved=service.create(request("中文方案 / x",null,List.of(),table("acct_master",null,List.of())),actor);
        assertTrue(service.download(saved.id(),1L).fileName().matches("[A-Za-z0-9._-]+\\.sql"));
        jdbc.update("UPDATE dii_replay_db_compare_script_config SET script_content_gzip=? WHERE id=?",new byte[]{1,2,3},saved.id());
        assertEquals(500,error(()->service.download(saved.id(),1L)));
        assertThrows(IllegalArgumentException.class,()->service.create(request("bad\nDROP TABLE x",null,List.of(),table("acct_master",null,List.of())),actor));
        assertThrows(IllegalArgumentException.class,()->service.create(request("",null,List.of(),table("acct_master",null,List.of())),actor));
        assertThrows(IllegalArgumentException.class,()->service.create(request("no user",null,List.of(),table("acct_master",null,List.of())),null));
    }

    @Test void rejectsNulTextFromOwnersTableCommentsAndMetadataBeforePersisting() {
        for (var input : List.of(
                new TableRequest("acct_master","账户","存款组","Owner\0",null,List.of()),
                new TableRequest("acct_master","A\0B","存款组","Owner",null,List.of()))) {
            assertEquals(422,error(()->service.create(request("nul",null,List.of(),input),actor)));
        }
        var fallback = new TableRequest("acct_master",null,"存款组","Owner",null,List.of());
        when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("acct_master",
                new ReplayBaseMetadataSnapshot(true,"acct_master","A\0B",snapshot("acct_master").columns())));
        assertEquals(422,error(()->service.create(request("nul",null,List.of(),fallback),actor)));
        var columns = List.of(new ReplayBaseColumnOption("id","编号","bigint",1,true,1),
                new ReplayBaseColumnOption("balance","A\0B","numeric",2,false,null));
        when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("acct_master",
                new ReplayBaseMetadataSnapshot(true,"acct_master","账户",columns)));
        assertEquals(422,error(()->service.create(request("nul",null,List.of(),table("acct_master",null,List.of())),actor)));
        assertTrue(service.list().items().isEmpty());
    }

    @Test void namedOnlyHexLiteralsPreserveBackslashesAcrossBdAndMysqlImport() {
        var columns = List.of(new ReplayBaseColumnOption("id","编号","bigint",1,true,1),
                new ReplayBaseColumnOption("balance","A\\'B","numeric",2,false,null));
        when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("acct_master",
                new ReplayBaseMetadataSnapshot(true,"acct_master","账户",columns)));
        var input = new TableRequest("acct_master","A\\B","存款组","A\\nB",null,List.of());
        var saved = service.create(request("backslashes",null,List.of(),input),actor);
        String sql = new String(service.download(saved.id(),1L).content(),StandardCharsets.UTF_8);
        assertTrue(sql.contains("CONVERT(0x415c6e42 USING utf8mb4)"));
        assertTrue(sql.contains("CONVERT(0x415c42e6af94e5afb9 USING utf8mb4)"));
        assertTrue(sql.contains("CONVERT(0x415c2742 USING utf8mb4)"));
    }

    @Test void permitsOrdinaryMultilineDisplayTextWhileRejectingOnlyNul() {
        var input = new TableRequest("acct_master","账户\n明细","存款组","A\nB",null,List.of());
        var saved = service.create(request("multiline",null,List.of(),input),actor);
        String sql = new String(service.download(saved.id(),1L).content(),StandardCharsets.UTF_8);
        assertTrue(sql.contains("账户\n明细比对"));
        assertTrue(sql.contains("A\nB"));
    }

    private SaveRequest request(String name,Long revision,List<String> ignored,TableRequest...tables) {
        return new SaveRequest(name,revision,ignored,List.of(tables));
    }
    private TableRequest table(String name,BigDecimal max,List<String> ignored) {
        return new TableRequest(name,"账户","存款组","Owner",max,ignored);
    }
    private ReplayBaseMetadataSnapshot snapshot(String name) {
        return new ReplayBaseMetadataSnapshot(true,name,"账户",List.of(
                new ReplayBaseColumnOption("id","编号","bigint",1,true,1),
                new ReplayBaseColumnOption("balance","余额","numeric",2,false,null),
                new ReplayBaseColumnOption("audit","审计","varchar",3,false,null),
                new ReplayBaseColumnOption("memo","备注","varchar",4,false,null)));
    }
    private int error(Runnable work) {
        return assertThrows(ReplayDatabaseComparisonGenerationException.class,work::run).status().value();
    }
}
