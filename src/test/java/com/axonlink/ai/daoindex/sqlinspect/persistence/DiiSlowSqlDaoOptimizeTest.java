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
        // 列表查询 LEFT JOIN 白名单申请表（发起人/当前审批人列）
        jdbc.execute("CREATE TABLE dii_whitelist_application ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, target_type VARCHAR(16), sql_hash VARCHAR(64),"
                + "named_sql VARCHAR(500), project_name VARCHAR(200), status VARCHAR(20),"
                + "applicant VARCHAR(100), applicant_name VARCHAR(64),"
                + "l1_approver VARCHAR(100), l2_approver VARCHAR(100))");
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

    @Test @DisplayName("互斥判定：hasWhitelist 任意状态命中；hasActiveOptimize 仅 OPTIMIZED 算（REGRESSED 路线重开）")
    void mutexPredicates() {
        insertRow("svcA", "h1", "20260601-1", 100);
        assertFalse(dao.hasWhitelistByServiceAndHash("svcA", "h1"));
        assertFalse(dao.hasActiveOptimizeByServiceAndHash("svcA", "h1"));
        jdbc.update("UPDATE dii_slow_sql SET whitelist_status='PENDING_L1' WHERE service_name='svcA'");
        assertTrue(dao.hasWhitelistByServiceAndHash("svcA", "h1"));
        dao.syncOptimizeByServiceAndHash("svcA", "h1", "OPTIMIZED", "20260601-1", null);
        assertTrue(dao.hasActiveOptimizeByServiceAndHash("svcA", "h1"));
        // 未生效 → 不再算「生效中」，白名单路线重新开放
        dao.syncOptimizeByServiceAndHash("svcA", "h1", "REGRESSED", "20260601-1", "20260615-1");
        assertFalse(dao.hasActiveOptimizeByServiceAndHash("svcA", "h1"));
    }

    @Test @DisplayName("发起人列/过滤：白名单在途→申请人；优化→打标人；按姓名或工号模糊")
    void initiatorColumnAndFilter() {
        // 行A：白名单在途（申请人 王山河/c-wangsh8）
        insertRow("svcA", "h1", "20260701-1", 300);
        jdbc.update("INSERT INTO dii_whitelist_application (target_type, sql_hash, project_name, status, applicant, applicant_name, l1_approver) "
                + "VALUES ('SLOW_SQL','h1','svcA','PENDING_L1','c-wangsh8','王山河','l1u')");
        jdbc.update("UPDATE dii_slow_sql SET whitelist_status='PENDING_L1', whitelist_app_id=1 WHERE service_name='svcA'");
        // 行B：已标优化（打标人 李四/1002）
        insertRow("svcB", "h2", "20260701-1", 200);
        jdbc.update("INSERT INTO dii_slow_sql_optimization (service_name, abstract_hash, status, optimized_round, optimized_by, optimized_by_name, optimized_at, updated_at) "
                + "VALUES ('svcB','h2','OPTIMIZED','20260701-1','1002','李四',NOW(),NOW())");
        dao.syncOptimizeByServiceAndHash("svcB", "h2", "OPTIMIZED", "20260701-1", null);

        List<Map<String, Object>> all = dao.listAggregated(null, null, null, null, null, null, null, null, null, null, 50, 0);
        Map<String, Object> a = all.stream().filter(r -> "svcA".equals(r.get("service_name"))).findFirst().orElseThrow();
        Map<String, Object> b = all.stream().filter(r -> "svcB".equals(r.get("service_name"))).findFirst().orElseThrow();
        assertEquals("c-wangsh8", a.get("initiator"));
        assertEquals("王山河", a.get("initiator_name"));
        assertEquals("l1u", a.get("current_approver"));          // 待一级 → 一级审批人
        assertEquals("1002", b.get("initiator"));
        assertEquals("李四", b.get("initiator_name"));
        assertNull(b.get("current_approver"));                    // 优化行无审批人

        // 模糊过滤：姓名 / 工号 各命中对应行
        assertEquals(1, dao.listAggregated(null, null, null, null, null, "王山", null, null, null, null, 50, 0).size());
        assertEquals(1, dao.listAggregated(null, null, null, null, null, "1002", null, null, null, null, 50, 0).size());
        assertEquals(1, dao.countAggregated(null, null, null, null, null, "李四", null, null, null, null));

        // 当前审批人过滤：工号 LIKE 命中；姓名经 yml 预匹配成 username 集命中；优化行不命中
        assertEquals(1, dao.listAggregated(null, null, null, null, null, null, "l1u", null, null, null, 50, 0).size());
        assertEquals(1, dao.listAggregated(null, null, null, null, null, null, null, java.util.List.of("l1u"), null, null, 50, 0).size());
        assertEquals(0, dao.listAggregated(null, null, null, null, null, null, "nobody", null, null, null, 50, 0).size());
    }

    @Test @DisplayName("listAggregated 按 optimizeStatus 过滤：REGRESSED / NONE(未处理)")
    void listFiltersByOptimizeStatus() {
        insertRow("svcA", "h1", "20260601-1", 300);
        insertRow("svcB", "h2", "20260601-1", 200);
        dao.syncOptimizeByServiceAndHash("svcA", "h1", "REGRESSED", "20260601-1", "20260615-1");
        List<Map<String, Object>> regressed = dao.listAggregated(null, null, null, null, "REGRESSED", null, null, null, null, null, 50, 0);
        assertEquals(1, regressed.size());
        assertEquals("svcA", regressed.get(0).get("service_name"));
        List<Map<String, Object>> none = dao.listAggregated(null, null, null, null, "NONE", null, null, null, null, null, 50, 0);
        assertEquals(1, none.size());
        assertEquals("svcB", none.get(0).get("service_name"));
        assertTrue(regressed.get(0).containsKey("optimize_status"));
        assertTrue(regressed.get(0).containsKey("optimized_round"));
        assertTrue(regressed.get(0).containsKey("reappeared_round"));
    }
}
