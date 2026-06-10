package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 概览仪表盘聚合查询。
 *
 * <p>四块数据来源（全部基于 dii_analysis_task + dii_analysis_item）：
 * <ol>
 *   <li><b>byDomain</b> — 最新 DONE 任务下，按业务领域聚合"巡检 SQL 数 / EXPLAIN 报错数 / LLM 整改数"</li>
 *   <li><b>ratingByDomain</b> — 同任务下，按业务领域聚合"整改分布"两档（报错 / 待整改）</li>
 *   <li><b>trend7d</b> — 最近 7 个 DONE 任务的"优/良/差/报错"四档趋势（按任务一行，保持四档不变）</li>
 *   <li><b>elapsed7d</b> — 最近 7 个 DONE 任务的执行时长（秒）</li>
 * </ol>
 *
 * <p><b>领域口径</b>（与 DaoIndexController#mapDomainLabel 一致）：
 * project_name 包含 dept-bcc → 存款；loan-bcc → 贷款；comm-bcc → 公共；
 * sett-bcc → 结算；其余 → 其他。
 *
 * <p><b>"报错"与"整改"的关系：</b>互斥取数 — 任何 explain_error 非空都算"报错"，
 * 不再参与"待整改"统计，避免重复计数。trend7d 仍按旧"优/良/差/报错"四档（未改）。
 *
 * <p><b>性能兜底</b>：trend7d 用相关子查询，每个任务跑一次聚合；
 * 如实测慢可加索引 {@code idx_item_task_rating ON dii_analysis_item(task_id, overall_rating)}。
 */
@Repository
public class DiiDashboardDao {

    private final JdbcTemplate jdbc;

    public DiiDashboardDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /**
     * 按领域聚合"总数 / EXPLAIN 报错 / LLM 整改"，限定到给定 task。
     * 用于第一块柱状图。
     *
     * @param taskId 任务 id；为 null 返回空列表
     * @return 每行含 domain / total / explain_err / llm_fix
     */
    public List<Map<String, Object>> aggregateByDomain(Long taskId) {
        if (taskId == null) return Collections.emptyList();
        // total 口径：与 aggregateRatingByDomain 的"优/良/差/报错"四档完全对齐，
        // 即 total ≡ 报错 + 优 + 良 + 差。排除 overall_rating='NOT_APPLICABLE'（不适用，
        // 如 INSERT VALUES 无需索引评级）和 overall_rating IS NULL（未评级/LLM 未跑）。
        // 这样概览仪表盘"巡检 SQL 总数"与"评级分布"两图恒等闭合，不再出现总数对不上。
        // v6：报错/需整改互斥剔除活跃白名单；新增 白名单申请中/已申请白名单 两列。
        String sql = ""
                + "SELECT " + DOMAIN_CASE + " AS domain, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' THEN 1 "
                + "                WHEN overall_rating IN ('EXCELLENT','GOOD','POOR') THEN 1 "
                + "                ELSE 0 END) AS total, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' AND " + plain("") + " "
                + "                THEN 1 ELSE 0 END) AS explain_err, "
                + "       SUM(CASE WHEN (explain_error IS NULL OR explain_error='') "
                // v7：去掉 llm_fix_verdict='NEED_FIX' 过滤，与 SQL维度分析口径一致
                // （维度分析"问题总数"= 报错 + 所有已分析 POOR，不区分 AI 是否判定需整改）
                + "                 AND overall_rating='POOR' AND " + plain("") + " "
                + "                THEN 1 ELSE 0 END) AS need_fix, "
                + "       SUM(CASE WHEN " + wlApplying("") + " THEN 1 ELSE 0 END) AS wl_applying, "
                + "       SUM(CASE WHEN " + wlApproved("") + " THEN 1 ELSE 0 END) AS wl_approved "
                + "  FROM dii_analysis_item "
                + " WHERE task_id = ? "
                + " GROUP BY domain "
                + " ORDER BY total DESC";
        return jdbc.queryForList(sql, taskId);
    }

