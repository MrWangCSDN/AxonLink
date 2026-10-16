package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.*;
import com.axonlink.ai.replay.dbcompare.persistence.*;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** End-to-end acceptance through real result persistence, version gate and both SQL formats. */
class ReplayComparisonKeyAcceptanceTest {
    private final ReplayIssueOperator actor=new ReplayIssueOperator("creator","创建人");
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-15T02:00:00Z"),ZoneId.of("Asia/Shanghai"));
    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonDao registrations;
    private ReplayDatabaseComparisonVersionDao versions;
    private ReplayDatabaseComparisonService service;
    private ReplayBaseMetadataService metadata;
    private SysUserDao users;
    private ReplayBaseMetadataSnapshot snapshot;
    private final List<ReplayBaseColumnOption> columns=List.of(
        new ReplayBaseColumnOption("id","ID","bigint",1,true,1),
        new ReplayBaseColumnOption("account_no","账户","varchar",2,false,null),
        new ReplayBaseColumnOption("sequence_no","序号","bigint",3,false,null),
        new ReplayBaseColumnOption("payload","内容","varchar",4,false,null));
    private final List<ReplayBaseKeyOption> keys=List.of(
        new ReplayBaseKeyOption("acct_pkey","PRIMARY_KEY",List.of("id")),
        new ReplayBaseKeyOption("uk_account_sequence","UNIQUE_INDEX",List.of("sequence_no","account_no")));

