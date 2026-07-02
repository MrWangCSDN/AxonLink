package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiiSlowSqlOptimizeDao —— 真身表 CRUD + 未生效标记")
class DiiSlowSqlOptimizeDaoTest {

    private JdbcTemplate jdbc;
    private DiiSlowSqlOptimizeDao dao;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("opt_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dii_slow_sql_optimization ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL,"
                + "status VARCHAR(20) NOT NULL,"
                + "optimized_round VARCHAR(20) NOT NULL,"
                + "reappeared_round VARCHAR(20),"
                + "optimized_by VARCHAR(100),"
                + "optimized_at DATETIME NOT NULL,"
                + "updated_at DATETIME NOT NULL,"
                + "CONSTRAINT uk_svc_hash UNIQUE (service_name, abstract_hash))");
        dao = new DiiSlowSqlOptimizeDao(jdbc);
    }

    @Test @DisplayName("upsert 首次插入 OPTIMIZED")
    void upsertInsertsOptimized() {
        dao.upsertOptimized("svcA", "h1", "20260601-1", "alice", LocalDateTime.now());
        Map<String, Object> rec = dao.findByKey("svcA", "h1");
        assertNotNull(rec);
        assertEquals("OPTIMIZED", rec.get("status"));
        assertEquals("20260601-1", rec.get("optimized_round"));
        assertNull(rec.get("reappeared_round"));
    }

    @Test @DisplayName("upsert 重复键：刷新 R0 并清 reappeared，仍只有一行")
    void upsertOnDuplicateRefreshes() {
        dao.upsertOptimized("svcA", "h1", "20260601-1", "alice", LocalDateTime.now());
        dao.flagReappeared("svcA", "h1", "20260701-1", LocalDateTime.now());
        dao.upsertOptimized("svcA", "h1", "20260701-1", "bob", LocalDateTime.now());
        Map<String, Object> rec = dao.findByKey("svcA", "h1");
        assertEquals("OPTIMIZED", rec.get("status"));
        assertEquals("20260701-1", rec.get("optimized_round"));
        assertNull(rec.get("reappeared_round"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_slow_sql_optimization", Integer.class));
    }

    @Test @DisplayName("flagReappeared：翻 REGRESSED 记 reappeared，取更晚者，不回退")
    void flagReappearedTakesLatest() {
        dao.upsertOptimized("svcA", "h1", "20260601-1", "alice", LocalDateTime.now());
        assertEquals(1, dao.flagReappeared("svcA", "h1", "20260701-1", LocalDateTime.now()));
        Map<String, Object> rec = dao.findByKey("svcA", "h1");
        assertEquals("REGRESSED", rec.get("status"));
        assertEquals("20260701-1", rec.get("reappeared_round"));
        dao.flagReappeared("svcA", "h1", "20260801-1", LocalDateTime.now());
        assertEquals("20260801-1", dao.findByKey("svcA", "h1").get("reappeared_round"));
        dao.flagReappeared("svcA", "h1", "20260705-1", LocalDateTime.now());   // 更早不回退
        assertEquals("20260801-1", dao.findByKey("svcA", "h1").get("reappeared_round"));
    }

    @Test @DisplayName("flagReappeared：R <= R0 时不匹配（防误报），返回 0")
    void flagReappearedGuardsByRound() {
        dao.upsertOptimized("svcA", "h1", "20260601-1", "alice", LocalDateTime.now());
        assertEquals(0, dao.flagReappeared("svcA", "h1", "20260601-1", LocalDateTime.now())); // 同轮
        assertEquals(0, dao.flagReappeared("svcA", "h1", "20260501-1", LocalDateTime.now())); // 更早
        assertEquals("OPTIMIZED", dao.findByKey("svcA", "h1").get("status"));
    }

    @Test @DisplayName("delete 物删")
    void deleteRemoves() {
        dao.upsertOptimized("svcA", "h1", "20260601-1", "alice", LocalDateTime.now());
        assertEquals(1, dao.delete("svcA", "h1"));
        assertNull(dao.findByKey("svcA", "h1"));
    }

    @Test @DisplayName("listAll 返回全部记录")
    void listAllReturnsAll() {
        dao.upsertOptimized("svcA", "h1", "20260601-1", "a", LocalDateTime.now());
        dao.upsertOptimized("svcB", "h2", "20260601-1", "a", LocalDateTime.now());
        List<Map<String, Object>> all = dao.listAll();
        assertEquals(2, all.size());
    }

    @Test @DisplayName("findByKey 不存在返回 null")
    void findByKeyMissingReturnsNull() {
        assertNull(dao.findByKey("nope", "hX"));
    }
}
