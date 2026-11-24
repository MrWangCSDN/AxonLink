package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.*;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.*;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonScriptConfigurationDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class ReplayDatabaseComparisonScriptConfigurationServiceTest {
    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonScriptConfigurationDao dao;
    private ReplayBaseMetadataService metadata;
    private ReplayDatabaseComparisonScriptConfigurationService service;
    private final ReplayIssueOperator actor = new ReplayIssueOperator("operator", "操作人");

    @BeforeEach void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V95__replay_named_script_configuration.sql")).execute(jdbc.getDataSource());
        dao = new ReplayDatabaseComparisonScriptConfigurationDao(jdbc);
        metadata = mock(ReplayBaseMetadataService.class);
        when(metadata.schemaName()).thenReturn("base_schema");
        when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("acct_master", snapshot("acct_master")));
        service = new ReplayDatabaseComparisonScriptConfigurationService(dao,metadata,
                new ReplayDatabaseComparisonConfigScriptGenerator(),new ReplayDatabaseComparisonTablesExcelParser(),
                Clock.fixed(Instant.parse("2026-11-24T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

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
