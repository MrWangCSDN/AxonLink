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

/** 白名单：二级退回(REJECTED_L2)跃迁 + 流转路径读写（H2 MODE=MySQL）。 */
@DisplayName("DiiWhitelistApplicationDao —— REJECTED_L2 跃迁 + 流转路径")
class DiiWhitelistFlowTest {

    private JdbcTemplate jdbc;
    private DiiWhitelistApplicationDao dao;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("wlf_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dii_whitelist_application ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "target_type VARCHAR(16) NOT NULL, sql_hash VARCHAR(64), named_sql VARCHAR(500),"
                + "sql_kind_source VARCHAR(16), sql_text TEXT, project_name VARCHAR(200), env VARCHAR(16),"
                + "status VARCHAR(20) NOT NULL, applicant VARCHAR(100) NOT NULL, apply_reason VARCHAR(1000),"
                + "apply_at DATETIME, l1_approver VARCHAR(100), l1_decision VARCHAR(10),"
                + "l1_opinion VARCHAR(1000), l1_at DATETIME, l2_approver VARCHAR(100),"
                + "l2_decision VARCHAR(10), l2_opinion VARCHAR(1000), l2_at DATETIME,"
                + "source_table VARCHAR(20), source_id BIGINT)");
        jdbc.execute("CREATE TABLE dii_whitelist_flow_history ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, application_id BIGINT NOT NULL,"
                + "target_type VARCHAR(16) NOT NULL, sql_hash VARCHAR(64), named_sql VARCHAR(500),"
                + "project_name VARCHAR(200), action VARCHAR(20) NOT NULL,"
                + "actor VARCHAR(100), actor_name VARCHAR(64), opinion VARCHAR(1000), created_at DATETIME NOT NULL)");
        // 铃铛计数的低频过滤按 whitelist_app_id 关联慢SQL行 → 建最小行表
        jdbc.execute("CREATE TABLE dii_slow_sql ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "whitelist_app_id BIGINT, exec_count INT)");
        dao = new DiiWhitelistApplicationDao(jdbc);
    }

    private long insertApp(String status) {
        jdbc.update("INSERT INTO dii_whitelist_application "
                + "(target_type, sql_hash, project_name, status, applicant, apply_at, l1_approver, l2_approver) "
                + "VALUES ('SLOW_SQL','h1','svcA',?,'user1',NOW(),'l1u','l2u')", status);
        return jdbc.queryForObject("SELECT MAX(id) FROM dii_whitelist_application", Long.class);
    }

    @Test @DisplayName("二级退回：PENDING_L2 → REJECTED_L2（状态可见，不再悄悄回 PENDING_L1）")
    void l2RejectGoesToRejectedL2() {
        long id = insertApp("PENDING_L2");
        assertEquals(1, dao.l2Reject(id, "l2u", "补充说明不足，退回"));
        assertEquals("REJECTED_L2", dao.findById(id).get("status"));
    }

    @Test @DisplayName("二级退回后一级可再审：REJECTED_L2 上 l1Approve/l1Reject 均可作用")
    void l1CanActOnRejectedL2() {
        long id = insertApp("REJECTED_L2");
        assertEquals(1, dao.l1Approve(id, "l1u", "已补充，再次通过", "l2u"));
        assertEquals("PENDING_L2", dao.findById(id).get("status"));

        long id2 = insertApp("REJECTED_L2");
        assertEquals(1, dao.l1Reject(id2, "l1u", "确实不符合，退回申请人"));
        assertEquals("REJECTED_L1", dao.findById(id2).get("status"));
    }

    @Test @DisplayName("流转路径：追加多条事件，按发生序返回")
    void flowInsertAndList() {
        long id = insertApp("PENDING_L1");
        dao.insertFlowEvent(id, "SLOW_SQL", "h1", null, "svcA", "APPLY", "user1", "王山河", "该SQL为批量对账必需");
        dao.insertFlowEvent(id, "SLOW_SQL", "h1", null, "svcA", "L1_APPROVE", "l1u", "邓仁伟", "同意");
        dao.insertFlowEvent(id, "SLOW_SQL", "h1", null, "svcA", "L2_REJECT", "l2u", "吴林宁", "理由不充分，退回");
        List<Map<String, Object>> flow = dao.listFlowBySlowKey("h1", "svcA");
        assertEquals(3, flow.size());
        assertEquals("APPLY", flow.get(0).get("action"));
        assertEquals("L2_REJECT", flow.get(2).get("action"));
        assertEquals("吴林宁", flow.get(2).get("actor_name"));
        assertNotNull(flow.get(0).get("created_at"));
    }

    @Test @DisplayName("流转路径：project_name 为空的历史事件按 hash 兜底也能查到")
    void flowLegacyBlankProjectMatched() {
        long id = insertApp("PENDING_L1");
        dao.insertFlowEvent(id, "SLOW_SQL", "h1", null, null, "APPLY", "user1", null, "历史申请");
        assertEquals(1, dao.listFlowBySlowKey("h1", "svcA").size());
    }

    // ── 铃铛待办计数：与慢SQL列表低频过滤同口径（2026-07-16）──────────────

    private void linkRow(long appId, int execCount) {
        jdbc.update("INSERT INTO dii_slow_sql (whitelist_app_id, exec_count) VALUES (?,?)", appId, execCount);
    }

    private long insertHashApp(String status, String l1) {
        jdbc.update("INSERT INTO dii_whitelist_application "
                + "(target_type, sql_hash, status, applicant, apply_at, l1_approver) "
                + "VALUES ('HASH','hx',?,'user1',NOW(),?)", status, l1);
        return jdbc.queryForObject("SELECT MAX(id) FROM dii_whitelist_application", Long.class);
    }

    @Test @DisplayName("待办计数忽略低频过滤：审批入口全量——低频行的在途申请照常计数（2026-07-16 修正）")
    void todoCountIgnoresLowFreqFilter() {
        long a1 = insertApp("PENDING_L1");   // 关联行次数3（低频）→ 仍计数（审批必须能完成）
        linkRow(a1, 3);
        long a2 = insertApp("PENDING_L1");   // 关联行次数6 → 计
        linkRow(a2, 6);
        insertApp("PENDING_L1");             // SLOW_SQL 无关联行 → 仍计数
        insertHashApp("PENDING_L1", "l1u");  // HASH 类 → 计

        assertEquals(4, dao.countMyPending("l1u"), "铃铛计数=该我审批全量，不受低频隐藏影响");
        Map<String, Object> c = dao.countMyPendingByCategory("l1u");
        assertEquals(4L, ((Number) c.get("total")).longValue());
        assertEquals(3L, ((Number) c.get("slowSql")).longValue());
        assertEquals(1L, ((Number) c.get("sqlInspect")).longValue());
    }
}
