package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlOptimizeDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiWhitelistApplicationDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlowSqlOptimizeService —— 打标(工号/姓名/内容)/跨轮次未生效检测")
class SlowSqlOptimizeServiceTest {

    private JdbcTemplate jdbc;
    private DiiSlowSqlDao slowSqlDao;
    private DiiSlowSqlOptimizeDao optimizeDao;
    private DiiWhitelistApplicationDao whitelistDao;
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
                + "whitelist_status VARCHAR(20) DEFAULT NULL,"
                + "optimize_status VARCHAR(20) DEFAULT NULL, optimized_round VARCHAR(20) DEFAULT NULL,"
                + "reappeared_round VARCHAR(20) DEFAULT NULL)");
        jdbc.execute("CREATE TABLE dii_slow_sql_optimization ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,"
                + "optimized_round VARCHAR(20) NOT NULL, reappeared_round VARCHAR(20),"
                + "optimized_by VARCHAR(100), optimized_by_name VARCHAR(64), optimize_note VARCHAR(200),"
                + "optimized_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,"
                + "CONSTRAINT uk_svc_hash UNIQUE (service_name, abstract_hash))");
        jdbc.execute("CREATE TABLE dii_slow_sql_optimize_history ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, optimized_round VARCHAR(20) NOT NULL,"
                + "optimized_by VARCHAR(100), optimized_by_name VARCHAR(64), optimize_note VARCHAR(200),"
                + "optimized_at DATETIME NOT NULL, reappeared_round VARCHAR(20) DEFAULT NULL,"
                + "revoked_by VARCHAR(100) DEFAULT NULL, revoked_by_name VARCHAR(64) DEFAULT NULL,"
                + "revoked_at DATETIME DEFAULT NULL)");
        jdbc.execute("CREATE TABLE dii_whitelist_flow_history ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, application_id BIGINT NOT NULL,"
                + "target_type VARCHAR(16) NOT NULL, sql_hash VARCHAR(64), named_sql VARCHAR(500),"
                + "project_name VARCHAR(200), action VARCHAR(20) NOT NULL,"
                + "actor VARCHAR(100), actor_name VARCHAR(64), opinion VARCHAR(1000), created_at DATETIME NOT NULL)");
        slowSqlDao = new DiiSlowSqlDao(jdbc);
        optimizeDao = new DiiSlowSqlOptimizeDao(jdbc);
        whitelistDao = new DiiWhitelistApplicationDao(jdbc);
        service = new SlowSqlOptimizeService(optimizeDao, slowSqlDao, whitelistDao);
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

    @Test @DisplayName("优化路线：首标追加1条；仍OPTIMIZED时编辑=更新最新不追加")
    void historyAppendOnMarkAndUpdateOnEdit() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");
        assertEquals(1, service.listHistory("svcA", "h1").size());
        service.mark("svcA", "h1", "1002", "李四", "改写SQL");        // 仍 OPTIMIZED → 编辑
        List<Map<String, Object>> hist = service.listHistory("svcA", "h1");
        assertEquals(1, hist.size());
        assertEquals("李四", hist.get(0).get("optimized_by_name"));
        assertEquals("改写SQL", hist.get(0).get("optimize_note"));
    }

    @Test @DisplayName("优化路线：未生效留痕(reappeared回填) + 重标追加新尝试，旧痕迹不被覆盖")
    void historyKeepsFailedAttemptAndAppendsRemark() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");                 // 尝试1
        insertRow("svcA", "h1", "20260629-1");
        service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260629-1"); // 尝试1失败
        service.mark("svcA", "h1", "1002", "李四", "改写SQL去全表扫");         // 尝试2（REGRESSED后重标→追加）
        List<Map<String, Object>> hist = service.listHistory("svcA", "h1");
        assertEquals(2, hist.size());
        // 尝试1：张三，reappeared 留痕，内容不被覆盖
        assertEquals("张三", hist.get(0).get("optimized_by_name"));
        assertEquals("加索引", hist.get(0).get("optimize_note"));
        assertEquals("20260629-1", hist.get(0).get("reappeared_round"));
        // 尝试2：李四，暂未失效
        assertEquals("李四", hist.get(1).get("optimized_by_name"));
        assertNull(hist.get(1).get("reappeared_round"));
        // 尝试2 再失败 → 只回填最新一条
        insertRow("svcA", "h1", "20260706-1");
        service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260706-1");
        hist = service.listHistory("svcA", "h1");
        assertEquals("20260629-1", hist.get(0).get("reappeared_round"));   // 旧痕迹不动
        assertEquals("20260706-1", hist.get(1).get("reappeared_round"));   // 新尝试留痕
    }

    @Test @DisplayName("互斥：已在白名单流程中 → 新标记被拒")
    void markBlockedWhenWhitelistActive() {
        insertRow("svcA", "h1", "20260615-1");
        jdbc.update("UPDATE dii_slow_sql SET whitelist_status='PENDING_L1' WHERE service_name='svcA'");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.mark("svcA", "h1", "1001", "张三", "加索引"));
        assertTrue(e.getMessage().contains("互斥"));
        assertNull(optimizeDao.findByKey("svcA", "h1"));
    }

    @Test @DisplayName("撤销优化：路线盖撤销人/时间(不删) + 清冗余 + 删真身 → 回未处理，白名单可申请")
    void revokeClearsStateAndStampsAudit() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");
        service.revoke("svcA", "h1", "1002", "李四");
        assertNull(optimizeDao.findByKey("svcA", "h1"));                              // 真身已删
        assertNull(row("svcA", "h1", "20260615-1").get("optimize_status"));           // 冗余已清
        assertFalse(slowSqlDao.hasActiveOptimizeByServiceAndHash("svcA", "h1"));      // 白名单互斥解除
        List<Map<String, Object>> hist = service.listHistory("svcA", "h1");           // 路线留痕
        assertEquals(1, hist.size());
        assertEquals("张三", hist.get(0).get("optimized_by_name"));                   // 原打标人还在
        assertEquals("1002", hist.get(0).get("revoked_by"));                          // 撤销人
        assertEquals("李四", hist.get(0).get("revoked_by_name"));
        assertNotNull(hist.get(0).get("revoked_at"));                                 // 撤销时间
    }

    @Test @DisplayName("统一处理路径：优化(标记/撤销)+白名单(申请)按时间升序合并成一条时间线")
    void journeyMergesBothTrailsInTimeOrder() throws Exception {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");        // t1 标记
        Thread.sleep(1100);                                          // DATETIME 秒级精度，隔开时间
        service.revoke("svcA", "h1", "1002", "李四");                // t2 撤销
        Thread.sleep(1100);
        whitelistDao.insertFlowEvent(9L, "SLOW_SQL", "h1", null, "svcA",
                "APPLY", "user1", "王山河", "优化撤销，转投白名单");   // t3 申请白名单
        List<Map<String, Object>> j = service.journey("svcA", "h1");
        assertEquals(3, j.size());
        assertEquals("MARK",   j.get(0).get("action"));
        assertEquals("REVOKE", j.get(1).get("action"));
        assertEquals("APPLY",  j.get(2).get("action"));
        assertEquals("OPTIMIZE",  j.get(0).get("kind"));
        assertEquals("WHITELIST", j.get(2).get("kind"));
        assertEquals("李四", j.get(1).get("actorName"));
    }

    @Test @DisplayName("撤销优化：未标记 → 抛 IllegalArgumentException")
    void revokeThrowsWhenNotMarked() {
        assertThrows(IllegalArgumentException.class, () -> service.revoke("nope", "hX", "1002", "李四"));
    }

    @Test @DisplayName("互斥：未生效后已转投白名单 → 重新标记(新尝试)被拒")
    void remarkBlockedWhenRegressedAndWhitelistActive() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");
        insertRow("svcA", "h1", "20260629-1");
        service.inheritAndDetectReappearOnImport(Set.of("svcA\nh1"), "20260629-1");   // → REGRESSED
        jdbc.update("UPDATE dii_slow_sql SET whitelist_status='PENDING_L1' WHERE service_name='svcA'"); // 转投白名单
        assertThrows(IllegalArgumentException.class,
                () -> service.mark("svcA", "h1", "1002", "李四", "改写SQL"));
        assertEquals(1, service.listHistory("svcA", "h1").size());   // 未追加新尝试
    }

    @Test @DisplayName("互斥：已有优化记录（存量双态）→ 编辑内容放行")
    void markAllowedForExistingOptimizeDespiteWhitelist() {
        insertRow("svcA", "h1", "20260615-1");
        service.mark("svcA", "h1", "1001", "张三", "加索引");                    // 先入优化路线
        jdbc.update("UPDATE dii_slow_sql SET whitelist_status='PENDING_L1' WHERE service_name='svcA'"); // 模拟存量双态
        service.mark("svcA", "h1", "1001", "张三", "改写SQL");                   // 编辑不被拦
        assertEquals("改写SQL", optimizeDao.findByKey("svcA", "h1").get("optimize_note"));
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
