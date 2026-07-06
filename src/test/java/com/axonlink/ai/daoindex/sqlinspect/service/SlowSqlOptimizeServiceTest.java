package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlOptimizeDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlowSqlOptimizeService —— 打标(工号/姓名/内容)/跨轮次未生效检测")
class SlowSqlOptimizeServiceTest {

    private JdbcTemplate jdbc;
    private DiiSlowSqlDao slowSqlDao;
    private DiiSlowSqlOptimizeDao optimizeDao;
    private SlowSqlOptimizeService service;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("optsvc_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dii_slow_sql ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, round VARCHAR(20) NOT NULL,"
                + "optimize_status VARCHAR(20) DEFAULT NULL, optimized_round VARCHAR(20) DEFAULT NULL,"
                + "reappeared_round VARCHAR(20) DEFAULT NULL)");
        jdbc.execute("CREATE TABLE dii_slow_sql_optimization ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,"
                + "optimized_round VARCHAR(20) NOT NULL, reappeared_round VARCHAR(20),"
                + "optimized_by VARCHAR(100), optimized_by_name VARCHAR(64), optimize_note VARCHAR(200),"
                + "optimized_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,"
                + "CONSTRAINT uk_svc_hash UNIQUE (service_name, abstract_hash))");
        slowSqlDao = new DiiSlowSqlDao(jdbc);
        optimizeDao = new DiiSlowSqlOptimizeDao(jdbc);
        service = new SlowSqlOptimizeService(optimizeDao, slowSqlDao);
    }

    private void insertRow(String svc, String hash, String round) {
        jdbc.update("INSERT INTO dii_slow_sql (service_name, abstract_hash, round) VALUES (?,?,?)", svc, hash, round);
    }
    private Map<String, Object> row(String svc, String hash, String round) {
        return jdbc.queryForMap("SELECT optimize_status, optimized_round, reappeared_round " +
                "FROM dii_slow_sql WHERE service_name=? AND abstract_hash=? AND round=?", svc, hash, round);
    }

    @Test @DisplayName("mark：写真身 OPTIMIZED，R0=MAX(round)，冗余列同步到所有轮次行")
    void markSetsOptimizedAndSyncs() {
        insertRow("svcA", "h1", "20260601-1");
        insertRow("svcA", "h1", "20260615-1");
        String r0 = service.mark("svcA", "h1", "1001", "张三", "加索引");
        assertEquals("20260615-1", r0);
        Map<String, Object> rec = optimizeDao.findByKey("svcA", "h1");
        assertEquals("OPTIMIZED", rec.get("status"));
        assertEquals("1001", rec.get("optimized_by"));
        assertEquals("张三", rec.get("optimized_by_name"));
        assertEquals("加索引", rec.get("optimize_note"));
        assertEquals("OPTIMIZED", row("svcA", "h1", "20260601-1").get("optimize_status"));
        assertEquals("20260615-1", row("svcA", "h1", "20260615-1").get("optimized_round"));
    }

    @Test @DisplayName("mark：SQL 不在池中 → 抛 IllegalArgumentException")
    void markThrowsWhenNotInPool() {
        assertThrows(IllegalArgumentException.class, () -> service.mark("nope", "hX", "1001", "张三", "加索引"));
    }

    @Test @DisplayName("导入更晚轮次又出现 → 翻 REGRESSED，reappearedHit=1，冗余列同步新轮行")
    void reappearFlipsRegressed() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");           // R0=20260615-1
        insertRow("svcA", "h1", "20260629-1");         // 模拟导入更晚一轮
        int hit = service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260629-1");
        assertEquals(1, hit);
        assertEquals("REGRESSED", optimizeDao.findByKey("svcA", "h1").get("status"));
        assertEquals("20260629-1", optimizeDao.findByKey("svcA", "h1").get("reappeared_round"));
        assertEquals("REGRESSED", row("svcA", "h1", "20260629-1").get("optimize_status"));
        assertEquals("20260629-1", row("svcA", "h1", "20260629-1").get("reappeared_round"));
    }

    @Test @DisplayName("同轮/更早轮重导 → 不误报，reappearedHit=0")
    void sameOrEarlierRoundNoRegress() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");
        assertEquals(0, service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260615-1"));
        insertRow("svcA", "h1", "20260601-1");
        assertEquals(0, service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260601-1"));
        assertEquals("OPTIMIZED", optimizeDao.findByKey("svcA", "h1").get("status"));
    }

    @Test @DisplayName("已 REGRESSED 再更晚轮出现 → 不重复计数，reappeared 取最新")
    void alreadyRegressedUpdatesLatestNoDoubleCount() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");
        insertRow("svcA", "h1", "20260629-1");
        service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260629-1");
        insertRow("svcA", "h1", "20260706-1");
        int hit = service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260706-1");
        assertEquals(0, hit);
        assertEquals("20260706-1", optimizeDao.findByKey("svcA", "h1").get("reappeared_round"));
    }

    @Test @DisplayName("本批不含该键 → 不动它（其他轮次导入不误伤）")
    void keyNotInBatchUntouched() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");
        int hit = service.inheritAndDetectReappearOnImport(Set.of("svcOTHER\nhZ"), "20260629-1");
        assertEquals(0, hit);
        assertEquals("OPTIMIZED", optimizeDao.findByKey("svcA", "h1").get("status"));
    }
}
