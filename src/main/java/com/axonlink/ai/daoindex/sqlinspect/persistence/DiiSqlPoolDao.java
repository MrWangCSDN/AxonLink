package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code dii_sql_pool}（SQL 池表）读写 DAO。
 *
 * <p>职责：
 * <ol>
 *     <li>{@link #insertIgnore}：批量导入用，INSERT IGNORE 跳过库内重复</li>
 *     <li>{@link #search}：分页列表查询</li>
 *     <li>{@link #setWhitelist}：白名单切换</li>
 * </ol>
 *
 * <p>风格与 {@link DiiAnalysisItemDao} 保持一致：用 {@code diiResultJdbcTemplate}
 * 注入结果库连接，不引入 ORM。
 */
@Repository
public class DiiSqlPoolDao {

    private static final Logger log = LoggerFactory.getLogger(DiiSqlPoolDao.class);

    /**
     * upsert 三态结果。
     * <ul>
     *   <li>{@link #INSERTED}：新行落库</li>
     *   <li>{@link #UPDATED}：已存在 (named_sql, sql_hash)，仅 updated_at 被更新到新时间</li>
     *   <li>{@link #UNCHANGED}：已存在且 updated_at 已经是该值（再次导入同样 Excel 同样时间戳）</li>
     * </ul>
     */
    public enum UpsertOutcome { INSERTED, UPDATED, UNCHANGED }

    /** 完整 INSERT 列：含 created_at/updated_at 显式传值（不走 DEFAULT，便于用日志时间）。 */
    private static final String INSERT_SQL =
            "INSERT INTO dii_sql_pool (" +
            " named_sql, sql_text, sql_hash, project_name, source, env, is_whitelist, " +
            " created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)";

    /**
     * 命中 unique key 时的更新——仅刷新 updated_at；不动 created_at / project_name / sql_text。
     * <p>条件加 {@code updated_at <> ?} 区分"真的改了"vs"完全相同"，用于
     * {@link UpsertOutcome#UPDATED} / {@link UpsertOutcome#UNCHANGED} 计数。
     */
    private static final String UPDATE_TS_SQL =
            "UPDATE dii_sql_pool SET updated_at = ? " +
            " WHERE named_sql = ? AND sql_hash = ? AND updated_at <> ?";

    /** 用于 unchanged 计数：行存在但时间相同。 */
    private static final String CHECK_EXISTS_SQL =
            "SELECT COUNT(*) FROM dii_sql_pool WHERE named_sql = ? AND sql_hash = ?";

    private final JdbcTemplate jdbc;

    public DiiSqlPoolDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /**
     * 带时间戳的 upsert：存在则只更 updated_at；不存在则 INSERT。
     *
     * <p>实现路径（两阶段，简单优先于完美原子性；并发由 unique key 兜底）：
     * <ol>
     *   <li>先 UPDATE WHERE updated_at &lt;&gt; logTs —— 命中返回 {@link UpsertOutcome#UPDATED}</li>
     *   <li>未命中：SELECT COUNT 看是否存在
     *     <ul>
     *       <li>存在 → 时间相同，{@link UpsertOutcome#UNCHANGED}</li>
     *       <li>不存在 → INSERT；遇 DuplicateKey（并发抢插）→ 当 UPDATED 处理（已被其他 import 占位）</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param logTs 日志行第一个 [] 解析出的时间戳
     */
    public UpsertOutcome upsertWithTimestamp(String namedSql, String sqlText, String sqlHash,
                                             String projectName, String source, String env,
                                             LocalDateTime logTs) {
        Timestamp ts = logTs == null
                ? Timestamp.valueOf(LocalDateTime.now())
                : Timestamp.valueOf(logTs);

        // 1) 先尝试 UPDATE（仅当时间不同）
        int updated;
        try {
            updated = jdbc.update(UPDATE_TS_SQL, ts, namedSql, sqlHash, ts);
        } catch (Exception e) {
            log.warn("[dii-sql-pool] UPDATE 失败 named={} hash={}: {}",
                    namedSql, sqlHash, e.getMessage());
            updated = 0;
        }
        if (updated > 0) return UpsertOutcome.UPDATED;

        // 2) UPDATE 0 行有两种情况：① 行不存在 → INSERT；② 行存在且时间相同 → UNCHANGED
        Long exists = jdbc.queryForObject(CHECK_EXISTS_SQL, Long.class, namedSql, sqlHash);
        if (exists != null && exists > 0) {
            return UpsertOutcome.UNCHANGED;
        }

        // 3) INSERT；并发场景下可能与他人抢插冲突，捕获 DuplicateKey 当 UPDATED 处理
        try {
            jdbc.update(INSERT_SQL,
                    namedSql, sqlText, sqlHash, projectName,
                    source == null ? "EXCEL" : source, env, ts, ts);
            return UpsertOutcome.INSERTED;
        } catch (DuplicateKeyException dupe) {
            // 并发：先前 SELECT 看不到，但 INSERT 时已被他人占了——按 UPDATED 兜底刷时间
            try {
                jdbc.update(UPDATE_TS_SQL, ts, namedSql, sqlHash, ts);
            } catch (Exception ignored) { /* 仍失败：日志即可，统计按 UPDATED 计一次 */ }
            return UpsertOutcome.UPDATED;
        } catch (Exception e) {
            log.warn("[dii-sql-pool] INSERT 失败 named={} hash={}: {}",
                    namedSql, sqlHash, e.getMessage());
            return UpsertOutcome.UNCHANGED;
        }
    }

    /**
     * 单条 ID 查询（用于白名单切换接口存在性校验）。
     */
    public Map<String, Object> findById(long id) {
        try {
            return jdbc.queryForMap("SELECT * FROM dii_sql_pool WHERE id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 分页查询池表，支持按工程名 / 白名单 / 关键字（命名 SQL 或 SQL 文本模糊）过滤。
     *
     * @param projectName 工程名等值过滤（可空）
     * @param whitelist   {@code null}=不过滤；{@code 0/1}=精确匹配 is_whitelist
     * @param keyword     在 named_sql / sql_text 上模糊匹配（可空）
     * @param limit       每页条数（默认 50，最大 500）
     * @param offset      偏移
     */
    public List<Map<String, Object>> search(String projectName, Integer whitelist,
                                            String keyword, int limit, int offset) {
        StringBuilder sb = new StringBuilder(
                "SELECT id, named_sql, sql_text, sql_hash, project_name, source, env, " +
                "       is_whitelist, overall_rating, rating_label, " +
                "       explain_top_cost, explain_est_rows, explain_has_seq_scan, " +
                "       llm_status, llm_summary, llm_fix_verdict, " +
                "       created_at, updated_at " +
                "  FROM dii_sql_pool WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            sb.append(" AND project_name = ?");
            args.add(projectName.trim());
        }
        if (whitelist != null) {
            sb.append(" AND is_whitelist = ?");
            args.add(whitelist == 0 ? 0 : 1);
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (named_sql LIKE ? OR sql_text LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        int eff = Math.min(Math.max(limit, 1), 500);
        int off = Math.max(offset, 0);
        sb.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(eff);
        args.add(off);
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    /**
     * 统计符合过滤条件的行数（前端分页器用）。
     */
    public long count(String projectName, Integer whitelist, String keyword) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM dii_sql_pool WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            sb.append(" AND project_name = ?");
            args.add(projectName.trim());
        }
        if (whitelist != null) {
            sb.append(" AND is_whitelist = ?");
            args.add(whitelist == 0 ? 0 : 1);
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (named_sql LIKE ? OR sql_text LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 切换白名单：DBA 标记一条池 SQL 为白名单 / 取消。
     *
     * @param id    池表行 id
     * @param value 1=置位为白名单；0=取消
     * @return 实际更新的行数（0=id 不存在）
     */
    public int setWhitelist(long id, int value) {
        int v = value == 0 ? 0 : 1;
        return jdbc.update(
                "UPDATE dii_sql_pool SET is_whitelist = ? WHERE id = ?",
                v, id);
    }

    /**
     * 工作流：HASH 模式同步——仅匹配 sql_hash 的池行。
     */
    public int syncWhitelistByHash(String sqlHash, Long appId, String status, boolean approved) {
        if (sqlHash == null) return 0;
        return jdbc.update(
                "UPDATE dii_sql_pool " +
                "   SET whitelist_app_id = ?, whitelist_status = ?, " +
                "       is_whitelist = CASE WHEN ? THEN 1 ELSE is_whitelist END " +
                " WHERE sql_hash = ?",
                appId, status, approved, sqlHash);
    }

    /**
     * 工作流：NAMED_SQL 模式同步——匹配 named_sql 的所有池行（nsql 包含模式）。
     */
    public int syncWhitelistByNamedSql(String namedSql, Long appId, String status, boolean approved) {
        if (namedSql == null) return 0;
        return jdbc.update(
                "UPDATE dii_sql_pool " +
                "   SET whitelist_app_id = ?, whitelist_status = ?, " +
                "       is_whitelist = CASE WHEN ? THEN 1 ELSE is_whitelist END " +
                " WHERE named_sql = ?",
                appId, status, approved, namedSql);
    }

    /** 清掉某 app 对应的池行 wl 字段（CANCELLED 时；不动 is_whitelist）。 */
    public int clearWhitelistByAppId(long appId) {
        return jdbc.update(
                "UPDATE dii_sql_pool " +
                "   SET whitelist_app_id = NULL, whitelist_status = NULL " +
                " WHERE whitelist_app_id = ?",
                appId);
    }

    /**
     * 池行 upsert 后调用：反查匹配 sql_hash 或 named_sql 的活跃 application，继承 wl 字段。
     * @param matchByNamedSql true=按 named_sql 匹配（NAMED_SQL 模式应用）；false=按 sql_hash
     */
    public boolean inheritWhitelistOnUpsert(long poolId, String sqlHash, String namedSql,
                                            Long appId, String status, boolean matchByNamedSql) {
        if (appId == null) return false;
        boolean approved = "APPROVED".equals(status);
        int affected = jdbc.update(
                "UPDATE dii_sql_pool " +
                "   SET whitelist_app_id = ?, whitelist_status = ?, " +
                "       is_whitelist = CASE WHEN ? THEN 1 ELSE is_whitelist END " +
                " WHERE id = ?",
                appId, status, approved, poolId);
        return affected > 0;
    }

    /**
     * 列出全部不同的 project_name（去重 + 排序），用于前端下拉框。
     */
    public List<String> listDistinctProjects() {
        return jdbc.queryForList(
                "SELECT DISTINCT project_name FROM dii_sql_pool " +
                " WHERE project_name IS NOT NULL AND project_name <> '' " +
                " ORDER BY project_name",
                String.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 与 item 表"问题列表"对齐的视图：用于 SQL 分析页前端合并展示
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 把池行映射到「问题列表」相同字段集，便于前端与 item 行同表渲染。
     *
     * <p>映射约定：
     * <ul>
     *   <li>{@code class_fqn = named_sql} —— 直接用全名（前端列展示也直观）</li>
     *   <li>{@code method_name = null}（未拆分；前端不展示）</li>
     *   <li>{@code source = 'nsql'}（虚拟列，frontend 渲染标签）</li>
     *   <li>{@code task_id = null}（池不挂任务）</li>
     * </ul>
     */
    private static final String ISSUE_PROJECTION_COLS = ""
            + " p.id, p.sql_hash, 'UNKNOWN' AS sql_kind, p.env, "
            + " CAST(NULL AS UNSIGNED) AS task_id, "
            + " p.project_name, p.named_sql AS class_fqn, NULL AS method_name, "
            + " p.overall_rating, p.rating_label, p.involved_tables, "
            + " CAST(NULL AS UNSIGNED) AS rule_engine_elapsed_ms, "
            + " 'DONE' AS status, p.created_at, p.sql_text, p.explain_error, "
            + " p.llm_status, p.llm_error, p.llm_summary, "
            + " p.llm_findings_json, p.llm_suggestions_json, "
            + " p.llm_model, p.llm_prompt_version, p.llm_elapsed_ms, p.llm_called_at, p.llm_confidence, "
            + " p.llm_fix_verdict, p.is_whitelist, "
            + " p.named_sql, "  // pool 多带原始 named_sql，前端申请白名单时区分 nsql 包含模式
            + " p.whitelist_app_id, p.whitelist_status, "
            // V16+：白名单 target_type 揭示"单条 HASH"vs"同名 NAMED_SQL"
            + " wa.target_type AS whitelist_target_type, "
            + " 'nsql' AS source ";

    /**
     * 池行的「问题列表」视图：按 env 过滤，分页。形状与
     * {@link DiiAnalysisItemDao#searchIssuesOnly} 一致（多了 source / named_sql / is_whitelist）。
     *
     * <p>"问题"口径与 item 表对齐：explain_error 非空 或 llm_status 终态。
     */
    public List<Map<String, Object>> searchAsIssues(String env, String whitelistScope, String wlStatus,
                                                    String approverUser, int limit, int offset) {
        StringBuilder sb = new StringBuilder("SELECT ").append(ISSUE_PROJECTION_COLS)
                .append("  FROM dii_sql_pool p ")
                // LEFT JOIN 取 target_type；池 wl_app_id 为 NULL 时不影响左侧记录
                .append("  LEFT JOIN dii_whitelist_application wa ON wa.id = p.whitelist_app_id ")
                .append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            // 与大屏 aggregateByDomain 同口径：当前 env + 未标 env 的池行都算进来，保证大屏与列表数量对得上
            sb.append(" AND (p.env = ? OR p.env IS NULL OR p.env = '')"); args.add(env.trim());
        }
        // 与 item 同样的"有料"过滤：explain 报错 或 LLM 终态
        sb.append(" AND ((p.explain_error IS NOT NULL AND p.explain_error <> '')")
          .append(" OR p.llm_status IN ('DONE','PENDING','FAILED')")
          // 池里很多刚导入还没跑分析的行也要展示（没 explain_error 也没 llm_status）
          // ——这是与 item 的关键差别：池是"待巡检候选库"，前端要看得到
          .append(" OR (p.explain_error IS NULL AND p.llm_status IS NULL)) ");
        // v6：白名单范围（plain 剔除活跃白名单 / wl 只看活跃白名单）
        sb.append(DiiDashboardDao.whitelistScopeClause("p.", whitelistScope, wlStatus));
        // v7：铃铛"我的待审"——只看该我审批且处于待审的白名单池行
        String apClause = DiiDashboardDao.approverClause("p.", approverUser);
        if (!apClause.isEmpty()) { sb.append(apClause); args.add(approverUser.trim()); args.add(approverUser.trim()); }
        int eff = Math.min(Math.max(limit, 1), 500);
        int off = Math.max(offset, 0);
        sb.append(" ORDER BY p.id DESC LIMIT ? OFFSET ?");
        args.add(eff);
        args.add(off);
        List<Map<String, Object>> rows = jdbc.queryForList(sb.toString(), args.toArray());

        // V16+ 修复：池表没存 sql_kind 列（ISSUE_PROJECTION_COLS 硬编码 'UNKNOWN'），
        // 这里用 SqlKindDetector 按 sql_text 实时识别覆盖，让前端 nsql 行也能显示 SELECT/UPDATE 等
        // 类型徽章——与 odb 行视觉一致。SqlKindDetector 是首关键字正则，开销 < 0.01ms/行。
        for (Map<String, Object> row : rows) {
            Object txt = row.get("sql_text");
            if (txt != null) {
                String sqlText = String.valueOf(txt);
                if (!sqlText.isBlank()) {
                    try {
                        row.put("sql_kind",
                                com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlKindDetector
                                        .detect(sqlText).name());
                    } catch (Exception ignore) {
                        // 解析失败保持默认 'UNKNOWN'
                    }
                }
            }
        }
        return rows;
    }

    /**
     * 与 {@link #searchAsIssues} 同过滤条件下的总数。
     */
    public long countAsIssues(String env, String whitelistScope, String wlStatus, String approverUser) {
        StringBuilder sb = new StringBuilder(
                "SELECT COUNT(*) FROM dii_sql_pool WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND (env = ? OR env IS NULL OR env = '')"); args.add(env.trim());
        }
        sb.append(" AND ((explain_error IS NOT NULL AND explain_error <> '')")
          .append(" OR llm_status IN ('DONE','PENDING','FAILED')")
          .append(" OR (explain_error IS NULL AND llm_status IS NULL))");
        sb.append(DiiDashboardDao.whitelistScopeClause("", whitelistScope, wlStatus));
        String apClause = DiiDashboardDao.approverClause("", approverUser);
        if (!apClause.isEmpty()) { sb.append(apClause); args.add(approverUser.trim()); args.add(approverUser.trim()); }
        Long total = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 4 个 KPI（与 item 同口径）：total / explainError / llmFindings / llmPending / llmError。
     */
    public Map<String, Long> getIssuesStats(String env, String whitelistScope, String wlStatus) {
        StringBuilder sb = new StringBuilder(
                "SELECT " +
                "  SUM(CASE WHEN explain_error IS NOT NULL AND explain_error<>'' THEN 1 ELSE 0 END) AS explain_error_cnt, " +
                "  SUM(CASE WHEN (explain_error IS NULL OR explain_error='') AND llm_status='DONE'    THEN 1 ELSE 0 END) AS llm_findings_cnt, " +
                "  SUM(CASE WHEN (explain_error IS NULL OR explain_error='') AND llm_status='PENDING' THEN 1 ELSE 0 END) AS llm_pending_cnt, " +
                "  SUM(CASE WHEN (explain_error IS NULL OR explain_error='') AND llm_status='FAILED'  THEN 1 ELSE 0 END) AS llm_error_cnt, " +
                "  COUNT(*) AS total_cnt " +
                "  FROM dii_sql_pool WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND (env = ? OR env IS NULL OR env = '')"); args.add(env.trim());
        }
        // 池统计取全量（含未分析的候选行），与 searchAsIssues 的过滤一致
        sb.append(" AND ((explain_error IS NOT NULL AND explain_error<>'')")
          .append(" OR llm_status IN ('DONE','PENDING','FAILED')")
          .append(" OR (explain_error IS NULL AND llm_status IS NULL))");
        sb.append(DiiDashboardDao.whitelistScopeClause("", whitelistScope, wlStatus));

        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(sb.toString(), args.toArray());
        } catch (Exception e) {
            log.warn("[dii-sql-pool] 查 issues stats 失败 env={}: {}", env, e.getMessage());
            row = java.util.Collections.emptyMap();
        }
        Map<String, Long> r = new java.util.LinkedHashMap<>();
        r.put("total",        toLong(row.get("total_cnt")));
        r.put("explainError", toLong(row.get("explain_error_cnt")));
        r.put("llmFindings",  toLong(row.get("llm_findings_cnt")));
        r.put("llmPending",   toLong(row.get("llm_pending_cnt")));
        r.put("llmError",     toLong(row.get("llm_error_cnt")));
        return r;
    }

    /**
     * 按领域聚合（与 {@link DiiDashboardDao#aggregateByDomain} 同口径，但来源是池）。
     * <p>不限定 task（池不挂任务）；仅按 env 过滤。
     * @return 每行 {domain, total, explain_err, llm_fix}
     */
    public List<Map<String, Object>> aggregateByDomain(String env) {
        String sql = ""
                + "SELECT " + domainCase("project_name") + " AS domain, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' THEN 1 "
                + "                WHEN overall_rating IN ('EXCELLENT','GOOD','POOR') THEN 1 "
                // 池行刚导入未跑分析的也算 total（让前端"巡检 SQL 数"看得见）
                + "                WHEN explain_error IS NULL AND llm_status IS NULL THEN 1 "
                + "                ELSE 0 END) AS total, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' AND " + DiiDashboardDao.plain("") + " "
                + "                THEN 1 ELSE 0 END) AS explain_err, "
                + "       SUM(CASE WHEN (explain_error IS NULL OR explain_error='') "
                + "                 AND overall_rating='POOR' AND llm_fix_verdict='NEED_FIX' AND " + DiiDashboardDao.plain("") + " "
                + "                THEN 1 ELSE 0 END) AS need_fix, "
                + "       SUM(CASE WHEN " + DiiDashboardDao.wlApplying("") + " THEN 1 ELSE 0 END) AS wl_applying, "
                + "       SUM(CASE WHEN " + DiiDashboardDao.wlApproved("") + " THEN 1 ELSE 0 END) AS wl_approved "
                + "  FROM dii_sql_pool "
                + " WHERE 1=1 ";
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            // V2 修复：池是全局候选库，导入时 env 常留空——dashboard 汇总要把
            // 「当前 env 的」+「未标 env 的」都算进去，否则总数对不上（看板 SQL 总数漏池）。
            sql += " AND (env = ? OR env IS NULL OR env = '') ";
            args.add(env.trim());
        }
        sql += " GROUP BY domain ORDER BY total DESC";
        return jdbc.queryForList(sql, args.toArray());
    }

    /**
     * 按领域聚合"整改分布"两档（与 {@link DiiDashboardDao#aggregateRatingByDomain} 同口径）。
     * @return 每行 {domain, error_count, need_fix}
     */
    public List<Map<String, Object>> aggregateRatingByDomain(String env) {
        String sql = ""
                + "SELECT " + domainCase("project_name") + " AS domain, "
                + "       SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' AND " + DiiDashboardDao.plain("") + " "
                + "                THEN 1 ELSE 0 END) AS error_count, "
                + "       SUM(CASE WHEN (explain_error IS NULL OR explain_error='') "
                + "                 AND overall_rating='POOR' "
                + "                 AND llm_fix_verdict='NEED_FIX' AND " + DiiDashboardDao.plain("") + " "
                + "                THEN 1 ELSE 0 END) AS need_fix, "
                + "       SUM(CASE WHEN " + DiiDashboardDao.wlApplying("") + " THEN 1 ELSE 0 END) AS wl_applying, "
                + "       SUM(CASE WHEN " + DiiDashboardDao.wlApproved("") + " THEN 1 ELSE 0 END) AS wl_approved "
                + "  FROM dii_sql_pool "
                + " WHERE 1=1 ";
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            // 同 aggregateByDomain：当前 env + 未标 env 的池行都计入
            sql += " AND (env = ? OR env IS NULL OR env = '') ";
            args.add(env.trim());
        }
        sql += " GROUP BY domain ORDER BY need_fix DESC";
        return jdbc.queryForList(sql, args.toArray());
    }

    /**
     * 池行按 env 计数（供 listBatchTasks LEFT JOIN 用）。
     * 返回 {@code env → count} map。
     */
    public Map<String, Long> countByEnv() {
        Map<String, Long> r = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT env, COUNT(*) AS cnt FROM dii_sql_pool " +
                " WHERE env IS NOT NULL AND env <> '' GROUP BY env");
        for (Map<String, Object> row : rows) {
            String env = String.valueOf(row.get("env"));
            r.put(env, toLong(row.get("cnt")));
        }
        return r;
    }

    /**
     * 池总数（不分 env，供回退场景使用）。
     */
    public long countAll() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM dii_sql_pool", Long.class);
        return n == null ? 0L : n;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // V16+：池行批量巡检（PoolBatchInspector 调用）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 列出待巡检的非白名单池行（按 env 过滤）。
     * <p>用于 PoolBatchInspector 主循环。返回完整池行字段（含 sql_text/named_sql/sql_hash/project_name 等）。
     */
    public List<Map<String, Object>> listForInspection(String env, int limit) {
        int eff = Math.min(Math.max(limit, 1), 10_000);
        StringBuilder sb = new StringBuilder(
                "SELECT id, named_sql, sql_text, sql_hash, project_name, env, is_whitelist " +
                "  FROM dii_sql_pool " +
                " WHERE is_whitelist = 0 ");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND env = ? "); args.add(env.trim());
        }
        sb.append(" ORDER BY id ASC LIMIT ?");
        args.add(eff);
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    /**
     * 物理删除一条池行（非 SeqScan 时调用）。
     * <p><b>注意</b>：真删除，不软标记。审计可在删前 log。
     */
    public int deleteById(long id) {
        return jdbc.update("DELETE FROM dii_sql_pool WHERE id = ?", id);
    }

    /**
     * EXPLAIN 完成后写回池行（用于 SeqScan 命中的保留路径）。
     * <p>同步把 LLM 字段清空，让后续 enrichPool 重跑。
     */
    public int updateInspectionFields(long id,
                                      com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult r) {
        return jdbc.update(
                "UPDATE dii_sql_pool SET " +
                "  overall_rating = ?, rating_label = ?, involved_tables = ?, " +
                "  explain_plan = ?, explain_top_cost = ?, explain_est_rows = ?, " +
                "  explain_has_seq_scan = ?, explain_elapsed_ms = ?, explain_error = ?, " +
                // 重置 LLM 字段——让 enrichPool 重新分析
                "  llm_status = NULL, llm_summary = NULL, llm_findings_json = NULL, " +
                "  llm_suggestions_json = NULL, llm_confidence = NULL, llm_fix_verdict = NULL, " +
                "  llm_model = NULL, llm_prompt_version = NULL, llm_elapsed_ms = NULL, " +
                "  llm_error = NULL, llm_called_at = NULL " +
                "WHERE id = ?",
                r.getOverallRating() == null ? null : r.getOverallRating().name(),
                r.getOverallRatingLabel(),
                truncate(resolveInvolvedTablesCsv(r), 2000),
                r.getExplainPlanJson(),
                r.getExplainTopCost(),
                r.getExplainEstRows(),
                r.getExplainHasSeqScan() == null ? null : (r.getExplainHasSeqScan() ? 1 : 0),
                r.getExplainElapsedMs(),
                r.getExplainError(),
                id);
    }

    /** 标 LLM 待跑：llm_status='PENDING'。EnrichPool 据此拣选。 */
    public int markLlmPending(long id) {
        return jdbc.update(
                "UPDATE dii_sql_pool SET llm_status = 'PENDING' " +
                "WHERE id = ? AND (llm_status IS NULL OR llm_status = 'SKIPPED' OR llm_status = 'FAILED')",
                id);
    }

    /**
     * 强制置 PENDING（重新分析按钮用）：不挑现状态，并清掉旧 llm_* 结果。
     * <p>与 {@link DiiAnalysisItemDao#forceMarkLlmPending} 同语义；前端首次轮询即可看到稳定蒙版。
     * @return affectedRows（0 = 池行不存在，调用方应返 404/400）
     */
    public int forceMarkLlmPending(long id) {
        return jdbc.update(
                "UPDATE dii_sql_pool SET llm_status='PENDING', " +
                "       llm_summary=NULL, llm_findings_json=NULL, llm_suggestions_json=NULL, " +
                "       llm_confidence=NULL, llm_fix_verdict=NULL, " +
                "       llm_error=NULL, llm_called_at=NOW() " +
                "WHERE id = ?",
                id);
    }

    /** 拣 LLM 待跑池行 id。 */
    public List<Long> findPendingLlmIds(String env, int limit) {
        int eff = Math.min(Math.max(limit, 1), 10_000);
        StringBuilder sb = new StringBuilder(
                "SELECT id FROM dii_sql_pool " +
                " WHERE llm_status = 'PENDING' " +
                "   AND is_whitelist = 0 " +
                "   AND overall_rating = 'POOR' ");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND env = ? "); args.add(env.trim());
        }
        sb.append(" ORDER BY id ASC LIMIT ?");
        args.add(eff);
        return jdbc.queryForList(sb.toString(), Long.class, args.toArray());
    }

    /** LLM 仅状态更新（失败 / 解析失败）。 */
    public int updateLlmStatusOnly(long id, String status, String error) {
        return jdbc.update(
                "UPDATE dii_sql_pool SET llm_status = ?, llm_error = ?, llm_called_at = NOW() " +
                "WHERE id = ?",
                status, truncate(error, 1000), id);
    }

    /** LLM 完整写回（成功路径）。字段顺序与 item DAO updateLlmFull 一致便于复用。 */
    public int updateLlmFull(long id, String status,
                             String summary, String findingsJson, String suggestionsJson,
                             String confidence, String fixVerdict,
                             String model, String promptVersion,
                             long elapsedMs, String error) {
        return jdbc.update(
                "UPDATE dii_sql_pool SET " +
                "  llm_status = ?, llm_summary = ?, llm_findings_json = ?, llm_suggestions_json = ?, " +
                "  llm_confidence = ?, llm_fix_verdict = ?, llm_model = ?, llm_prompt_version = ?, " +
                "  llm_elapsed_ms = ?, llm_error = ?, llm_called_at = NOW() " +
                "WHERE id = ?",
                status, truncate(summary, 500), findingsJson, suggestionsJson,
                confidence, fixVerdict, model, promptVersion,
                (int) Math.min(elapsedMs, Integer.MAX_VALUE),
                truncate(error, 1000),
                id);
    }

    /** 加载 LLM 上下文用的单行查询（enrichPool 拣 id 后调）。 */
    public Map<String, Object> loadInspectionContext(long id) {
        try {
            return jdbc.queryForMap("SELECT * FROM dii_sql_pool WHERE id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** SqlInspectionResult.tables 或 involvedTables → 逗号分隔表名。 */
    private static String resolveInvolvedTablesCsv(
            com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult r) {
        // 优先用 involvedTables（SqlInspectionService 已填充）；为空回退 tableRatings 表名
        if (r.getInvolvedTables() != null && !r.getInvolvedTables().isEmpty()) {
            return String.join(",", r.getInvolvedTables());
        }
        if (r.getTableRatings() != null && !r.getTableRatings().isEmpty()) {
            return r.getTableRatings().stream()
                    .map(t -> t == null ? "" : t.getTable())
                    .filter(s -> s != null && !s.isEmpty())
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        return null;
    }

    /** 与 {@link DiiDashboardDao#domainCase} 同口径，保证前后端领域映射一致。 */
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

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0L; }
    }
}