    @BeforeEach void setup() {
        jdbc=ReplayIssueTestFixtures.newJdbc();
        var migrations=List.of("V62__dii_replay_database_comparison_fields.sql","V63__dii_replay_database_comparison_versions.sql",
            "V66__replay_db_compare_person_username_snapshots.sql","V70__replay_db_compare_scope.sql",
            "V71__replay_db_compare_ordering_primary_key_snapshot.sql","V74__replay_db_compare_partition_num.sql",
            "V75__replay_db_compare_sample_limit.sql","V81__replay_db_compare_comparison_key.sql",
            "V82__replay_database_table_person.sql");
        new ResourceDatabasePopulator(migrations.stream().map(name->new ClassPathResource("db/daoindex/"+name)).toArray(ClassPathResource[]::new)).execute(jdbc.getDataSource());
        ReplayDatabaseComparisonServiceTest.createUsers(jdbc);
        users=new SysUserDao(jdbc);registrations=new ReplayDatabaseComparisonDao(jdbc);versions=new ReplayDatabaseComparisonVersionDao(jdbc);
        metadata=mock(ReplayBaseMetadataService.class);
        snapshot=new ReplayBaseMetadataSnapshot(true,"acct_master","账户",columns,keys);
        when(metadata.schemaName()).thenReturn("base_schema");
        when(metadata.inspectTable(any())).thenAnswer(call->snapshot);
        when(metadata.inspectTables(any())).thenAnswer(call->Map.of("acct_master",snapshot));
        when(metadata.requireTableWithColumns(any(),any())).thenAnswer(call->new ReplayBaseValidatedTable(
            "base_schema","acct_master","账户",snapshot.columns(),snapshot.columns().stream().filter(ReplayBaseColumnOption::primaryKey).toList(),snapshot.columns(),snapshot.keyOptions()));
        service=new ReplayDatabaseComparisonService(metadata,registrations,versions,users,new ReplayDatabaseComparisonAuditDiff(),jdbc,clock);
    }
    private ReplayDbCompareSaveRequest request(Long version,String key,List<String> names) throws Exception {
        var values=new LinkedHashMap<String,Object>();values.put("tableName","acct_master");values.put("domainName","存款组");
        values.put("groupOwnerEmpNo","101");values.put("fieldNames",names);values.put("version",version);values.put("compareLimit",10);
        if(key!=null)values.put("comparisonKeyName",key);
        return new ObjectMapper().convertValue(values,ReplayDbCompareSaveRequest.class);
    }
    private ReplayDbCompareVersionSummary generate(int seconds) {
        return new ReplayDatabaseComparisonVersionService(service,metadata,registrations,versions,
            new ReplayDatabaseComparisonConfigurationHasher(),users,jdbc,Clock.offset(clock,Duration.ofSeconds(seconds)))
            .generate("secret","secret",actor);
    }
    @Test void switchingToUniqueSurvivesReloadSyncVersionsAndBothScriptsWithoutReaddingOldPrimary() throws Exception {
        var created=service.create(request(null,null,List.of("id","account_no","sequence_no","payload")),actor);
        assertEquals("acct_pkey",created.comparisonKey().indexName());
        var first=generate(0);
        var changed=service.update(created.id(),request(created.version(),"uk_account_sequence",List.of("account_no","sequence_no","payload")),actor);
        service.synchronizePrimaryKeys();
        var loaded=registrations.findByIdIncludingDeleted(changed.id());
        assertEquals("uk_account_sequence",loaded.comparisonKey().indexName());
        assertFalse(loaded.fields().stream().anyMatch(field->field.columnName().equals("id")));
        assertEquals(List.of("sequence_no","account_no"),loaded.orderingPrimaryKeyNames());
        var second=generate(1);
        assertNotEquals(first.versionNo(),second.versionNo());
        var oldSnapshot=versions.findCompleteSnapshot(versions.findStoredVersion(first.versionNo()).id()).get(0);
        var newSnapshot=versions.findCompleteSnapshot(versions.findStoredVersion(second.versionNo()).id()).get(0);
        assertEquals("acct_pkey",oldSnapshot.comparisonKey().indexName());
        assertEquals("uk_account_sequence",newSnapshot.comparisonKey().indexName());
        var generator=new ReplayDatabaseComparisonConfigScriptGenerator();
        for(var generated:List.of(generator.generate("v",List.of(newSnapshot)),generator.generateNew("v",List.of(newSnapshot)))) {
            String sql=new String(generated.sqlContent(),StandardCharsets.UTF_8);
            assertTrue(sql.toLowerCase(Locale.ROOT).contains("order by sequence_no,account_no"),sql);
            assertTrue(sql.contains("(1,'account_no','1','账户','1','1','1','0')"),sql);
            assertTrue(sql.contains("(1,'sequence_no','1','序号','2','2','1','0')"),sql);
            assertTrue(sql.contains("(1,'payload','1','内容','3','3','0','0')"),sql);
        }
        snapshot=new ReplayBaseMetadataSnapshot(true,"acct_master","账户",columns,List.of(keys.get(0)));
        assertThrows(RuntimeException.class,()->generate(2));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_version",Integer.class));
    }
    @Test void fieldChangeChecksFollowSelectedUniqueIndexOnly() throws Exception {
        var created=service.create(request(null,"uk_account_sequence",List.of("account_no","sequence_no","payload")),actor);
        var changedColumns=List.of(
            new ReplayBaseColumnOption("id","ID","bigint",1,false,null),
            new ReplayBaseColumnOption("account_no","账户","varchar",2,false,null),
            new ReplayBaseColumnOption("sequence_no","序号","bigint",3,false,null),
            new ReplayBaseColumnOption("payload","内容","varchar",4,true,1));
        snapshot=new ReplayBaseMetadataSnapshot(true,"acct_master","账户",changedColumns,List.of(
            new ReplayBaseKeyOption("payload_pkey","PRIMARY_KEY",List.of("payload")),
            new ReplayBaseKeyOption("uk_other","UNIQUE_INDEX",List.of("id")),
            keys.get(1)));

        var detail=service.detail(created.id());
        assertEquals(ReplayDbCompareMetadataStatus.VALID,detail.metadataValidation().status());
        assertFalse(detail.metadataValidation().orderingPrimaryKeyChanged());
        assertTrue(detail.metadataValidation().missingPrimaryKeyNames().isEmpty());
        var listed=service.search(ReplayDbCompareQuery.empty(0,20)).items().get(0);
        assertEquals(ReplayDbCompareMetadataStatus.VALID,listed.metadataValidation().status());
        assertEquals(0,service.synchronizePrimaryKeys().addedFieldCount());
        assertEquals(List.of("account_no","sequence_no","payload"),registrations.findByIdIncludingDeleted(created.id())
            .fields().stream().map(ReplayDbCompareField::columnName).toList());
        var versionService=new ReplayDatabaseComparisonVersionService(service,metadata,registrations,versions,
            new ReplayDatabaseComparisonConfigurationHasher(),users,jdbc,clock);
        assertTrue(versionService.validateAll(List.of(detail)).valid());

        snapshot=new ReplayBaseMetadataSnapshot(true,"acct_master","账户",changedColumns.stream()
            .filter(column->!column.columnName().equals("id")).toList(),List.of(keys.get(1)));
        assertEquals(ReplayDbCompareMetadataStatus.VALID,service.detail(created.id()).metadataValidation().status());
        assertTrue(versionService.validateAll(List.of(detail)).valid());

        snapshot=new ReplayBaseMetadataSnapshot(true,"acct_master","账户",changedColumns,List.of(
            new ReplayBaseKeyOption("payload_pkey","PRIMARY_KEY",List.of("payload")),
            new ReplayBaseKeyOption("uk_account_sequence","UNIQUE_INDEX",List.of("account_no","sequence_no"))));
        var selectedChanged=service.detail(created.id()).metadataValidation();
        assertTrue(selectedChanged.orderingPrimaryKeyChanged());
        assertEquals(List.of("sequence_no","account_no"),selectedChanged.savedOrderingPrimaryKeyNames());
        assertEquals(List.of("account_no","sequence_no"),selectedChanged.currentOrderingPrimaryKeyNames());
        assertFalse(versionService.validateAll(List.of(detail)).valid());
    }
    @Test void tableWithoutPrimaryDefaultsToStableFirstUniqueAndExportsCompleteCompositeKey() throws Exception {
        var noPrimary=columns.stream().map(c->new ReplayBaseColumnOption(c.columnName(),c.columnComment(),c.dataType(),c.ordinalPosition(),false,null)).toList();
        snapshot=new ReplayBaseMetadataSnapshot(true,"acct_master","账户",noPrimary,List.of(
            new ReplayBaseKeyOption("uk_z","UNIQUE_INDEX",List.of("payload")),
            new ReplayBaseKeyOption("uk_a","UNIQUE_INDEX",List.of("sequence_no","account_no"))));
        var created=service.create(request(null,null,List.of("account_no","sequence_no","payload")),actor);
        assertEquals("uk_a",created.comparisonKey().indexName());
        var version=generate(0);
        var stored=versions.findCompleteSnapshot(versions.findStoredVersion(version.versionNo()).id()).get(0);
        assertTrue(stored.fields().stream().noneMatch(ReplayDbCompareVersionField::primaryKey));
        String sql=new String(new ReplayDatabaseComparisonConfigScriptGenerator().generateNew("v",List.of(stored)).sqlContent(),StandardCharsets.UTF_8);
        assertTrue(sql.toLowerCase(Locale.ROOT).contains("order by sequence_no,account_no"));
    }
}
