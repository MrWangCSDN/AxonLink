package com.axonlink.ai.daoindex.sqlinspect.persistence;

import com.axonlink.ai.daoindex.sqlinspect.slowsql.dto.ParsedSlowSqlRow;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code dii_slow_sql} 读写 DAO（结果库 MySQL）。
 * <p>聚合/透视用窗口函数(MySQL 8)。
 */
@Repository
public class DiiSlowSqlDao {

    private final JdbcTemplate jdbc;

    public DiiSlowSqlDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    private static final String INSERT_SQL =
            "INSERT INTO dii_slow_sql " +
            " (domain, time_cost_ms, time_cost_raw, abstract_sql, abstract_hash, exec_params, round, imported_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    /** 批量插入一批解析行（同一轮次、同一导入时刻）。 */
    public int[] batchInsert(List<ParsedSlowSqlRow> rows, String round, LocalDateTime importedAt) {
        Timestamp ts = Timestamp.valueOf(importedAt);
        return jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                ParsedSlowSqlRow r = rows.get(i);
                ps.setString(1, r.domain);
                ps.setLong(2, r.timeCostMs);
                ps.setString(3, r.timeCostRaw);
                ps.setString(4, r.abstractSql);
                ps.setString(5, r.abstractHash);
                ps.setString(6, r.execParams);
                ps.setString(7, round);
                ps.setTimestamp(8, ts);
            }
            @Override public int getBatchSize() { return rows.size(); }
        });
    }

    /** 库中已有的全部轮次号（轮次生成时按日期前缀过滤）。 */
    public List<String> listAllRounds() {
        return jdbc.queryForList("SELECT DISTINCT round FROM dii_slow_sql", String.class);
    }

    // ── 页面聚合：每个抽象SQL取全局最大耗时那条 ──

    /** 过滤条件拼装（domain / keyword / whitelistStatus / round）。 */
    private void appendFilters(StringBuilder sb, List<Object> args,
                               String domain, String keyword, String whitelistStatus, String round) {
        if (domain != null && !domain.isBlank()) { sb.append(" AND s.domain = ? "); args.add(domain.trim()); }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (s.abstract_sql LIKE ? OR s.domain LIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like);
        }
        if (whitelistStatus != null && !whitelistStatus.isBlank()) {
            if ("NONE".equalsIgnoreCase(whitelistStatus)) {
                sb.append(" AND s.whitelist_status IS NULL ");
            } else {
                sb.append(" AND s.whitelist_status = ? "); args.add(whitelistStatus.trim());
            }
        }
        if (round != null && !round.isBlank()) { sb.append(" AND s.round = ? "); args.add(round.trim()); }
    }

    /** 聚合列表：每抽象SQL一行(代表行=全局最大耗时)，分页。 */
    public List<Map<String, Object>> listAggregated(String domain, String keyword,
                                                     String whitelistStatus, String round,
                                                     int limit, int offset) {
        StringBuilder inner = new StringBuilder(
                "SELECT s.id, s.domain, s.time_cost_ms, s.time_cost_raw, s.abstract_sql, s.abstract_hash, " +
                "       s.exec_params, s.round, s.whitelist_app_id, s.whitelist_status, s.is_whitelist, " +
                "       ROW_NUMBER() OVER (PARTITION BY s.abstract_hash ORDER BY s.time_cost_ms DESC, s.id ASC) rn " +
                "  FROM dii_slow_sql s WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendFilters(inner, args, domain, keyword, whitelistStatus, round);
        String sql = "SELECT t.* FROM ( " + inner + " ) t WHERE t.rn = 1 " +
                     " ORDER BY t.time_cost_ms DESC LIMIT ? OFFSET ?";
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));
        return jdbc.queryForList(sql, args.toArray());
    }

    /** 聚合总数 = distinct abstract_hash。 */
    public long countAggregated(String domain, String keyword, String whitelistStatus, String round) {
        StringBuilder sb = new StringBuilder(
                "SELECT COUNT(DISTINCT s.abstract_hash) FROM dii_slow_sql s WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendFilters(sb, args, domain, keyword, whitelistStatus, round);
        Long n = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return n == null ? 0L : n;
    }

    /** 去重 domain 下拉。 */
    public List<String> listDistinctDomains() {
        return jdbc.queryForList(
                "SELECT DISTINCT domain FROM dii_slow_sql ORDER BY domain", String.class);
    }

    // ── 导出透视（全量，不过滤）──

    /** 全部轮次（升序）→ 动态列表头。 */
    public List<String> distinctRoundsSorted() {
        return jdbc.queryForList("SELECT DISTINCT round FROM dii_slow_sql ORDER BY round", String.class);
    }

    /** (abstract_hash, round, 次数)。 */
    public List<Map<String, Object>> roundCounts() {
        return jdbc.queryForList(
                "SELECT abstract_hash, round, COUNT(*) AS cnt FROM dii_slow_sql GROUP BY abstract_hash, round");
    }

    /** (abstract_hash, 总次数)。 */
    public List<Map<String, Object>> totalCounts() {
        return jdbc.queryForList(
                "SELECT abstract_hash, COUNT(*) AS cnt FROM dii_slow_sql GROUP BY abstract_hash");
    }

    /** 每抽象SQL代表行(全局最大耗时)：domain/abstract_sql/exec_params/max_cost。 */
    public List<Map<String, Object>> representativeRows() {
        return jdbc.queryForList(
                "SELECT t.abstract_hash, t.domain, t.abstract_sql, t.exec_params, t.time_cost_ms FROM ( " +
                "  SELECT s.abstract_hash, s.domain, s.abstract_sql, s.exec_params, s.time_cost_ms, " +
                "         ROW_NUMBER() OVER (PARTITION BY s.abstract_hash ORDER BY s.time_cost_ms DESC, s.id ASC) rn " +
                "    FROM dii_slow_sql s " +
                ") t WHERE t.rn = 1 ORDER BY t.time_cost_ms DESC");
    }

    // ── 白名单同步（被 WhitelistApplicationService 调）──

    /** 把某抽象SQL的所有行置成申请的当前状态；approved→is_whitelist 置 1；非通过不回收（与池/项一致）。 */
    public int syncWhitelistByHash(String abstractHash, long appId, String status, boolean approved) {
        if (abstractHash == null || abstractHash.isEmpty()) return 0;
        return jdbc.update(
                "UPDATE dii_slow_sql SET whitelist_app_id = ?, whitelist_status = ?, " +
                "       is_whitelist = CASE WHEN ? THEN 1 ELSE is_whitelist END " +
                " WHERE abstract_hash = ?",
                appId, status, approved, abstractHash);
    }

    /** 取消申请：清冗余字段(不动 is_whitelist，与池一致)。 */
    public int clearWhitelistByAppId(long appId) {
        return jdbc.update(
                "UPDATE dii_slow_sql SET whitelist_app_id = NULL, whitelist_status = NULL " +
                " WHERE whitelist_app_id = ?", appId);
    }
}
