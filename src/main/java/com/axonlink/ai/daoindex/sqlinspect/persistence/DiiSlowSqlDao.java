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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code dii_slow_sql} 读写 DAO（结果库 MySQL，V22 聚合行语义）。
 * <p>v2：行=（轮次, 微服务, 抽象SQL哈希）聚合（导入时已聚合），列表无需窗口函数；
 * 导出透视按 exec_count 求和。
 */
@Repository
public class DiiSlowSqlDao {

    private final JdbcTemplate jdbc;

    public DiiSlowSqlDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    private static final String INSERT_SQL =
            "INSERT INTO dii_slow_sql " +
            " (service_name, domain, biz_type, abstract_sql, abstract_hash, " +
            "  max_time_cost_ms, max_time_cost_raw, exec_params, source_location, exec_count, " +
            "  round, repeat_rounds, imported_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** 批量插入一批聚合行（同一轮次、同一导入时刻）。 */
    public int[] batchInsert(List<ParsedSlowSqlRow> rows, String round, LocalDateTime importedAt) {
        Timestamp ts = Timestamp.valueOf(importedAt);
        return jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                ParsedSlowSqlRow r = rows.get(i);
                ps.setString(1, r.serviceName);
                ps.setString(2, r.domain);
                ps.setString(3, r.bizType);
                ps.setString(4, r.abstractSql);
                ps.setString(5, r.abstractHash);
                ps.setLong(6, r.maxTimeCostMs);
                ps.setString(7, r.maxTimeCostRaw);
                ps.setString(8, r.execParams);
                ps.setString(9, r.sourceLocation);
                ps.setInt(10, r.execCount);
                ps.setString(11, round);
                ps.setString(12, r.repeatRounds);
                ps.setTimestamp(13, ts);
            }
            @Override public int getBatchSize() { return rows.size(); }
        });
    }

    /** 同轮覆盖：删除整轮旧数据，返回删除行数。 */
    public int deleteByRound(String round) {
        return jdbc.update("DELETE FROM dii_slow_sql WHERE round = ?", round);
    }

    /**
     * 「重复出现轮次」底料：排除指定轮次后，库中每个 (service_name, abstract_hash)
     * 出现过的轮次集合。键 = {@code service_name + "\n" + abstract_hash}（与导入聚合键一致），
     * 值 = 升序轮次集合（TreeSet 自然序，轮次形如 20260103-20260107 字符串序即时间序）。
     */
    public Map<String, TreeSet<String>> roundsByKeyExcluding(String excludeRound) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT service_name, abstract_hash, round FROM dii_slow_sql WHERE round <> ?",
                excludeRound);
        Map<String, TreeSet<String>> map = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String key = r.get("service_name") + "\n" + r.get("abstract_hash");
            map.computeIfAbsent(key, k -> new TreeSet<>()).add(String.valueOf(r.get("round")));
        }
        return map;
    }

    // ── 页面列表（聚合行直查，无窗口函数）──

    /** 过滤条件拼装（domain / bizType / keyword / whitelistStatus / round / approverUser）。 */
    private void appendFilters(StringBuilder sb, List<Object> args,
                               String domain, String bizType, String keyword,
                               String whitelistStatus, String round, String approverUser) {
        if (domain != null && !domain.isBlank()) { sb.append(" AND s.domain = ? "); args.add(domain.trim()); }
        if (bizType != null && !bizType.isBlank()) { sb.append(" AND s.biz_type = ? "); args.add(bizType.trim()); }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (s.abstract_sql LIKE ? OR s.service_name LIKE ? OR s.source_location LIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (whitelistStatus != null && !whitelistStatus.isBlank()) {
            if ("NONE".equalsIgnoreCase(whitelistStatus)) {
                sb.append(" AND s.whitelist_status IS NULL ");
            } else {
                sb.append(" AND s.whitelist_status = ? "); args.add(whitelistStatus.trim());
            }
        }
        if (round != null && !round.isBlank()) { sb.append(" AND s.round = ? "); args.add(round.trim()); }
        // 「该我审批」：行的白名单申请处于待审，且当前用户是对应级别审批人（铃铛慢SQL待办用）
        if (approverUser != null && !approverUser.isBlank()) {
            sb.append(" AND s.whitelist_status IN ('PENDING_L1','PENDING_L2') " +
                      " AND s.whitelist_app_id IN (SELECT id FROM dii_whitelist_application " +
                      "   WHERE (status='PENDING_L1' AND l1_approver=?) OR (status='PENDING_L2' AND l2_approver=?)) ");
            args.add(approverUser.trim()); args.add(approverUser.trim());
        }
    }

    /** 列表：聚合行直查（导入时已按 轮次+微服务+哈希 聚合），按最大耗时倒序分页。 */
    public List<Map<String, Object>> listAggregated(String domain, String bizType, String keyword,
                                                     String whitelistStatus, String round, String approverUser,
                                                     int limit, int offset) {
        StringBuilder sb = new StringBuilder(
                "SELECT s.id, s.service_name, s.domain, s.biz_type, s.abstract_sql, s.abstract_hash, " +
                "       s.max_time_cost_ms, s.max_time_cost_raw, s.exec_params, s.source_location, " +
                "       s.exec_count, s.round, s.repeat_rounds, " +
                "       s.whitelist_app_id, s.whitelist_status, s.is_whitelist " +
                "  FROM dii_slow_sql s WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendFilters(sb, args, domain, bizType, keyword, whitelistStatus, round, approverUser);
        sb.append(" ORDER BY s.max_time_cost_ms DESC, s.id ASC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));
        return jdbc.queryForList(sb.toString(), args.toArray());
    }

    /** 列表总数（同过滤条件）。 */
    public long countAggregated(String domain, String bizType, String keyword,
                                String whitelistStatus, String round, String approverUser) {
        StringBuilder sb = new StringBuilder(
                "SELECT COUNT(*) FROM dii_slow_sql s WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendFilters(sb, args, domain, bizType, keyword, whitelistStatus, round, approverUser);
        Long n = jdbc.queryForObject(sb.toString(), Long.class, args.toArray());
        return n == null ? 0L : n;
    }

    /** 去重 domain 下拉（中文领域，含 平台）。 */
    public List<String> listDistinctDomains() {
        return jdbc.queryForList(
                "SELECT DISTINCT domain FROM dii_slow_sql ORDER BY domain", String.class);
    }

    /** 去重 biz_type 下拉（中文类型）。 */
    public List<String> listDistinctBizTypes() {
        return jdbc.queryForList(
                "SELECT DISTINCT biz_type FROM dii_slow_sql ORDER BY biz_type", String.class);
    }

    /** 全部轮次（升序）→ 导出动态列表头 / 页面轮次下拉。 */
    public List<String> distinctRoundsSorted() {
        return jdbc.queryForList("SELECT DISTINCT round FROM dii_slow_sql ORDER BY round", String.class);
    }

    // ── 导出透视（全量，不过滤）──

    /** (abstract_hash, round, 次数=SUM(exec_count))。 */
    public List<Map<String, Object>> roundCounts() {
        return jdbc.queryForList(
                "SELECT abstract_hash, round, SUM(exec_count) AS cnt FROM dii_slow_sql GROUP BY abstract_hash, round");
    }

    /** (abstract_hash, 总次数=SUM(exec_count))。 */
    public List<Map<String, Object>> totalCounts() {
        return jdbc.queryForList(
                "SELECT abstract_hash, SUM(exec_count) AS cnt FROM dii_slow_sql GROUP BY abstract_hash");
    }

    /** 每抽象SQL代表行(跨轮最大耗时)：导出用。 */
    public List<Map<String, Object>> representativeRows() {
        return jdbc.queryForList(
                "SELECT t.abstract_hash, t.service_name, t.domain, t.biz_type, t.abstract_sql, " +
                "       t.exec_params, t.source_location, t.max_time_cost_ms FROM ( " +
                "  SELECT s.abstract_hash, s.service_name, s.domain, s.biz_type, s.abstract_sql, " +
                "         s.exec_params, s.source_location, s.max_time_cost_ms, " +
                "         ROW_NUMBER() OVER (PARTITION BY s.abstract_hash ORDER BY s.max_time_cost_ms DESC, s.id ASC) rn " +
                "    FROM dii_slow_sql s " +
                ") t WHERE t.rn = 1 ORDER BY t.max_time_cost_ms DESC");
    }

    // ── 白名单同步（被 WhitelistApplicationService 调）──

    /**
     * 把 (微服务, 抽象SQL) 的所有行（跨轮次）置成申请的当前状态；approved→is_whitelist 置 1；
     * 非通过不回收（与池/项一致）。
     * <p>v2：白名单粒度 = (service_name + abstract_hash)；serviceName 为空退化为仅按 hash
     * （兼容历史无微服务的申请）。
     */
    public int syncWhitelistByServiceAndHash(String serviceName, String abstractHash,
                                             long appId, String status, boolean approved) {
        if (abstractHash == null || abstractHash.isEmpty()) return 0;
        if (serviceName == null || serviceName.isBlank()) {
            return jdbc.update(
                    "UPDATE dii_slow_sql SET whitelist_app_id = ?, whitelist_status = ?, " +
                    "       is_whitelist = CASE WHEN ? THEN 1 ELSE is_whitelist END " +
                    " WHERE abstract_hash = ?",
                    appId, status, approved, abstractHash);
        }
        return jdbc.update(
                "UPDATE dii_slow_sql SET whitelist_app_id = ?, whitelist_status = ?, " +
                "       is_whitelist = CASE WHEN ? THEN 1 ELSE is_whitelist END " +
                " WHERE service_name = ? AND abstract_hash = ?",
                appId, status, approved, serviceName.trim(), abstractHash);
    }

    /** 取消申请：清冗余字段(不动 is_whitelist，与池一致)。 */
    public int clearWhitelistByAppId(long appId) {
        return jdbc.update(
                "UPDATE dii_slow_sql SET whitelist_app_id = NULL, whitelist_status = NULL " +
                " WHERE whitelist_app_id = ?", appId);
    }
}