    /**
     * 按领域聚合"整改分布"两档，限定到给定 task。
     * 用于第二块整改分布图。
     *
     * <p>口径（与原"评级分布"区别）：不再按 overall_rating 出"优/良/差"，
     * 改为只关心"要不要 DBA 动手"两类：
     * <ul>
     *   <li>{@code error_count} —— EXPLAIN 报错（explain_error 非空），保留</li>
     *   <li>{@code need_fix}    —— 需整改：非报错 + overall_rating='POOR'（有 Seq Scan）。
     *       v7 起<b>不再</b>要求 llm_fix_verdict='NEED_FIX'，与 SQL维度分析"问题总数"口径一致
     *       （报错 + 所有已分析 POOR；维度分析不区分 AI 是否判定需整改）</li>
     * </ul>
     * 二者互斥：explain_error 非空只算 error_count，不进 need_fix。
     * <p><b>注意：返回字段已由 excellent/good/poor/error_count 变为 error_count/need_fix，
     * 前端取数 key 需同步调整。</b>
     */
    public List<Map<String, Object>> aggregateRatingByDomain(Long taskId) {
        if (taskId == null) return Collections.emptyList();
        // v6：报错/需整改互斥剔除活跃白名单；新增 白名单申请中/已申请白名单 两列。
        String sql = ""
                + "SELECT " + DOMAIN_CASE + " AS domain, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' AND " + plain("") + " "
                + "                THEN 1 ELSE 0 END) AS error_count, "
                + "       SUM(CASE WHEN (explain_error IS NULL OR explain_error='') "
                // v7：去掉 llm_fix_verdict='NEED_FIX' 过滤，与 SQL维度分析口径一致
                + "                 AND overall_rating='POOR' AND " + plain("") + " "
                + "                THEN 1 ELSE 0 END) AS need_fix, "
                + "       SUM(CASE WHEN " + wlApplying("") + " THEN 1 ELSE 0 END) AS wl_applying, "
                + "       SUM(CASE WHEN " + wlApproved("") + " THEN 1 ELSE 0 END) AS wl_approved "
                + "  FROM dii_analysis_item "
                + " WHERE task_id = ? "
                + " GROUP BY domain "
                + " ORDER BY need_fix DESC";
        return jdbc.queryForList(sql, taskId);
    }

    /**
     * 最近 7 个 DONE 任务的<b>整改趋势</b>，按领域明细。
     * 用于第三块 7 天趋势图（前端折线 + 领域多选）。
     *
     * <p>口径与 {@link #aggregateRatingByDomain}（整改分布）<b>完全一致</b>，
     * 保证"整改分布"快照图与其时间趋势图同口径：
     * <ul>
     *   <li>{@code error_count} —— EXPLAIN 报错（explain_error 非空）</li>
     *   <li>{@code need_fix}    —— 需整改：非报错 + overall_rating='POOR'（有 Seq Scan）。
     *       v7 起不再要求 llm_fix_verdict='NEED_FIX'，与 SQL维度分析口径一致</li>
     * </ul>
     * AI 判无需整改 / EXCELLENT / GOOD / 未分析(verdict NULL) 天然不计入。
     * 注：need_fix 依赖 refactor 分支的 llm_fix_verdict 列（V10），同整改分布的跨分支耦合。
     *
     * <p>"汇总"由前端对同一 task 跨 domain 求和得出，后端只返回领域明细行。
     *
     * @param env 环境过滤（必填）
     * @return 每行含 task_id / day / domain / error_count / need_fix，
     *         按 task 时间正序、领域名次序；最多 N 任务 × 5 领域
     */
    public List<Map<String, Object>> trendRecentTasks(String env, int limit) {
        int eff = Math.min(Math.max(limit, 1), 30);
        // MySQL 用 INNER 子查询限定最近 N 个任务，再 JOIN 明细按 (任务,领域) 聚合
        // ORDER BY t.id ASC 让前端从左到右按时间正序铺图
        String sql = ""
                + "SELECT t.id AS task_id, "
                + "       DATE(t.created_at) AS day, "
                + "       " + domainCase("i.project_name") + " AS domain, "
                + "       SUM(CASE WHEN i.explain_error IS NOT NULL AND i.explain_error <> '' AND " + plain("i.") + " "
                + "                THEN 1 ELSE 0 END) AS error_count, "
                + "       SUM(CASE WHEN (i.explain_error IS NULL OR i.explain_error='') "
                // v7：去掉 llm_fix_verdict='NEED_FIX' 过滤，与 SQL维度分析口径一致
                + "                 AND i.overall_rating='POOR' AND " + plain("i.") + " THEN 1 ELSE 0 END) AS need_fix, "
                + "       SUM(CASE WHEN " + wlApplying("i.") + " THEN 1 ELSE 0 END) AS wl_applying, "
                + "       SUM(CASE WHEN " + wlApproved("i.") + " THEN 1 ELSE 0 END) AS wl_approved "
                + "  FROM ( "
                + "    SELECT id, created_at FROM dii_analysis_task "
                + "     WHERE env = ? AND status='DONE' "
                + "     ORDER BY id DESC LIMIT ? "
                + "  ) t "
                + "  LEFT JOIN dii_analysis_item i ON i.task_id = t.id "
                + " GROUP BY t.id, day, domain "
                + " ORDER BY t.id ASC, domain";
        return jdbc.queryForList(sql, env, eff);
    }

    /**
     * 最近 7 个 DONE 任务的执行时长（秒）。
     * 用于第四块 7 天耗时柱状图。
     *
     * @param env 环境过滤
     */
    public List<Map<String, Object>> elapsedRecentTasks(String env, int limit) {
        int eff = Math.min(Math.max(limit, 1), 30);
        String sql = ""
                + "SELECT id AS task_id, "
                + "       DATE(created_at) AS day, "
                + "       TIMESTAMPDIFF(SECOND, created_at, updated_at) AS elapsed_seconds, "
                + "       total_sqls "
                + "  FROM dii_analysis_task "
                + " WHERE env = ? AND status='DONE' "
                + " ORDER BY id DESC "
                + " LIMIT ?";
        // SQL 取的是"最近 N 条"，前端要正序展示，由 service 层翻转
        return jdbc.queryForList(sql, env, eff);
    }

