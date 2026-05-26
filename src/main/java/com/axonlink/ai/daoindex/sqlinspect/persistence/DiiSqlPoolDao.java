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
     * 列出全部不同的 project_name（去重 + 排序），用于前端下拉框。
     */
    public List<String> listDistinctProjects() {
        return jdbc.queryForList(
                "SELECT DISTINCT project_name FROM dii_sql_pool " +
                " WHERE project_name IS NOT NULL AND project_name <> '' " +
                " ORDER BY project_name",
                String.class);
    }
}
