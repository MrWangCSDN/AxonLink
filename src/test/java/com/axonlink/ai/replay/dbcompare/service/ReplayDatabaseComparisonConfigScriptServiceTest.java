package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionScriptDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonConfigScriptServiceTest {

    private static final String VERSION_NO = "20260914-172637";

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonVersionDao versionDao;
    private ReplayDatabaseComparisonVersionScriptDao scriptDao;
    private ReplayDatabaseComparisonConfigScriptService service;
    private long versionId;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V64__drop_replay_database_comparison_generation_lock.sql"),
                new ClassPathResource("db/daoindex/V65__dii_replay_database_comparison_version_script.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"),
                new ClassPathResource("db/daoindex/V70__replay_db_compare_scope.sql"),
                new ClassPathResource("db/daoindex/V71__replay_db_compare_ordering_primary_key_snapshot.sql"),
                new ClassPathResource("db/daoindex/V73__replay_db_compare_partition_num.sql"))
                .execute(jdbc.getDataSource());
        versionDao = new ReplayDatabaseComparisonVersionDao(jdbc);
        scriptDao = new ReplayDatabaseComparisonVersionScriptDao(jdbc);
        service = new ReplayDatabaseComparisonConfigScriptService(
                versionDao,
                scriptDao,
                new ReplayDatabaseComparisonConfigScriptGenerator(),
                Clock.fixed(Instant.parse("2026-09-14T10:35:26.318Z"), ZoneId.of("Asia/Shanghai")));
        versionId = versionDao.insertVersion(
                VERSION_NO, "a".repeat(64), 1, 2,
                "c-version", "版本生成人", LocalDateTime.of(2026, 9, 14, 17, 26, 37));
        ReplayDbCompareRegistration registration = registration("acct_master", List.of(
                field("acct_no", "账号", true, 1),
                field("customer_no", "客户号", false, 2)));
        long tableId = versionDao.insertVersionTable(versionId, registration);
        versionDao.insertVersionFields(tableId, registration.fields());
    }

    @Test
    void returnsGeneratedFalseForAnExistingVersionWithoutAScript() {
        ReplayDbCompareConfigScriptStatus status = service.status(VERSION_NO);

        assertFalse(status.generated());
        assertEquals(null, status.fileName());
    }

    @Test
    void generatesPersistsAndReportsTheCompleteVersionScript() {
        ReplayDatabaseComparisonConfigScriptService.ScriptFile file =
                service.generate(VERSION_NO, new ReplayIssueOperator("A012345", "张三"));
        ReplayDbCompareConfigScriptStatus status = service.status(VERSION_NO);
        ReplayDatabaseComparisonVersionScriptDao.StoredScript stored =
                scriptDao.findByVersionId(versionId);
        String sql = new String(file.content(), StandardCharsets.UTF_8);

        assertEquals("replay-db-compare-config-20260914-172637.sql", file.fileName());
        assertTrue(sql.contains("acct_no,customer_no"));
        assertTrue(status.generated());
        assertEquals(file.fileName(), status.fileName());
        assertEquals(file.sha256(), status.sha256());
        assertEquals((long) file.content().length, status.scriptSize());
        assertEquals("A012345", status.generatedBy());
        assertEquals("张三", status.generatedName());
        assertEquals(LocalDateTime.of(2026, 9, 14, 18, 35, 26, 318_000_000), status.generatedAt());
        assertNotNull(stored);
        assertEquals("GZIP", stored.compression());
        assertEquals(stored.scriptContent().length, stored.compressedSize());
    }

    @Test
    void generatesWhereOrderLimitAndAliasesFromThePersistedVersionSnapshot() {
        jdbc.update("UPDATE dii_replay_db_compare_version_table "
                        + "SET where_sql=?,compare_limit=? WHERE version_id=?",
                "(status_cd = '1')", 1000L, versionId);
        jdbc.update("UPDATE dii_replay_db_compare_version_field SET primary_key_order=1 "
                        + "WHERE version_table_id=(SELECT id FROM dii_replay_db_compare_version_table "
                        + "WHERE version_id=?) AND column_name='acct_no'",
                versionId);

        ReplayDatabaseComparisonConfigScriptService.ScriptFile file =
                service.generate(VERSION_NO, new ReplayIssueOperator("A012345", "张三"));
        String sql = new String(file.content(), StandardCharsets.UTF_8);

        assertTrue(sql.contains("'(select acct_no,customer_no from acct_master "
                + "where (status_cd = ''1'') order by acct_no limit 1000) orig'"));
        assertTrue(sql.contains("'(select acct_no,customer_no from acct_master "
                + "where (status_cd = ''1'') order by acct_no limit 1000) dest'"));
    }

    @Test
    void returnsTheStoredArtifactWhenTheVersionSnapshotLaterChanges() {
        ReplayDatabaseComparisonConfigScriptService.ScriptFile first =
                service.generate(VERSION_NO, new ReplayIssueOperator("A012345", "张三"));
        jdbc.update("UPDATE dii_replay_db_compare_version_table SET partition_num=32 WHERE version_id=?", versionId);
        ReplayDbCompareRegistration later = registration("later_table", List.of(
                field("later_id", "后加字段", true, 1)));
        long tableId = versionDao.insertVersionTable(versionId, later);
        versionDao.insertVersionFields(tableId, later.fields());

        ReplayDatabaseComparisonConfigScriptService.ScriptFile second =
                service.generate(VERSION_NO, new ReplayIssueOperator("B000001", "李四"));

        assertArrayEquals(first.content(), second.content());
        assertArrayEquals(first.content(), service.download(VERSION_NO).content());
        assertEquals(first.sha256(), second.sha256());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_version_script WHERE version_id=?",
                Integer.class, versionId));
        assertEquals("A012345", service.status(VERSION_NO).generatedBy());
    }

    @Test
    void concurrentGenerationReturnsOneImmutableArtifact() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ReplayDatabaseComparisonConfigScriptService.ScriptFile> first = executor.submit(() -> {
                start.await();
                return service.generate(VERSION_NO, new ReplayIssueOperator("A012345", "张三"));
            });
            Future<ReplayDatabaseComparisonConfigScriptService.ScriptFile> second = executor.submit(() -> {
                start.await();
                return service.generate(VERSION_NO, new ReplayIssueOperator("B000001", "李四"));
            });
            start.countDown();

            assertArrayEquals(first.get().content(), second.get().content());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dii_replay_db_compare_version_script WHERE version_id=?",
                    Integer.class, versionId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsMissingVersionsAndDownloadsBeforeGeneration() {
        ReplayDatabaseComparisonGenerationException missingVersion = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.status("missing"));
        ReplayDatabaseComparisonGenerationException missingScript = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.download(VERSION_NO));

        assertEquals("VERSION_NOT_FOUND", missingVersion.errorCode());
        assertEquals("CONFIG_SCRIPT_NOT_GENERATED", missingScript.errorCode());
    }

    @Test
    void refusesInvalidGzipAndHashMismatchWithoutRegeneration() {
        service.generate(VERSION_NO, new ReplayIssueOperator("A012345", "张三"));
        byte[] originalCompressed = scriptDao.findByVersionId(versionId).scriptContent();
        jdbc.update("UPDATE dii_replay_db_compare_version_script SET script_content=? WHERE version_id=?",
                new byte[]{1, 2, 3}, versionId);

        ReplayDatabaseComparisonGenerationException invalidGzip = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.download(VERSION_NO));
        assertEquals("CONFIG_SCRIPT_CORRUPTED", invalidGzip.errorCode());

        jdbc.update("UPDATE dii_replay_db_compare_version_script SET script_content=?,script_sha256=? "
                        + "WHERE version_id=?",
                originalCompressed, "f".repeat(64), versionId);
        ReplayDatabaseComparisonGenerationException invalidHash = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> service.download(VERSION_NO));
        assertEquals("CONFIG_SCRIPT_CORRUPTED", invalidHash.errorCode());
    }

    private ReplayDbCompareRegistration registration(
            String tableName,
            List<ReplayDbCompareField> fields) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 14, 17, 0);
        return new ReplayDbCompareRegistration(
                (long) Math.abs(tableName.hashCode()), "base_schema", tableName, "账户主表", "存款组",
                "100", "zhangsan", "张三", "200", "李经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 1L,
                "100", "张三", now, "100", "张三", now, fields);
    }

    private ReplayDbCompareField field(
            String name,
            String comment,
            boolean primaryKey,
            int comparisonOrder) {
        return new ReplayDbCompareField(name, comment, comparisonOrder, primaryKey, comparisonOrder);
    }
}
