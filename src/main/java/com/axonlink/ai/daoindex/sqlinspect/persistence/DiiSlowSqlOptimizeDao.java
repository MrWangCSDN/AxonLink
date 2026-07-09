package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code dii_slow_sql_optimization} 真身表读写 DAO（结果库 MySQL）。
 * <p>跨轮次身份键 = (service_name, abstract_hash)，整轮 DELETE→INSERT 不影响此表。
 * 状态：OPTIMIZED 已优化 / REGRESSED 优化未生效。
 */
@Repository
public class DiiSlowSqlOptimizeDao {

    public static final String STATUS_OPTIMIZED = "OPTIMIZED";
    public static final String STATUS_REGRESSED = "REGRESSED";

    private final JdbcTemplate jdbc;

    public DiiSlowSqlOptimizeDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /**
     * 打标「已优化」：按 (service_name, abstract_hash) upsert，回到 OPTIMIZED 并清 reappeared。
     * 记录优化人工号({@code optimizedBy})、姓名({@code optimizedByName})、优化内容({@code note})。
     */
    public void upsertOptimized(String serviceName, String abstractHash, String optimizedRound,
                                String optimizedBy, String optimizedByName, String note, LocalDateTime now) {
        Timestamp ts = Timestamp.valueOf(now);
        jdbc.update(
                "INSERT INTO dii_slow_sql_optimization " +
                " (service_name, abstract_hash, status, optimized_round, reappeared_round, " +
                "  optimized_by, optimized_by_name, optimize_note, optimized_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "  status = VALUES(status), optimized_round = VALUES(optimized_round), " +
                "  reappeared_round = NULL, optimized_by = VALUES(optimized_by), " +
                "  optimized_by_name = VALUES(optimized_by_name), optimize_note = VALUES(optimize_note), " +
                "  updated_at = VALUES(updated_at)",
                serviceName, abstractHash, STATUS_OPTIMIZED, optimizedRound,
                optimizedBy, optimizedByName, note, ts, ts);
    }

    /** 取消标记：物删真身行，返回删除行数。 */
    public int delete(String serviceName, String abstractHash) {
        return jdbc.update(
                "DELETE FROM dii_slow_sql_optimization WHERE service_name = ? AND abstract_hash = ?",
                serviceName, abstractHash);
    }

    /** 单条查询：不存在返回 null。 */
    public Map<String, Object> findByKey(String serviceName, String abstractHash) {
        try {
            return jdbc.queryForMap(
                    "SELECT * FROM dii_slow_sql_optimization WHERE service_name = ? AND abstract_hash = ?",
                    serviceName, abstractHash);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 全量取记录（导入检测用；数量受人工标记限制，全表可控）。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList(
                "SELECT service_name, abstract_hash, status, optimized_round, reappeared_round " +
                "  FROM dii_slow_sql_optimization");
    }

    /**
     * 检测到又出现：置 REGRESSED，reappeared_round 取更晚者（不回退）。
     * 仅当 {@code reappearedRound > optimized_round}（字符串序）时匹配——防同轮/历史轮误报。
     * @return 受影响行数（1=命中并更新；0=未命中或轮次不满足）
     */
    public int flagReappeared(String serviceName, String abstractHash, String reappearedRound, LocalDateTime now) {
        return jdbc.update(
                "UPDATE dii_slow_sql_optimization " +
                "   SET status = ?, " +
                "       reappeared_round = CASE WHEN reappeared_round IS NULL OR ? > reappeared_round " +
                "                               THEN ? ELSE reappeared_round END, " +
                "       updated_at = ? " +
                " WHERE service_name = ? AND abstract_hash = ? AND ? > optimized_round",
                STATUS_REGRESSED, reappearedRound, reappearedRound, Timestamp.valueOf(now),
                serviceName, abstractHash, reappearedRound);
    }

    // ── 优化路线（dii_slow_sql_optimize_history，追加不删，审计每次优化）──

    /** 追加一条路线（新一次优化尝试：首次打标 / 未生效后重新打标）。 */
    public void appendHistory(String serviceName, String abstractHash, String optimizedRound,
                              String optimizedBy, String optimizedByName, String note, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO dii_slow_sql_optimize_history " +
                " (service_name, abstract_hash, optimized_round, optimized_by, optimized_by_name, " +
                "  optimize_note, optimized_at, reappeared_round) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                serviceName, abstractHash, optimizedRound, optimizedBy, optimizedByName,
                note, Timestamp.valueOf(now));
    }

    /** 最新一条路线的 id（派生表包一层，MySQL 允许 UPDATE 同表 + H2 行为一致；ORDER BY+LIMIT 的 UPDATE 两库有分歧，弃用）。 */
    private static final String LATEST_HISTORY_ID =
            "(SELECT mid FROM (SELECT MAX(id) AS mid FROM dii_slow_sql_optimize_history " +
            "   WHERE service_name = ? AND abstract_hash = ?) t)";

    /** 仍是 OPTIMIZED 时的内容编辑：更新最新一条路线（不新增，属于同一次优化）。 */
    public int updateLatestHistory(String serviceName, String abstractHash,
                                   String optimizedBy, String optimizedByName, String note, LocalDateTime now) {
        return jdbc.update(
                "UPDATE dii_slow_sql_optimize_history " +
                "   SET optimized_by = ?, optimized_by_name = ?, optimize_note = ?, optimized_at = ? " +
                " WHERE id = " + LATEST_HISTORY_ID,
                optimizedBy, optimizedByName, note, Timestamp.valueOf(now), serviceName, abstractHash);
    }

    /** 导入检测到该 SQL 又出现：把最新一条路线回填 reappeared_round（取更晚者），留"这次优化失败"的铁证。 */
    public int stampLatestHistoryReappeared(String serviceName, String abstractHash, String reappearedRound) {
        return jdbc.update(
                "UPDATE dii_slow_sql_optimize_history " +
                "   SET reappeared_round = CASE WHEN reappeared_round IS NULL OR ? > reappeared_round " +
                "                               THEN ? ELSE reappeared_round END " +
                " WHERE id = " + LATEST_HISTORY_ID,
                reappearedRound, reappearedRound, serviceName, abstractHash);
    }

    /** 撤销优化：把撤销人(工号/姓名)与时间记到最新一条路线上——审计留痕，路线不删。 */
    public int stampLatestHistoryRevoked(String serviceName, String abstractHash,
                                         String revokedBy, String revokedByName, LocalDateTime now) {
        return jdbc.update(
                "UPDATE dii_slow_sql_optimize_history " +
                "   SET revoked_by = ?, revoked_by_name = ?, revoked_at = ? " +
                " WHERE id = " + LATEST_HISTORY_ID,
                revokedBy, revokedByName, Timestamp.valueOf(now), serviceName, abstractHash);
    }

    /** 查优化路线（按时间升序=第1次→最新），时间列转字符串便于前端直显。 */
    public List<Map<String, Object>> listHistory(String serviceName, String abstractHash) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, optimized_round, optimized_by, optimized_by_name, optimize_note, " +
                "       optimized_at, reappeared_round, revoked_by, revoked_by_name, revoked_at " +
                "  FROM dii_slow_sql_optimize_history " +
                " WHERE service_name = ? AND abstract_hash = ? ORDER BY id ASC",
                serviceName, abstractHash);
        rows.forEach(r -> {
            r.computeIfPresent("optimized_at", (k, v) -> String.valueOf(v));
            r.computeIfPresent("revoked_at", (k, v) -> String.valueOf(v));
        });
        return rows;
    }
}
