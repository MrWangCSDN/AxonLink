package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
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

    /** 更新运行时计数器。 */
    public void updateCounters(long taskId, int analyzed, int failed, int skipped) {
        try {
            jdbc.update(UPDATE_COUNTERS, analyzed, failed, skipped, taskId);
        } catch (Exception e) {
            log.warn("[dii-task] 更新计数器失败 taskId={}: {}", taskId, e.getMessage());
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
        int eff = Math.min(Math.max(limit, 1), 500);
        return jdbc.queryForList(LIST, eff, Math.max(offset, 0));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
