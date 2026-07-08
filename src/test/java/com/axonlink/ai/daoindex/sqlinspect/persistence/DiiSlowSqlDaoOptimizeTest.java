package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiiSlowSqlDao —— 优化冗余列同步 / R0 查询 / optimizeStatus 过滤")
class DiiSlowSqlDaoOptimizeTest {

    private JdbcTemplate jdbc;
    private DiiSlowSqlDao dao;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("sq_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dii_slow_sql ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "service_name VARCHAR(128) NOT NULL, domain VARCHAR(16), biz_type VARCHAR(16),"
                + "abstract_sql VARCHAR(1000), abstract_hash CHAR(64) NOT NULL,"
                + "max_time_cost_ms BIGINT, max_time_cost_raw VARCHAR(32), exec_params VARCHAR(1000),"
                + "source_location VARCHAR(512), exec_count INT, round VARCHAR(20) NOT NULL, repeat_rounds VARCHAR(1000),"
                + "whitelist_app_id BIGINT, whitelist_status VARCHAR(20), is_whitelist TINYINT DEFAULT 0,"
                + "optimize_status VARCHAR(20) DEFAULT NULL, optimized_round VARCHAR(20) DEFAULT NULL,"
                + "reappeared_round VARCHAR(20) DEFAULT NULL)");
        // listAggregated/listAggregatedAll 现 LEFT JOIN 真身表取 优化人/内容 → 建表以供 JOIN
        jdbc.execute("CREATE TABLE dii_slow_sql_optimization ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,"
                + "optimized_round VARCHAR(20) NOT NULL, reappeared_round VARCHAR(20),"
                + "optimized_by VARCHAR(100), optimized_by_name VARCHAR(64), optimize_note VARCHAR(200),"
                + "optimized_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,"
                + "CONSTRAINT uk_svc_hash UNIQUE (service_name, abstract_hash))");
        dao = new DiiSlowSqlDao(jdbc);
    }

    private void insertRow(String svc, String hash, String round, long cost) {
        jdbc.update("INSERT INTO dii_slow_sql (service_name, abstract_hash, round, max_time_cost_ms, exec_count) " +
                "VALUES (?,?,?,?,1)", svc, hash, round, cost);
    }

    @Test @DisplayName("maxRoundByServiceAndHash：取该键最新轮次；无行返回 null")
    void maxRound() {
        insertRow("svcA", "h1", "20260601-1", 100);
        insertRow("svcA", "h1", "20260615-1", 200);
        assertEquals("20260615-1", dao.maxRoundByServiceAndHash("svcA", "h1"));
        assertNull(dao.maxRoundByServiceAndHash("nope", "hX"));
    }

    @Test @DisplayName("syncOptimizeByServiceAndHash：把 3 冗余列盖到该键所有轮次行")
    void syncSpansAllRounds() {
        insertRow("svcA", "h1", "20260601-1", 100);
        insertRow("svcA", "h1", "20260615-1", 200);
        int n = dao.syncOptimizeByServiceAndHash("svcA", "h1", "REGRESSED", "20260601-1", "20260615-1");
        assertEquals(2, n);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT optimize_status, optimized_round, reappeared_round FROM dii_slow_sql WHERE service_name='svcA'");
        assertTrue(rows.stream().allMatch(r -> "REGRESSED".equals(r.get("optimize_status"))
                && "20260601-1".equals(r.get("optimized_round"))
                && "20260615-1".equals(r.get("reappeared_round"))));
    }

    @Test @DisplayName("clearOptimizeByServiceAndHash：清空 3 冗余列")
    void clearNullsColumns() {
        insertRow("svcA", "h1", "20260601-1", 100);
        dao.syncOptimizeByServiceAndHash("svcA", "h1", "OPTIMIZED", "20260601-1", null);
        assertEquals(1, dao.clearOptimizeByServiceAndHash("svcA", "h1"));
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT optimize_status, optimized_round, reappeared_round FROM dii_slow_sql WHERE service_name='svcA'");
        assertNull(r.get("optimize_status"));
        assertNull(r.get("optimized_round"));
        assertNull(r.get("reappeared_round"));
    }

    @Test @DisplayName("互斥判定：hasWhitelist / hasOptimize 按冗余列命中任意轮次行")
    void mutexPredicates() {
        insertRow("svcA", "h1", "20260601-1", 100);
        assertFalse(dao.hasWhitelistByServiceAndHash("svcA", "h1"));
        assertFalse(dao.hasOptimizeByServiceAndHash("svcA", "h1"));
        jdbc.update("UPDATE dii_slow_sql SET whitelist_status='PENDING_L1' WHERE service_name='svcA'");
        assertTrue(dao.hasWhitelistByServiceAndHash("svcA", "h1"));
        dao.syncOptimizeByServiceAndHash("svcA", "h1", "OPTIMIZED", "20260601-1", null);
        assertTrue(dao.hasOptimizeByServiceAndHash("svcA", "h1"));
    }

    @Test @DisplayName("listAggregated 按 optimizeStatus 过滤：REGRESSED / NONE(未处理)")
    void listFiltersByOptimizeStatus() {
        insertRow("svcA", "h1", "20260601-1", 300);
        insertRow("svcB", "h2", "20260601-1", 200);
        dao.syncOptimizeByServiceAndHash("svcA", "h1", "REGRESSED", "20260601-1", "20260615-1");
        List<Map<String, Object>> regressed = dao.listAggregated(
                null, null, null, null, "REGRESSED", null, null, 50, 0);
        assertEquals(1, regressed.size());
        assertEquals("svcA", regressed.get(0).get("service_name"));
        List<Map<String, Object>> none = dao.listAggregated(
                null, null, null, null, "NONE", null, null, 50, 0);
        assertEquals(1, none.size());
        assertEquals("svcB", none.get(0).get("service_name"));
        assertTrue(regressed.get(0).containsKey("optimize_status"));
        assertTrue(regressed.get(0).containsKey("optimized_round"));
        assertTrue(regressed.get(0).containsKey("reappeared_round"));
    }
}
