package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code dii_whitelist_application} 表读写 DAO（白名单审批工作流，V16）。
 *
 * <p>职责：纯持久化层——状态机和业务规则在 {@code WhitelistApplicationService}。
 *
 * <p>常用 lookup：
 * <ul>
 *   <li>{@link #findLatestActiveBySqlHash} —— 列表渲染按钮 4 态 / 入库继承</li>
 *   <li>{@link #findLatestActiveByNamedSql} —— 同上，nsql 包含模式</li>
 *   <li>{@link #listMyTodo} —— L1/L2 审批人待办视图</li>
 * </ul>
 *
 * <p>"活跃" = 状态 ∈ {PENDING_L1, PENDING_L2, REJECTED_L1, APPROVED}（含终态 APPROVED，
 * 让继承也能查到）；CANCELLED 不算活跃。
 */
@Repository
public class DiiWhitelistApplicationDao {

    private static final Logger log = LoggerFactory.getLogger(DiiWhitelistApplicationDao.class);

    // ─────────────────────────────────────────────────────────────────────────
    // 状态常量
    // ─────────────────────────────────────────────────────────────────────────
    public static final String STATUS_PENDING_L1 = "PENDING_L1";
    public static final String STATUS_PENDING_L2 = "PENDING_L2";
    public static final String STATUS_APPROVED   = "APPROVED";
    public static final String STATUS_REJECTED_L1 = "REJECTED_L1";
    public static final String STATUS_CANCELLED  = "CANCELLED";

    public static final String TARGET_HASH      = "HASH";
    public static final String TARGET_NAMED_SQL = "NAMED_SQL";
    public static final String TARGET_SLOW_SQL  = "SLOW_SQL";   // 慢SQL：按 abstract_hash 匹配

    private static final String INSERT_SQL =
            "INSERT INTO dii_whitelist_application (" +
            " target_type, sql_hash, named_sql, sql_kind_source, sql_text, " +
            " project_name, env, status, applicant, apply_reason, apply_at, " +
            " l1_approver, source_table, source_id) " +
            "VALUES (?, ?, ?, ?, ?,  ?, ?, ?, ?, ?, ?,  ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public DiiWhitelistApplicationDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /** 创建新申请，初态 {@code PENDING_L1}，返回新 id。 */
    public long create(String targetType, String sqlHash, String namedSql,
                       String kindSource, String sqlText,
                       String projectName, String env,
                       String applicant, String applyReason,
                       String l1Approver,
                       String sourceTable, long sourceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, targetType);
            ps.setString(i++, sqlHash);
            ps.setString(i++, namedSql);
            ps.setString(i++, kindSource == null ? "odb" : kindSource);
            ps.setString(i++, truncate(sqlText, 60_000));
            ps.setString(i++, projectName);
            ps.setString(i++, env);
            ps.setString(i++, STATUS_PENDING_L1);
            ps.setString(i++, applicant);
            ps.setString(i++, truncate(applyReason, 1000));
            ps.setTimestamp(i++, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(i++, l1Approver);
            ps.setString(i++, sourceTable);
            ps.setLong(i++, sourceId);
            return ps;
        }, kh);
        Number key = kh.getKey();
        long id = key == null ? -1L : key.longValue();
        log.info("[wl-dao] 新建申请 id={} targetType={} hash={} named={} applicant={} l1={}",
                id, targetType, sqlHash, namedSql, applicant, l1Approver);
        return id;
    }

    /** 按 id 查申请详情。 */
    public Map<String, Object> findById(long id) {
        try {
            return jdbc.queryForMap("SELECT * FROM dii_whitelist_application WHERE id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 找匹配 sql_hash 的「最新活跃」申请（含 APPROVED 终态，便于继承）。
     * CANCELLED 不算活跃，跳过。
     * <p>{@code target_type='HASH'} 必走 sql_hash 匹配。NAMED_SQL 类型的不在此找。
     */
    public Map<String, Object> findLatestActiveBySqlHash(String sqlHash) {
        if (sqlHash == null || sqlHash.isEmpty()) return null;
        try {
            return jdbc.queryForMap(
                    "SELECT * FROM dii_whitelist_application " +
                    " WHERE target_type = 'HASH' AND sql_hash = ? AND status <> 'CANCELLED' " +
                    " ORDER BY id DESC LIMIT 1",
                    sqlHash);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 找匹配 named_sql 的「最新活跃」申请，仅 {@code target_type='NAMED_SQL'}（即包含模式）。
     */
    public Map<String, Object> findLatestActiveByNamedSql(String namedSql) {
        if (namedSql == null || namedSql.isEmpty()) return null;
        try {
            return jdbc.queryForMap(
                    "SELECT * FROM dii_whitelist_application " +
                    " WHERE target_type = 'NAMED_SQL' AND named_sql = ? AND status <> 'CANCELLED' " +
                    " ORDER BY id DESC LIMIT 1",
                    namedSql);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 找匹配 (微服务, abstract_hash) 的「最新活跃」慢SQL申请，仅 {@code target_type='SLOW_SQL'}。
     * <p>慢SQL v2：白名单粒度 = (微服务+抽象SQL)，微服务存 {@code project_name} 列（复用，零 schema 改动）。
     * serviceName 为空时退化为仅按 hash 匹配（兼容历史无微服务的申请）。
     */
    public Map<String, Object> findLatestActiveBySlowHash(String abstractHash, String serviceName) {
        if (abstractHash == null || abstractHash.isEmpty()) return null;
        try {
            if (serviceName == null || serviceName.isBlank()) {
                return jdbc.queryForMap(
                        "SELECT * FROM dii_whitelist_application " +
                        " WHERE target_type = 'SLOW_SQL' AND sql_hash = ? AND status <> 'CANCELLED' " +
                        " ORDER BY id DESC LIMIT 1",
                        abstractHash);
            }
            return jdbc.queryForMap(
                    "SELECT * FROM dii_whitelist_application " +
                    " WHERE target_type = 'SLOW_SQL' AND sql_hash = ? AND project_name = ? AND status <> 'CANCELLED' " +
                    " ORDER BY id DESC LIMIT 1",
                    abstractHash, serviceName.trim());
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 取某 target_type 下全部「活跃」申请（status &lt;&gt; CANCELLED），按 id 升序。
     * <p>用于大批量导入时一次性拉全部 SLOW_SQL 申请到内存（数量有限，人工创建），
     * 避免逐 hash 反查的 N+1。按 id 升序便于调用方用 map put 覆盖取「最新（最高 id）」。
     */
    public List<Map<String, Object>> listActiveByTargetType(String targetType) {
        // v2：带回 project_name（慢SQL=微服务名），导入继承按 (微服务+hash) 匹配
        return jdbc.queryForList(
                "SELECT id, sql_hash, project_name, status FROM dii_whitelist_application " +
                " WHERE target_type = ? AND status <> 'CANCELLED' ORDER BY id ASC",
                targetType);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 状态机跃迁：每条 update SQL 都带 WHERE status = 期望初态，防并发
    // ─────────────────────────────────────────────────────────────────────────

    /** L1 通过：PENDING_L1 → PENDING_L2，写 L1 决策 + 指定 L2 审批人。 */
    public int l1Approve(long id, String l1Approver, String l1Opinion, String l2Approver) {
        return jdbc.update(
                "UPDATE dii_whitelist_application " +
                "   SET status = 'PENDING_L2', l1_decision = 'APPROVE', " +
                "       l1_opinion = ?, l1_at = NOW(), l2_approver = ? " +
                " WHERE id = ? AND status = 'PENDING_L1' AND l1_approver = ?",
                truncate(l1Opinion, 1000), l2Approver, id, l1Approver);
    }

    /** L1 退回：PENDING_L1 → REJECTED_L1，写 L1 决策 + 意见，退回申请人。 */
    public int l1Reject(long id, String l1Approver, String l1Opinion) {
        return jdbc.update(
                "UPDATE dii_whitelist_application " +
                "   SET status = 'REJECTED_L1', l1_decision = 'REJECT', " +
                "       l1_opinion = ?, l1_at = NOW() " +
                " WHERE id = ? AND status = 'PENDING_L1' AND l1_approver = ?",
                truncate(l1Opinion, 1000), id, l1Approver);
    }

    /** L2 通过：PENDING_L2 → APPROVED（终态），写 L2 决策 + 意见。 */
    public int l2Approve(long id, String l2Approver, String l2Opinion) {
        return jdbc.update(
                "UPDATE dii_whitelist_application " +
                "   SET status = 'APPROVED', l2_decision = 'APPROVE', " +
                "       l2_opinion = ?, l2_at = NOW() " +
                " WHERE id = ? AND status = 'PENDING_L2' AND l2_approver = ?",
                truncate(l2Opinion, 1000), id, l2Approver);
    }

    /** L2 退回：PENDING_L2 → PENDING_L1，写 L2 决策 + 意见，退回 L1 再审。 */
    public int l2Reject(long id, String l2Approver, String l2Opinion) {
        return jdbc.update(
                "UPDATE dii_whitelist_application " +
                "   SET status = 'PENDING_L1', l2_decision = 'REJECT', " +
                "       l2_opinion = ?, l2_at = NOW() " +
                " WHERE id = ? AND status = 'PENDING_L2' AND l2_approver = ?",
                truncate(l2Opinion, 1000), id, l2Approver);
    }

    /** 取消：仅在 PENDING_L1 / REJECTED_L1 + 申请人本人才能取消。 */
    public int cancel(long id, String applicant) {
        return jdbc.update(
                "UPDATE dii_whitelist_application " +
                "   SET status = 'CANCELLED' " +
                " WHERE id = ? AND applicant = ? AND status IN ('PENDING_L1', 'REJECTED_L1')",
                id, applicant);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 列表 / 计数
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 待办：按当前用户角色（L1 / L2 / 申请人）+ 状态过滤的列表。
     *
     * @param username 当前用户
     * @param role     {@code l1} = 我是 L1 审批人；{@code l2} = 我是 L2 审批人；
     *                 {@code applicant} = 我是申请人（看自己发起的） / {@code all} 不限
     */
    /**
     * 统计「当前用户需要处理的」申请数：
     * <ul>
     *   <li>我是 L1 审批人 + status=PENDING_L1</li>
     *   <li>我是 L2 审批人 + status=PENDING_L2</li>
     * </ul>
     * <p>已退回 / 已通过 / 已取消 / 不归我管 都不计——用户头像旁的小铃铛只展示"该我动手"的条数。
     */
    public long countMyPending(String username) {
        if (username == null || username.isBlank()) return 0L;
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_whitelist_application " +
                " WHERE (l1_approver = ? AND status = 'PENDING_L1') " +
                "    OR (l2_approver = ? AND status = 'PENDING_L2')",
                Long.class, username, username);
        return n == null ? 0L : n;
    }

    /**
     * 「该我审批」按类拆分：{@code total / slowSql / sqlInspect}（铃铛分流到对应页面用）。
     * slowSql=target_type='SLOW_SQL'；sqlInspect=其余(HASH/NAMED_SQL)。
     */
    public Map<String, Object> countMyPendingByCategory(String username) {
        if (username == null || username.isBlank()) {
            return Map.of("total", 0L, "slowSql", 0L, "sqlInspect", 0L);
        }
        return jdbc.queryForMap(
                "SELECT COUNT(*) AS total, " +
                "  SUM(CASE WHEN target_type='SLOW_SQL' THEN 1 ELSE 0 END) AS slowSql, " +
                "  SUM(CASE WHEN target_type<>'SLOW_SQL' THEN 1 ELSE 0 END) AS sqlInspect " +
                " FROM dii_whitelist_application " +
                " WHERE (l1_approver = ? AND status = 'PENDING_L1') " +
                "    OR (l2_approver = ? AND status = 'PENDING_L2')",
                username, username);
    }

    public List<Map<String, Object>> listMyTodo(String username, String role,
                                                String status, int limit, int offset) {
        StringBuilder sb = new StringBuilder(
                "SELECT * FROM dii_whitelist_application WHERE 1=1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (username != null && !username.isBlank()) {
            switch (role == null ? "" : role.toLowerCase()) {
                case "l1":
                    sb.append(" AND l1_approver = ? "); args.add(username); break;
                case "l2":
                    sb.append(" AND l2_approver = ? "); args.add(username); break;
                case "applicant":
                    sb.append(" AND applicant = ? "); args.add(username); break;
                default:
                    sb.append(" AND (applicant = ? OR l1_approver = ? OR l2_approver = ?) ");
                    args.add(username); args.add(username); args.add(username); break;
            }
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = ? "); args.add(status);
        }
        sb.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