    /**
     * 取指定 env 下最新一个 DONE 任务的元信息（id / task_no / created_at / total_sqls）。
     * 给概览页面页头展示用。
     *
     * @return Map 或 null（如 env 下还没有 DONE 任务）
     */
    public Map<String, Object> latestDoneTask(String env) {
        String sql = ""
                + "SELECT id, task_no, env, created_at, updated_at, total_sqls, "
                + "       analyzed_sqls, failed_sqls, skipped_sqls "
                + "  FROM dii_analysis_task "
                + " WHERE env = ? AND status='DONE' "
                + " ORDER BY id DESC "
                + " LIMIT 1";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, env);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * project_name → 领域 SQL CASE 子句生成器。
     *
     * <p>带 JOIN 的查询里 dii_analysis_item 有表别名（如 {@code i}），
     * 需用 {@code domainCase("i.project_name")}；无别名查询用默认 {@link #DOMAIN_CASE}。
     * 与 DaoIndexController#mapDomainLabel 一致，避免前后端口径漂移。
     *
     * @param col project_name 的列引用（可带表别名前缀）
     */
    private static String domainCase(String col) {
        return ""
                + "CASE "
                + "  WHEN LOWER(" + col + ") LIKE '%dept-bcc%' THEN '存款' "
                + "  WHEN LOWER(" + col + ") LIKE '%loan-bcc%' THEN '贷款' "
                + "  WHEN LOWER(" + col + ") LIKE '%comm-bcc%' THEN '公共' "
                + "  WHEN LOWER(" + col + ") LIKE '%sett-bcc%' THEN '结算' "
                + "  ELSE '其他' "
                + "END";
    }

    /** 无表别名场景（FROM dii_analysis_item 直查）的领域 CASE 子句。 */
    private static final String DOMAIN_CASE = domainCase("project_name");

    // ── 白名单互斥分桶谓词（item/pool 同列名；p 为表别名前缀，如 "" 或 "i."）──
    // 已申请白名单：is_whitelist=1 或 申请已通过
    static String wlApproved(String p) {
        return "(" + p + "is_whitelist=1 OR " + p + "whitelist_status='APPROVED')";
    }
    // 白名单申请中：未到已申请，且处于待审/退回流程中
    static String wlApplying(String p) {
        return "(NOT(" + p + "is_whitelist=1 OR " + p + "whitelist_status='APPROVED') AND "
             + p + "whitelist_status IN ('PENDING_L1','PENDING_L2','REJECTED_L1'))";
    }
    // 普通（进维度分析/参与报错·需整改统计）：无活跃白名单
    static String plain(String p) {
        return "(" + p + "is_whitelist=0 AND (" + p + "whitelist_status IS NULL OR "
             + p + "whitelist_status NOT IN ('PENDING_L1','PENDING_L2','REJECTED_L1','APPROVED')))";
    }
    // 活跃白名单（进白名单列表）：已申请 或 申请中
    static String wlActive(String p) {
        return "(" + wlApproved(p) + " OR " + wlApplying(p) + ")";
    }

    /**
     * 问题列表/统计的白名单范围 WHERE 片段（含前导 " AND "）。
     * @param scope    {@code wl}=只看活跃白名单（白名单列表）；其余(含 null)=plain，剔除活跃白名单（维度分析）
     * @param wlStatus 仅 scope=wl 生效：applying(申请中)/approved(已通过)/rejected(已退回)/其余=all(全部活跃)
     */
    public static String whitelistScopeClause(String p, String scope, String wlStatus) {
        if (!"wl".equalsIgnoreCase(scope)) {
            return " AND " + plain(p);
        }
        String ws = wlStatus == null ? "all" : wlStatus.trim().toLowerCase();
        switch (ws) {
            case "applying": return " AND " + wlApplying(p);
            case "approved": return " AND " + wlApproved(p);
            case "rejected": return " AND " + p + "whitelist_status='REJECTED_L1'";
            default:         return " AND " + wlActive(p);
        }
    }

    /**
     * 「该我审批」过滤片段（含前导 " AND "，带 2 个 {@code ?} 占位=approver,approver）：
     * 行的白名单申请处于待审，且当前用户是对应级别审批人（铃铛跳白名单页"我的待审"用）。
     * approver 为空返回空串（不过滤）。
     */
    public static String approverClause(String p, String approver) {
        if (approver == null || approver.isBlank()) return "";
        return " AND " + p + "whitelist_app_id IN (SELECT id FROM dii_whitelist_application "
             + " WHERE (status='PENDING_L1' AND l1_approver=?) OR (status='PENDING_L2' AND l2_approver=?))";
    }
}
