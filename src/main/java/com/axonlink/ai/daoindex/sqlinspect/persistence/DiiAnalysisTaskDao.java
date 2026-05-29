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
import java.util.List;
import java.util.Map;

/**
 * {@code dii_analysis_task} 表读写。
 *
 * 简单直接：START / UPDATE_COUNTERS / DONE / FAILED。
 */
@Repository
public class DiiAnalysisTaskDao {

    private static final Logger log = LoggerFactory.getLogger(DiiAnalysisTaskDao.class);

    private static final String INSERT =
            "INSERT INTO dii_analysis_task (task_no, env, status, total_sqls, analyzed_sqls, " +
            " failed_sqls, skipped_sqls, trigger_type, owner) " +
            "VALUES (?, ?, 'RUNNING', ?, 0, 0, 0, ?, ?)";

    private static final String UPDATE_COUNTERS =
            "UPDATE dii_analysis_task " +
            "   SET analyzed_sqls = ?, failed_sqls = ?, skipped_sqls = ?, updated_at = NOW() " +
            " WHERE id = ?";

    // scanAll 已移到异步线程执行：建 task 时 total_sqls 还是未知的（先填 0），
    // 等异步扫描+去重完成后再用本语句回填真实候选数，列表/进度才准确。
    private static final String UPDATE_TOTAL =
            "UPDATE dii_analysis_task SET total_sqls = ?, updated_at = NOW() WHERE id = ?";

    private static final String MARK_DONE =
            "UPDATE dii_analysis_task SET status = 'DONE', analyzed_sqls = ?, " +
            " failed_sqls = ?, skipped_sqls = ?, updated_at = NOW() WHERE id = ?";

    private static final String MARK_FAILED =
            "UPDATE dii_analysis_task SET status = 'FAILED', error_msg = ?, updated_at = NOW() WHERE id = ?";

    private static final String SELECT_BY_ID =
            "SELECT id, task_no, env, status, total_sqls, analyzed_sqls, failed_sqls, skipped_sqls, " +
            "       trigger_type, owner, error_msg, created_at, updated_at " +
            "  FROM dii_analysis_task WHERE id = ?";

    private static final String LIST =
            "SELECT id, task_no, env, status, total_sqls, analyzed_sqls, failed_sqls, skipped_sqls, " +
            "       trigger_type, owner, error_msg, created_at, updated_at " +
            "  FROM dii_analysis_task ORDER BY id DESC LIMIT ? OFFSET ?";

    // 「一天一次·覆盖式」：查当天（结果库 MySQL 本地时区）已存在的巡检任务。
    // CURDATE() 取数据库当天日期，DATE(created_at) 把 created_at 截断到日，
    // 只返回 id + status 两列即可（service 层据此判断是否拒绝/删旧）。
    private static final String FIND_TODAY =
            "SELECT id, status FROM dii_analysis_task " +
            " WHERE env = ? AND DATE(created_at) = CURDATE()";

    // 「覆盖式」真删：物理删除指定任务行（配合 item 表 deleteByTaskId 一起用，
    // 保证每 env 每天恰好 1 条 task）。返回受影响行数（正常 0 或 1）。
    private static final String DELETE_BY_ID =
            "DELETE FROM dii_analysis_task WHERE id = ?";

    private final JdbcTemplate jdbc;

