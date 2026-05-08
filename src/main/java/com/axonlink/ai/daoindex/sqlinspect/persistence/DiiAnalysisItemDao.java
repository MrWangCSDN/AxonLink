package com.axonlink.ai.daoindex.sqlinspect.persistence;

import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code dii_analysis_item} 表读写 DAO。
 *
 * <p>职责：
 * <ol>
 *   <li>{@link #insertFromResult}：把一次 SQL 分析结果落库，返回 itemId</li>
 *   <li>{@link #findRecentDone}：5 分钟幂等窗口查询，命中直接复用旧结论</li>
 *   <li>{@link #loadById}：查单条详情</li>
 * </ol>
 */
@Repository
public class DiiAnalysisItemDao {

    private static final Logger log = LoggerFactory.getLogger(DiiAnalysisItemDao.class);

    private static final String INSERT_SQL =
            "INSERT INTO dii_analysis_item (" +
            " sql_hash, sql_text, sql_kind, env, task_id, " +
            " project_name, class_fqn, method_name, source_file, " +
            " overall_rating, rating_label, involved_tables, table_ratings_json, rule_engine_elapsed_ms, " +
            " status, warnings_json, " +
            // V8 新增：EXPLAIN + runtime + 表元数据
            " runtime_rating, runtime_rating_label, disagreement, disagreement_reason, " +
            " explain_plan, explain_top_cost, explain_est_rows, explain_has_seq_scan, " +
            " explain_elapsed_ms, explain_error, " +
            " table_stats_json, column_stats_json, table_ddl_json) " +
            "VALUES (?, ?, ?, ?, ?,  ?, ?, ?, ?,  ?, ?, ?, ?, ?,  ?, ?, " +
            "        ?, ?, ?, ?,  ?, ?, ?, ?,  ?, ?,  ?, ?, ?)";

    /** 5 分钟幂等窗口查询：按 (sql_hash, env) 倒序取最近一条 DONE 记录。 */
    private static final String FIND_RECENT_DONE_SQL =
            "SELECT id FROM dii_analysis_item " +
            " WHERE sql_hash = ? AND env = ? AND status = 'DONE' " +
            "   AND created_at > (NOW() - INTERVAL ? MINUTE) " +
            " ORDER BY created_at DESC LIMIT 1";

    // SELECT * 故意使用：loadContext 重建 SqlInspectionResult 时需要用到
    // runtime_rating / explain_* / disagreement / table_stats_json / column_stats_json /
    // table_ddl_json / llm_* 等大量字段，之前手工列白名单漏了 runtime_rating，
    // 导致 LLM 防御守卫把所有 POOR/GOOD item 误判成 SKIPPED。
    private static final String SELECT_BY_ID_SQL =
            "SELECT * FROM dii_analysis_item WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DiiAnalysisItemDao(JdbcTemplate diiResultJdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = diiResultJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查"最近 N 分钟内同 sql_hash+env 的 DONE 记录"；命中即返回 id，用于 5 分钟幂等复用。
     *
     * @return 命中的 itemId，未命中返回 {@code null}
     */
    public Long findRecentDone(String sqlHash, String env, int minutes) {
        if (sqlHash == null || env == null || minutes <= 0) return null;
        try {
            return jdbc.queryForObject(FIND_RECENT_DONE_SQL, Long.class, sqlHash, env, minutes);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("[dii-item-dao] 幂等查询失败 sqlHash={} env={} minutes={}: {}",
                    sqlHash, env, minutes, e.getMessage());
            return null;
        }
    }

    /**
     * 把规则引擎结果落库。返回新插入的 itemId。
     *
     * @param result 规则引擎输出
     * @param source SQL 来源信息（batch 扫描时填，单条分析可全为 null）
     * @return 新 itemId
     */
    public long insertFromResult(SqlInspectionResult result, SqlSource source) {
        String involvedTables = result.getTableRatings() == null ? null
                : result.getTableRatings().stream()
                        .map(tr -> tr.getTable())
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.joining(","));

        String tableRatingsJson = toJson(result.getTableRatings());
        String warningsJson     = toJson(result.getWarnings());

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            // 基础 SQL 字段
            ps.setString(i++, result.getSqlHash());
            ps.setString(i++, result.getSql());
            ps.setString(i++, source == null || source.sqlKind == null ? "UNKNOWN" : source.sqlKind);
            ps.setString(i++, result.getEnv() == null ? "" : result.getEnv());
            if (source != null && source.taskId != null) {
                ps.setLong(i++, source.taskId);
            } else {
                ps.setNull(i++, java.sql.Types.BIGINT);
            }
            ps.setString(i++, source == null ? null : source.projectName);
            ps.setString(i++, source == null ? null : source.classFqn);
            ps.setString(i++, source == null ? null : source.methodName);
            ps.setString(i++, source == null ? null : source.sourceFile);
            // 规则引擎结果
            ps.setString(i++, result.getOverallRating() == null ? null : result.getOverallRating().name());
            ps.setString(i++, result.getOverallRatingLabel());
            ps.setString(i++, involvedTables);
            ps.setString(i++, tableRatingsJson);
            if (result.getRuleEngineElapsedMs() <= 0) {
                ps.setNull(i++, java.sql.Types.INTEGER);
            } else {
                ps.setLong(i++, result.getRuleEngineElapsedMs());
            }
            ps.setString(i++, "DONE");
            ps.setString(i++, warningsJson);

            // V8：runtime_rating
            ps.setString(i++, result.getRuntimeRating() == null ? null : result.getRuntimeRating().name());
            ps.setString(i++, result.getRuntimeRatingLabel());
            ps.setInt(i++, result.isDisagreement() ? 1 : 0);
            ps.setString(i++, result.getDisagreementReason());

            // V8：EXPLAIN
            ps.setString(i++, result.getExplainPlanJson());
            if (result.getExplainTopCost() == null) ps.setNull(i++, java.sql.Types.DOUBLE);
            else ps.setDouble(i++, result.getExplainTopCost());
            if (result.getExplainEstRows() == null) ps.setNull(i++, java.sql.Types.BIGINT);
            else ps.setLong(i++, result.getExplainEstRows());
            if (result.getExplainHasSeqScan() == null) ps.setNull(i++, java.sql.Types.TINYINT);
            else ps.setInt(i++, result.getExplainHasSeqScan() ? 1 : 0);
            if (result.getExplainElapsedMs() == null) ps.setNull(i++, java.sql.Types.INTEGER);
            else ps.setLong(i++, result.getExplainElapsedMs());
            ps.setString(i++, result.getExplainError());

            // V8：表元数据 JSON
            ps.setString(i++, result.getTableStatsJson());
            ps.setString(i++, result.getColumnStatsJson());
            ps.setString(i++, result.getTableDdlJson());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        long id = key == null ? -1L : key.longValue();
        log.debug("[dii-item-dao] 插入 item id={} sqlHash={} rating={} env={}",
                id, result.getSqlHash(), result.getOverallRatingLabel(), result.getEnv());
        return id;
    }

    /** 查单条详情，返回 Map（字段名 → 值）。前端/调试接口使用。 */
    public Map<String, Object> loadById(long id) {
        try {
            return jdbc.queryForMap(SELECT_BY_ID_SQL, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按条件分页查询，用于报表/列表接口。
     * 所有参数可选；为空/null 时不参与过滤。
     *
     * @param env       环境过滤
     * @param rating    评级过滤：POOR/GOOD/EXCELLENT/NOT_APPLICABLE
     * @param tableName 表名过滤（模糊匹配 involved_tables）
     * @param taskId    任务过滤
     * @param limit     每页条数（默认 50，最大 500）
     * @param offset    偏移量
     */
    public List<Map<String, Object>> search(String env,
                                            String rating,
                                            String tableName,
                                            Long taskId,
                                            int limit,
                                            int offset) {
        StringBuilder sb = new StringBuilder(
                "SELECT id, sql_hash, sql_kind, env, task_id, project_name, class_fqn, method_name, " +
                "       overall_rating, rating_label, involved_tables, rule_engine_elapsed_ms, " +
                "       status, created_at " +
                "  FROM dii_analysis_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND env = ?"); args.add(env.trim());
        }
        if (rating != null && !rating.isBlank()) {
            sb.append(" AND overall_rating = ?"); args.add(rating.trim().toUpperCase());
        }
        if (tableName != null && !tableName.isBlank()) {
            sb.append(" AND involved_tables LIKE ?"); args.add("%" + tableName.trim() + "%");
        }
        if (taskId != null) {
            sb.append(" AND task_id = ?"); args.add(taskId);
        }
        int eff = Math.min(Math.max(limit, 1), 500);
        int off = Math.max(offset, 0);
        sb.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(eff);
        args.add(off);
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    /**
     * 标记 item 为"待 LLM 分析"。
     * 触发条件（由上层 SqlInspectionService 判断）：
     * rating ≤ GOOD / disagreement=true / schema drift / llm 建议作用 scope=TABLE 的高优先级场景。
     */
    public int markLlmPending(long itemId) {
        return jdbc.update(
                "UPDATE dii_analysis_item SET llm_pending=1, llm_status='PENDING' " +
                "WHERE id=? AND (llm_status IS NULL OR llm_status='SKIPPED')",
                itemId);
    }

    /**
     * 强制把 item 置为 PENDING（不挑现状态）。用于"重新分析"按钮异步触发：
     * 控制器先同步打 PENDING，再异步交后台跑 LLM；前端立刻轮询到 PENDING，蒙版稳定显示。
     * 同时清空旧 llm_error / llm_summary 等字段，避免界面看到"PENDING 但仍展示旧错误信息"。
     */
    public int forceMarkLlmPending(long itemId) {
        return jdbc.update(
                "UPDATE dii_analysis_item SET llm_pending=1, llm_status='PENDING', " +
                "       llm_error=NULL, llm_summary=NULL, llm_findings_json=NULL, " +
                "       llm_suggestions_json=NULL, llm_confidence=NULL, " +
                "       llm_called_at=NOW() WHERE id=?",
                itemId);
    }

    /**
     * 重跑场景：用新的规则引擎 + EXPLAIN 结果 UPDATE 已存在行（不新建行）。
     * 同步把 LLM 字段清空，让上层接着重跑 LLM。
     */
    public int updateInspectionFields(long itemId, SqlInspectionResult r) {
        String involvedTables = r.getTableRatings() == null ? null
                : r.getTableRatings().stream()
                        .map(tr -> tr.getTable())
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.joining(","));
        String tableRatingsJson = toJson(r.getTableRatings());
        String warningsJson     = toJson(r.getWarnings());

        return jdbc.update(
                "UPDATE dii_analysis_item SET " +
                "  sql_hash = ?, sql_text = ?, " +
                "  overall_rating = ?, rating_label = ?, involved_tables = ?, table_ratings_json = ?, " +
                "  rule_engine_elapsed_ms = ?, status = 'DONE', warnings_json = ?, " +
                "  runtime_rating = ?, runtime_rating_label = ?, disagreement = ?, disagreement_reason = ?, " +
                "  explain_plan = ?, explain_top_cost = ?, explain_est_rows = ?, " +
                "  explain_has_seq_scan = ?, explain_elapsed_ms = ?, explain_error = ?, " +
                "  table_stats_json = ?, column_stats_json = ?, table_ddl_json = ?, " +
                // 重置 LLM 字段，让后续重跑覆盖
                "  llm_pending = 1, llm_status = NULL, llm_summary = NULL, " +
                "  llm_findings_json = NULL, llm_suggestions_json = NULL, " +
                "  llm_confidence = NULL, llm_model = NULL, llm_prompt_version = NULL, " +
                "  llm_elapsed_ms = NULL, llm_error = NULL, llm_called_at = NULL " +
                "WHERE id = ?",
                r.getSqlHash(), r.getSql(),
                r.getOverallRating() == null ? null : r.getOverallRating().name(),
                r.getOverallRatingLabel(), involvedTables, tableRatingsJson,
                r.getRuleEngineElapsedMs() <= 0 ? null : r.getRuleEngineElapsedMs(),
                warningsJson,
                r.getRuntimeRating() == null ? null : r.getRuntimeRating().name(),
                r.getRuntimeRatingLabel(),
                r.isDisagreement() ? 1 : 0,
                r.getDisagreementReason(),
                r.getExplainPlanJson(), r.getExplainTopCost(), r.getExplainEstRows(),
                r.getExplainHasSeqScan() == null ? null : (r.getExplainHasSeqScan() ? 1 : 0),
                r.getExplainElapsedMs(), r.getExplainError(),
                r.getTableStatsJson(), r.getColumnStatsJson(), r.getTableDdlJson(),
                itemId);
    }

    /** LLM 分析失败或解析失败，只更新状态与错误。 */
    public int updateLlmStatusOnly(long itemId, String status, String errorMsg) {
        return jdbc.update(
                "UPDATE dii_analysis_item SET " +
                " llm_pending = CASE WHEN ?='DONE' OR ?='SKIPPED' THEN 0 ELSE llm_pending END, " +
                " llm_status=?, llm_error=?, llm_called_at=NOW() " +
                "WHERE id=?",
                status, status, status, truncate(errorMsg, 1000), itemId);
    }

    /** LLM 分析成功：一次性写入所有 LLM 字段。 */
    public int updateLlmFull(long itemId, String status,
                             String summary, String findingsJson, String suggestionsJson,
                             String confidence, String model, String promptVersion,
                             long elapsedMs, String error) {
        return jdbc.update(
                "UPDATE dii_analysis_item SET " +
                " llm_pending=0, llm_status=?, " +
                " llm_summary=?, llm_findings_json=?, llm_suggestions_json=?, " +
                " llm_confidence=?, llm_model=?, llm_prompt_version=?, " +
                " llm_elapsed_ms=?, llm_error=?, llm_called_at=NOW() " +
                "WHERE id=?",
                status, truncate(summary, 500), findingsJson, suggestionsJson,
                confidence, model, promptVersion,
                (int) Math.min(elapsedMs, Integer.MAX_VALUE),
                truncate(error, 1000),
                itemId);
    }

    /**
     * 查"待 LLM 分析"列表。
     *
     * <p><b>硬过滤</b>：只捡 {@code runtime_rating IN ('POOR','GOOD')} 的 item。
     * 即使历史数据把 llm_pending=1 错标到了 EXCELLENT / NULL 行上，这里也兜底不捡，
     * 防止把时间浪费在不需要 LLM 解读的行上。
     *
     * @param env       过滤环境，可 null
     * @param taskId    过滤任务，可 null
     * @param onlyFailed true=只要重跑失败的；false=只要 llm_pending=1 还没跑的
     * @param limit     上限
     */
    public List<Long> findPendingLlmIds(String env, Long taskId, boolean onlyFailed, int limit) {
        StringBuilder sb = new StringBuilder(
                "SELECT id FROM dii_analysis_item WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (onlyFailed) {
            sb.append(" AND llm_status='FAILED' ");
        } else {
            sb.append(" AND llm_pending=1 AND (llm_status IS NULL OR llm_status='PENDING') ");
        }
        // 最后一公里防护：只有 runtime_rating 是 POOR 或 GOOD 才跑 LLM
        sb.append(" AND runtime_rating IN ('POOR','GOOD') ");
        if (env != null && !env.isBlank()) {
            sb.append(" AND env=? "); args.add(env);
        }
        if (taskId != null) {
            sb.append(" AND task_id=? "); args.add(taskId);
        }
        sb.append(" ORDER BY id ASC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 10000));
        return jdbc.queryForList(sb.toString(), Long.class, args.toArray());
    }

    /**
     * 表维度聚合视图：某张表上所有 SQL 的 LLM 建议去重 + 推荐次数。
     * 用于"DBA 想一眼看这张表要做哪些 DDL"的场景。
     */
    public List<Map<String, Object>> tableAdviceRollup(String env, String table) {
        // 按 DDL 去重 + 计数：不用 MySQL 的 JSON_EXTRACT（版本兼容问题），统一从 Java 侧聚合
        String sql = "SELECT id, sql_hash, class_fqn, overall_rating, runtime_rating, " +
                "       llm_summary, llm_suggestions_json " +
                "  FROM dii_analysis_item " +
                " WHERE env=? AND involved_tables LIKE ? AND llm_status='DONE' " +
                " ORDER BY id DESC LIMIT 2000";
        return jdbc.queryForList(sql, env, "%" + table + "%");
    }

    /** 补关联 taskId + 来源信息（batch 用），不覆盖已有规则引擎结果。 */
    public int linkToTask(long itemId, long taskId,
                          String projectName, String classFqn, String methodName, String sourceFile) {
        return jdbc.update(
                "UPDATE dii_analysis_item SET task_id=?, project_name=COALESCE(?, project_name), " +
                "       class_fqn=COALESCE(?, class_fqn), method_name=COALESCE(?, method_name), " +
                "       source_file=COALESCE(?, source_file) WHERE id=?",
                taskId, projectName, classFqn, methodName, sourceFile, itemId);
    }

    /** 记录批量过程中某条 SQL 的失败。确保即便规则引擎挂了，失败痕迹也写进去。 */
    public long insertFailed(long taskId,
                             String sqlHash, String sqlText, String env,
                             String projectName, String classFqn, String sourceFile,
                             String errorMsg) {
        String ins = "INSERT INTO dii_analysis_item (sql_hash, sql_text, sql_kind, env, task_id, " +
                     "  project_name, class_fqn, source_file, status, error_msg) " +
                     "VALUES (?, ?, 'UNKNOWN', ?, ?, ?, ?, ?, 'FAILED', ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, sqlHash);
                ps.setString(2, sqlText);
                ps.setString(3, env);
                ps.setLong(4, taskId);
                ps.setString(5, projectName);
                ps.setString(6, classFqn);
                ps.setString(7, sourceFile);
                ps.setString(8, truncate(errorMsg, 1000));
                return ps;
            }, keyHolder);
            Number k = keyHolder.getKey();
            return k == null ? -1L : k.longValue();
        } catch (Exception e) {
            log.error("[dii-item-dao] 插入 FAILED 记录失败 taskId={} sqlHash={}: {}",
                    taskId, sqlHash, e.getMessage());
            return -1L;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** 查与 {@link #search} 同过滤条件下的总行数，便于分页展示。 */
    public long count(String env, String rating, String tableName, Long taskId) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM dii_analysis_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) { sb.append(" AND env = ?"); args.add(env.trim()); }
        if (rating != null && !rating.isBlank()) { sb.append(" AND overall_rating = ?"); args.add(rating.trim().toUpperCase()); }
        if (tableName != null && !tableName.isBlank()) { sb.append(" AND involved_tables LIKE ?"); args.add("%" + tableName.trim() + "%"); }
        if (taskId != null) { sb.append(" AND task_id = ?"); args.add(taskId); }
        Long total = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * "有问题"视图：仅返回 EXPLAIN 报错或 LLM 已有结论（DONE/FAILED）的行，
     * 并额外带上前端列表/建议列展示所需的长字段（sql_text、llm_* 等）。
     *
     * <p>过滤逻辑（OR）：
     * <pre>
     *   (explain_error IS NOT NULL AND explain_error &lt;&gt; '')
     *   OR llm_status IN ('DONE','PENDING','FAILED')
     * </pre>
     *
     * <p>典型调用场景：前端"SQL 分析"页切到某个任务后，只看真正"有料"的行。
     *
     * @param env     环境过滤（必填，避免全库扫）
     * @param taskId  任务过滤（必填，避免历史积压把列表压爆）
     * @param limit   每页条数（默认 50，最大 500）
     * @param offset  偏移量
     * @return Map 列表，字段与 SELECT 列一一对应
     */
    public List<Map<String, Object>> searchIssuesOnly(String env,
                                                      Long taskId,
                                                      int limit,
                                                      int offset) {
        StringBuilder sb = new StringBuilder(
                "SELECT id, sql_hash, sql_kind, env, task_id, project_name, class_fqn, method_name, " +
                "       overall_rating, rating_label, involved_tables, rule_engine_elapsed_ms, " +
                "       status, created_at, sql_text, explain_error, " +
                "       llm_status, llm_error, llm_summary, " +
                "       llm_findings_json, llm_suggestions_json, " +
                "       llm_model, llm_prompt_version, llm_elapsed_ms, llm_called_at, llm_confidence " +
                "  FROM dii_analysis_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND env = ?"); args.add(env.trim());
        }
        if (taskId != null) {
            sb.append(" AND task_id = ?"); args.add(taskId);
        }
        // OR 组合：EXPLAIN 报错 或 LLM 有终态
        sb.append(" AND ((explain_error IS NOT NULL AND explain_error <> '')")
          .append(" OR llm_status IN ('DONE','PENDING','FAILED'))");
        int eff = Math.min(Math.max(limit, 1), 500);
        int off = Math.max(offset, 0);
        sb.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(eff);
        args.add(off);
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    /**
     * {@link #searchIssuesOnly} 的 keyset 分页版，专用于全量导出流式拉取。
     *
     * <p>用 {@code id < :afterId} 作为游标，绕开 OFFSET 在并发写入下的漂移问题
     * （并发 INSERT 或 {@code llm_status} 翻态会让 {@code ORDER BY id DESC + OFFSET} 出现
     * 重复行 / 漏行）。WHERE 过滤条件与 {@link #searchIssuesOnly} 完全一致。
     *
     * @param env     环境过滤
     * @param taskId  任务过滤
     * @param afterId 游标：只返回 {@code id < afterId} 的行；首批传 {@code null}
     * @param limit   单批条数（1~500）
     * @return 按 id DESC 排序的一批行；调用方取本批最后一行的 id 作为下一批的 afterId
     */
    public List<Map<String, Object>> searchIssuesOnlyAfter(String env,
                                                           Long taskId,
                                                           Long afterId,
                                                           int limit) {
        StringBuilder sb = new StringBuilder(
                "SELECT id, sql_hash, sql_kind, env, task_id, project_name, class_fqn, method_name, " +
                "       overall_rating, rating_label, involved_tables, rule_engine_elapsed_ms, " +
                "       status, created_at, sql_text, explain_error, " +
                "       llm_status, llm_error, llm_summary, " +
                "       llm_findings_json, llm_suggestions_json, " +
                "       llm_model, llm_prompt_version, llm_elapsed_ms, llm_called_at, llm_confidence " +
                "  FROM dii_analysis_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND env = ?"); args.add(env.trim());
        }
        if (taskId != null) {
            sb.append(" AND task_id = ?"); args.add(taskId);
        }
        sb.append(" AND ((explain_error IS NOT NULL AND explain_error <> '')")
          .append(" OR llm_status IN ('DONE','PENDING','FAILED'))");
        if (afterId != null) {
            sb.append(" AND id < ?"); args.add(afterId);
        }
        int eff = Math.min(Math.max(limit, 1), 500);
        sb.append(" ORDER BY id DESC LIMIT ?");
        args.add(eff);
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    /**
     * 一次 SQL 算出"问题列表"的 4 个 KPI：总数 / 数据库报错 / AI 分析完成 / AI 分析失败。
     *
     * <p>替代之前"前端全量拉所有 items 再 group by"的方案，O(1) 网络请求 + O(1) SQL，
     * 即便单 task 有几万条 issues 也不影响 KPI 渲染速度。
     *
     * <p>返回 Map 字段：
     * <ul>
     *   <li>{@code total}         总问题数</li>
     *   <li>{@code explainError}  EXPLAIN 报错数（explain_error 非空）</li>
     *   <li>{@code llmFindings}   AI 分析完成数（llm_status=DONE 且 explain_error 为空）</li>
     *   <li>{@code llmError}      AI 分析失败数（llm_status=FAILED 且 explain_error 为空）</li>
     * </ul>
     */
    public Map<String, Long> getIssuesStats(String env, Long taskId) {
        StringBuilder sb = new StringBuilder(
                "SELECT " +
                "  SUM(CASE WHEN explain_error IS NOT NULL AND explain_error<>'' THEN 1 ELSE 0 END) AS explain_error_cnt, " +
                "  SUM(CASE WHEN (explain_error IS NULL OR explain_error='') AND llm_status='DONE'    THEN 1 ELSE 0 END) AS llm_findings_cnt, " +
                "  SUM(CASE WHEN (explain_error IS NULL OR explain_error='') AND llm_status='PENDING' THEN 1 ELSE 0 END) AS llm_pending_cnt, " +
                "  SUM(CASE WHEN (explain_error IS NULL OR explain_error='') AND llm_status='FAILED'  THEN 1 ELSE 0 END) AS llm_error_cnt, " +
                "  COUNT(*) AS total_cnt " +
                " FROM dii_analysis_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND env = ?"); args.add(env.trim());
        }
        if (taskId != null) {
            sb.append(" AND task_id = ?"); args.add(taskId);
        }
        sb.append(" AND ((explain_error IS NOT NULL AND explain_error<>'')")
          .append(" OR llm_status IN ('DONE','PENDING','FAILED'))");

        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(sb.toString(), args.toArray());
        } catch (Exception e) {
            log.warn("[dii-item-dao] 查询 issues stats 失败 env={} taskId={}: {}",
                    env, taskId, e.getMessage());
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

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0L; }
    }

    /** 与 {@link #searchIssuesOnly} 同过滤条件下的总行数。 */
    public long countIssuesOnly(String env, Long taskId) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM dii_analysis_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (env != null && !env.isBlank()) { sb.append(" AND env = ?"); args.add(env.trim()); }
        if (taskId != null) { sb.append(" AND task_id = ?"); args.add(taskId); }
        sb.append(" AND ((explain_error IS NOT NULL AND explain_error <> '')")
          .append(" OR llm_status IN ('DONE','PENDING','FAILED'))");
        Long total = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** SQL 来源元信息。batch 扫描时必填，单条分析可全 null。 */
    public static class SqlSource {
        public Long taskId;
        public String sqlKind;        // SELECT/UPDATE/DELETE/INSERT_VALUES/INSERT_SELECT/UNKNOWN
        public String projectName;
        public String classFqn;
        public String methodName;
        public String sourceFile;

        public SqlSource() {}

        public static SqlSource ofKind(String kind) {
            SqlSource s = new SqlSource();
            s.sqlKind = kind;
            return s;
        }
    }
}
