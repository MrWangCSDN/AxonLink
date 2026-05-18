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
        String sql = ""
                + "SELECT " + DOMAIN_CASE + " AS domain, "
                + "       COUNT(*) AS total, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' "
                + "                THEN 1 ELSE 0 END) AS explain_err, "
                // 整改口径：以 LLM 整改判定 NEED_FIX 为准（与第二块"整改分布"统一）
                + "       SUM(CASE WHEN llm_fix_verdict='NEED_FIX' "
                + "                THEN 1 ELSE 0 END) AS llm_fix "
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
     *   <li>{@code need_fix}    —— 待整改：非报错 + overall_rating='POOR'（有 Seq Scan）
     *       + LLM 整改判定 llm_fix_verdict='NEED_FIX'</li>
     * </ul>
     * 二者互斥：explain_error 非空只算 error_count，不进 need_fix。
     * <p><b>注意：返回字段已由 excellent/good/poor/error_count 变为 error_count/need_fix，
     * 前端取数 key 需同步调整。</b>
     */
    public List<Map<String, Object>> aggregateRatingByDomain(Long taskId) {
        if (taskId == null) return Collections.emptyList();
        String sql = ""
                + "SELECT " + DOMAIN_CASE + " AS domain, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' "
                + "                THEN 1 ELSE 0 END) AS error_count, "
                + "       SUM(CASE WHEN (explain_error IS NULL OR explain_error='') "
                + "                 AND overall_rating='POOR' "
                + "                 AND llm_fix_verdict='NEED_FIX' "
                + "                THEN 1 ELSE 0 END) AS need_fix "
                + "  FROM dii_analysis_item "
                + " WHERE task_id = ? "
                + " GROUP BY domain "
                + " ORDER BY need_fix DESC";
        return jdbc.queryForList(sql, taskId);
    }

    /**
     * 最近 7 个 DONE 任务的评级趋势。
     * 用于第三块 7 天趋势折线/分组柱状图。
     *
     * @param env 环境过滤（必填）
     * @return 每行含 task_id / day / excellent / good / poor / error_count，按时间正序
     */
    public List<Map<String, Object>> trendRecentTasks(String env, int limit) {
        int eff = Math.min(Math.max(limit, 1), 30);
        // MySQL 用 INNER 子查询限定最近 N 个任务，再 JOIN 明细聚合
        // ORDER BY t.id ASC 让前端从左到右按时间正序铺图
        String sql = ""
                + "SELECT t.id AS task_id, "
                + "       DATE(t.created_at) AS day, "
                + "       SUM(CASE WHEN (i.explain_error IS NULL OR i.explain_error='') "
                + "                 AND i.overall_rating='EXCELLENT' THEN 1 ELSE 0 END) AS excellent, "
                + "       SUM(CASE WHEN (i.explain_error IS NULL OR i.explain_error='') "
                + "                 AND i.overall_rating='GOOD' THEN 1 ELSE 0 END) AS good, "
                + "       SUM(CASE WHEN (i.explain_error IS NULL OR i.explain_error='') "
                + "                 AND i.overall_rating='POOR' THEN 1 ELSE 0 END) AS poor, "
                + "       SUM(CASE WHEN i.explain_error IS NOT NULL AND i.explain_error <> '' "
                + "                THEN 1 ELSE 0 END) AS error_count "
                + "  FROM ( "
                + "    SELECT id, created_at FROM dii_analysis_task "
                + "     WHERE env = ? AND status='DONE' "
                + "     ORDER BY id DESC LIMIT ? "
                + "  ) t "
                + "  LEFT JOIN dii_analysis_item i ON i.task_id = t.id "
                + " GROUP BY t.id, day "
                + " ORDER BY t.id ASC";
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
     * 复用的 project_name → 领域 SQL CASE 子句。
     * 与 DaoIndexController#mapDomainLabel 一致，避免前后端口径漂移。
     */
    private static final String DOMAIN_CASE = ""
            + "CASE "
            + "  WHEN LOWER(project_name) LIKE '%dept-bcc%' THEN '存款' "
            + "  WHEN LOWER(project_name) LIKE '%loan-bcc%' THEN '贷款' "
            + "  WHEN LOWER(project_name) LIKE '%comm-bcc%' THEN '公共' "
            + "  WHEN LOWER(project_name) LIKE '%sett-bcc%' THEN '结算' "
            + "  ELSE '其他' "
            + "END";
}