    public DiiAnalysisTaskDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /** 创建新任务。返回 taskId。 */
    public long startTask(String taskNo, String env, int totalSqls, String triggerType, String owner) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, taskNo);
            ps.setString(2, env);
            ps.setInt(3, totalSqls);
            ps.setString(4, triggerType);
            ps.setString(5, owner);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? -1 : key.longValue();
        log.info("[dii-task] 创建任务 id={} taskNo={} env={} total={} trigger={}",
                id, taskNo, env, totalSqls, triggerType);
        return id;
    }

    /**
     * 查当天（结果库本地日期）该 env 下已存在的巡检任务。
     *
     * <p>「一天一次·覆盖式」用：service 层在新建任务前先调本方法——
     * <ul>
     *   <li>有 status=RUNNING 的 → 拒绝本次触发（删正在跑的任务会让其异步线程写孤儿数据）</li>
     *   <li>全是 DONE/FAILED（或空）→ 逐条删旧 task + 其全部 item，实现覆盖</li>
     * </ul>
     *
     * @param env 目标库 env
     * @return 每行含 {@code id}（long）与 {@code status}（String）两列；当天无任务返回空列表
     */
    public List<Map<String, Object>> findTodayTasks(String env) {
        return jdbc.queryForList(FIND_TODAY, env);
    }

    /**
     * 物理删除指定任务行（「覆盖式」真删，非软删）。
     *
     * <p>调用方须先 {@link DiiAnalysisItemDao#deleteByTaskId(long)} 删掉该任务的
     * 全部 item，再调本方法删 task 行，避免留下「无主 item」（孤儿数据）。
     *
     * @param taskId 任务 id
     * @return 受影响行数（正常 0 或 1）
     */
    public int deleteById(long taskId) {
        return jdbc.update(DELETE_BY_ID, taskId);
    }

    /**
     * 回填巡检候选总数。
     *
     * <p>因为 {@code scanner.scanAll} 已从 HTTP 请求线程移到异步线程执行，
     * 建 task 记录时还不知道 total_sqls（先建成 RUNNING、total=0），
     * 异步扫描+去重完成后调用本方法把真实总数写回去。
     */
    public void updateTotal(long taskId, int totalSqls) {
        try {
            jdbc.update(UPDATE_TOTAL, totalSqls, taskId);
        } catch (Exception e) {
            log.warn("[dii-task] 回填 total_sqls 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 更新运行时计数器。 */
    public void updateCounters(long taskId, int analyzed, int failed, int skipped) {
        try {
            jdbc.update(UPDATE_COUNTERS, analyzed, failed, skipped, taskId);
        } catch (Exception e) {
            log.warn("[dii-task] 更新计数器失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 登记 EXPLAIN 巡检阶段完成时间（V18）。
     * <p>由 {@code BatchInspectionRunner} 在 item+pool 两阶段 EXPLAIN 跑完、LLM 之前调用。
     * 任务此时仍保持 RUNNING（不改 status），只盖一个时间戳供「耗时」口径用。
     */
    public void markInspectDone(long taskId) {
        try {
            jdbc.update("UPDATE dii_analysis_task SET inspect_done_at = NOW() WHERE id = ?", taskId);
            log.info("[dii-task] 巡检(EXPLAIN)阶段完成 id={}，开始 LLM 回填（耗时已定格）", taskId);
        } catch (Exception e) {
            log.warn("[dii-task] 登记 inspect_done_at 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 标记任务完成（即便过程中有失败也算 DONE，只是 failedSqls 计数 > 0）。 */
    public void markDone(long taskId, int analyzed, int failed, int skipped) {
        try {
            jdbc.update(MARK_DONE, analyzed, failed, skipped, taskId);
            log.info("[dii-task] 任务完成 id={} analyzed={} failed={} skipped={}",
                    taskId, analyzed, failed, skipped);
        } catch (Exception e) {
            log.error("[dii-task] 标记 DONE 失败 taskId={}: {}", taskId, e.getMessage(), e);
        }
    }

    /** 标记任务本身失败（只在扫描阶段出问题等"任务级"故障时调用）。 */
    public void markFailed(long taskId, String errorMsg) {
        try {
            jdbc.update(MARK_FAILED, truncate(errorMsg, 1000), taskId);
            log.error("[dii-task] 任务失败 id={}: {}", taskId, errorMsg);
        } catch (Exception e) {
            log.error("[dii-task] 标记 FAILED 失败 taskId={}: {}", taskId, e.getMessage(), e);
        }
    }

    public Map<String, Object> findById(long taskId) {
        try {
            return jdbc.queryForMap(SELECT_BY_ID, taskId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<Map<String, Object>> list(int limit, int offset) {
        return list(limit, offset, null, null);
    }

    /**
     * 列出任务，可选 env / status 过滤；每行带回 4 项 dii_analysis_item 聚合统计。
     *
     * <p>聚合统计字段（<b>全集口径</b>，覆盖该任务下所有 SQL，不再仅限 POOR）：
     * <ul>
     *   <li>{@code explain_err}   — explain_error 非空的数量（执行报错数）</li>
     *   <li>{@code llm_done}      — llm_status='DONE' 且 llm_suggestions_json 非空（AI 整改）</li>
     *   <li>{@code llm_failed}    — llm_status='FAILED'（AI 报错）</li>
     *   <li>{@code llm_running}   — llm_status='PENDING' 或 llm_pending=1（保留供后续诊断）</li>
     * </ul>
     *
     * <p>"巡检总数"直接用 task 表的 {@code total_sqls} 字段，无需聚合。
     *
     * <p>{@code status} 参数支持特殊值 {@code RUNNING_OR_PENDING}，DAO 翻译为
     * {@code WHERE status IN ('PENDING','RUNNING')}，方便前端"进行中"过滤。
     *
     * <p><b>性能兜底：</b>子查询全表扫 {@code dii_analysis_item} 然后 GROUP BY task_id。
     * 如果实测慢可加索引：
     * <pre>{@code
     *   CREATE INDEX idx_item_task ON dii_analysis_item(task_id);
     * }</pre>
     *
     * @param limit  限制条数（1~500）
     * @param offset 偏移
     * @param env    可选环境过滤（null 不过滤）
     * @param status 可选状态过滤（null 不过滤；除标准 PENDING/RUNNING/DONE/FAILED 外
     *               支持特殊值 RUNNING_OR_PENDING）
     */
    public List<Map<String, Object>> list(int limit, int offset, String env, String status) {
        int eff = Math.min(Math.max(limit, 1), 500);
        StringBuilder sb = new StringBuilder(
                "SELECT t.id, t.task_no, t.env, t.status, t.total_sqls, t.analyzed_sqls, " +
                "       t.failed_sqls, t.skipped_sqls, t.trigger_type, t.owner, " +
                "       t.error_msg, t.created_at, t.updated_at, t.inspect_done_at, " +
                // V2 修复：4 个统计列都 = item 聚合 + 池聚合（之前只有 pool_count 合并了）
                "       (COALESCE(s.explain_err, 0) + COALESCE(p.pool_explain_err, 0)) AS explain_err, " +
                "       (COALESCE(s.llm_done,    0) + COALESCE(p.pool_llm_done,    0)) AS llm_done, " +
                "       (COALESCE(s.llm_failed,  0) + COALESCE(p.pool_llm_failed,  0)) AS llm_failed, " +
                "       (COALESCE(s.llm_running, 0) + COALESCE(p.pool_llm_running, 0)) AS llm_running, " +
                // 巡检总数中并入池总数（前端 mergedTotal = total_sqls + pool_count）
                "       COALESCE(p.pool_count,  0) AS pool_count " +
                "  FROM dii_analysis_task t " +
                "  LEFT JOIN ( " +
                "    SELECT task_id, " +
                "           SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' " +
                "                    THEN 1 ELSE 0 END) AS explain_err, " +
                "           SUM(CASE WHEN llm_status='DONE' " +
                "                     AND llm_suggestions_json IS NOT NULL " +
                "                     AND llm_suggestions_json NOT IN ('','[]') " +
                "                    THEN 1 ELSE 0 END) AS llm_done, " +
                "           SUM(CASE WHEN llm_status='FAILED' THEN 1 ELSE 0 END) AS llm_failed, " +
                "           SUM(CASE WHEN llm_status='PENDING' OR llm_pending=1 " +
                "                    THEN 1 ELSE 0 END) AS llm_running " +
                "      FROM dii_analysis_item " +
                "     GROUP BY task_id " +
                "  ) s ON s.task_id = t.id " +
                // V2：池聚合补齐 explain_err / llm_done / llm_failed / llm_running 四档
                // （含未标 env 的池行——env IS NULL/'' 视为对所有 env 可见，与看板口径一致）
                "  LEFT JOIN ( " +
                "    SELECT env AS p_env, COUNT(*) AS pool_count, " +
                "           SUM(CASE WHEN explain_error IS NOT NULL AND explain_error <> '' " +
                "                    THEN 1 ELSE 0 END) AS pool_explain_err, " +
                "           SUM(CASE WHEN llm_status='DONE' " +
                "                     AND llm_suggestions_json IS NOT NULL " +
                "                     AND llm_suggestions_json NOT IN ('','[]') " +
                "                    THEN 1 ELSE 0 END) AS pool_llm_done, " +
                "           SUM(CASE WHEN llm_status='FAILED' THEN 1 ELSE 0 END) AS pool_llm_failed, " +
                "           SUM(CASE WHEN llm_status='PENDING' THEN 1 ELSE 0 END) AS pool_llm_running " +
                "      FROM dii_sql_pool " +
                "     WHERE env IS NOT NULL AND env <> '' " +
                "     GROUP BY env " +
                "  ) p ON p.p_env = t.env " +
                " WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND t.env = ?");
            args.add(env.trim());
        }
        if (status != null && !status.isBlank()) {
            String st = status.trim();
            if ("RUNNING_OR_PENDING".equalsIgnoreCase(st)) {
                // 前端"进行中"过滤的特殊值，DAO 翻译为 IN 子句
                sb.append(" AND t.status IN ('PENDING','RUNNING')");
            } else {
                sb.append(" AND t.status = ?");
                args.add(st);
            }
        }
        sb.append(" ORDER BY t.id DESC LIMIT ? OFFSET ?");
        args.add(eff);
        args.add(Math.max(offset, 0));
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    /**
     * 按相同 env / status 过滤条件统计总数（不分页），供前端分页器使用。
     *
     * <p>跟 {@link #list(int, int, String, String)} 用完全相同的 WHERE 子句
     * （含 RUNNING_OR_PENDING 特殊值翻译），保证 total 与 items 的总数一致。
     *
     * @param env    可选环境过滤
     * @param status 可选状态过滤（含特殊值 RUNNING_OR_PENDING）
     * @return 符合条件的任务总数
     */
    public long countAll(String env, String status) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM dii_analysis_task WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (env != null && !env.isBlank()) {
            sb.append(" AND env = ?");
            args.add(env.trim());
        }
        if (status != null && !status.isBlank()) {
            String st = status.trim();
            if ("RUNNING_OR_PENDING".equalsIgnoreCase(st)) {
                sb.append(" AND status IN ('PENDING','RUNNING')");
            } else {
                sb.append(" AND status = ?");
                args.add(st);
            }
        }
        Long n = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return n == null ? 0L : n;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
