package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayDatabaseComparisonVersionScriptDaoTest {

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonVersionScriptDao dao;
    private long versionId;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V64__drop_replay_database_comparison_generation_lock.sql"),
                new ClassPathResource("db/daoindex/V65__dii_replay_database_comparison_version_script.sql"))
                .execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO dii_replay_db_compare_version "
                        + "(version_no,configuration_hash,table_count,field_count,"
                        + "generated_by,generated_name,generated_at) VALUES (?,?,?,?,?,?,?)",
                "20260914-172637", "a".repeat(64), 1, 2,
                "c-version", "版本生成人", LocalDateTime.of(2026, 9, 14, 17, 26, 37));
        versionId = jdbc.queryForObject(
                "SELECT id FROM dii_replay_db_compare_version WHERE version_no=?",
                Long.class, "20260914-172637");
        dao = new ReplayDatabaseComparisonVersionScriptDao(jdbc);
    }

    @Test
    void insertsAndReadsCompressedScriptByVersionId() {
        byte[] compressed = new byte[]{31, -117, 8, 0, 1, 2, 3};
        LocalDateTime generatedAt = LocalDateTime.of(2026, 9, 14, 18, 35, 26, 318_000_000);

        long id = dao.insert(new ReplayDatabaseComparisonVersionScriptDao.NewScript(
                versionId,
                "replay-db-compare-config-20260914-172637.sql",
                "GZIP",
                compressed,
                "b".repeat(64),
                19_503_514L,
                compressed.length,
                "A012345",
                "张三",
                generatedAt));

        ReplayDatabaseComparisonVersionScriptDao.StoredScript stored = dao.findByVersionId(versionId);

        assertEquals(id, stored.id());
        assertEquals(versionId, stored.versionId());
        assertEquals("replay-db-compare-config-20260914-172637.sql", stored.fileName());
        assertEquals("GZIP", stored.compression());
        assertArrayEquals(compressed, stored.scriptContent());
        assertEquals("b".repeat(64), stored.scriptSha256());
        assertEquals(19_503_514L, stored.scriptSize());
        assertEquals(compressed.length, stored.compressedSize());
        assertEquals("A012345", stored.generatedBy());
        assertEquals("张三", stored.generatedName());
        assertEquals(generatedAt, stored.generatedAt());
    }

    @Test
    void returnsNullWhenVersionHasNoScript() {
        assertNull(dao.findByVersionId(versionId));
    }

    @Test
    void rejectsASecondScriptForTheSameVersion() {
        ReplayDatabaseComparisonVersionScriptDao.NewScript script =
                new ReplayDatabaseComparisonVersionScriptDao.NewScript(
                        versionId,
                        "replay-db-compare-config-20260914-172637.sql",
                        "GZIP",
                        new byte[]{1, 2, 3},
                        "c".repeat(64),
                        10L,
                        3L,
                        "A012345",
                        "张三",
                        LocalDateTime.of(2026, 9, 14, 18, 35, 26));

        dao.insert(script);

        assertThrows(DuplicateKeyException.class, () -> dao.insert(script));
    }
}
