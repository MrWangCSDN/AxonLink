package com.axonlink.ai.daoindex.sqlinspect.er.persistence;

import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code dii_er_relation} 表读写 DAO。
 *
 * <p>核心方法：
 * <ul>
 *   <li>{@link #upsertKeepHumanDecision}：upsert，但已 CONFIRMED/IGNORED 的只更 confidence（保留人工决策）</li>
 *   <li>{@link #deleteStaleAuto}：删本轮没再推出的 AUTO 关系（schema 变了关系消失）；人工标过的不删</li>
 *   <li>{@link #subgraph}：以中心表为起点的 N 跳邻居子图</li>
 * </ul>
 */
@Repository
public class ErRelationDao {

    private static final Logger log = LoggerFactory.getLogger(ErRelationDao.class);

    private final JdbcTemplate jdbc;

    public ErRelationDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /** confidence → 数值序（用于 minConfidence 过滤：HIGH≥MEDIUM≥LOW）。 */
    private static int confRank(String c) {
        if ("HIGH".equals(c)) return 3;
        if ("MEDIUM".equals(c)) return 2;
        return 1; // LOW / null
    }

    /** SQL 片段：confidence 转 rank（MySQL CASE）。 */
    private static final String CONF_RANK_CASE =
            "CASE confidence WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END";

    /**
     * upsert 一条推断关系，保留人工决策：
     * <ul>
     *   <li>UNIQUE (env,from,to,join_columns) 命中且 status ∈ {CONFIRMED, IGNORED}：只更 confidence/key_*，status 不动</li>
     *   <li>否则：插入（status=AUTO）或更新为 AUTO + 新 confidence</li>
     * </ul>
     */
    public void upsertKeepHumanDecision(String env, ErRelation r) {
        String joinCols = r.joinColumnsCsv();
        // 先查现有 status
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM dii_er_relation " +
                " WHERE env=? AND from_table=? AND to_table=? AND join_columns=?",
                String.class, env, r.getFromTable(), r.getToTable(), joinCols);

        if (!statuses.isEmpty()) {
            String st = statuses.get(0);
            if ("CONFIRMED".equals(st) || "IGNORED".equals(st)) {
                // 人工决策保留：只刷 confidence / key 元数据，不回退 status
                jdbc.update(
                        "UPDATE dii_er_relation SET confidence=?, key_type=?, key_col_count=? " +
                        " WHERE env=? AND from_table=? AND to_table=? AND join_columns=?",
                        r.getConfidence(), r.getKeyType(), r.getKeyColCount(),
                        env, r.getFromTable(), r.getToTable(), joinCols);
            } else {
                // AUTO：刷成最新 AUTO 结果
                jdbc.update(
                        "UPDATE dii_er_relation SET confidence=?, key_type=?, key_col_count=?, status='AUTO' " +
                        " WHERE env=? AND from_table=? AND to_table=? AND join_columns=?",
                        r.getConfidence(), r.getKeyType(), r.getKeyColCount(),
                        env, r.getFromTable(), r.getToTable(), joinCols);
            }
        } else {
            jdbc.update(
                    "INSERT INTO dii_er_relation " +
                    " (env, from_table, to_table, join_columns, key_type, key_col_count, confidence, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'AUTO')",
                    env, r.getFromTable(), r.getToTable(), joinCols,
                    r.getKeyType(), r.getKeyColCount(), r.getConfidence());
        }
    }

    /**
     * 删除本轮没再推出的 AUTO 关系（CONFIRMED/IGNORED 保留）。
     * @param liveKeys 本轮推出的关系 key 集合，key = from|to|joinCols
     */
    public int deleteStaleAuto(String env, Set<String> liveKeys) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, from_table, to_table, join_columns FROM dii_er_relation " +
                " WHERE env=? AND status='AUTO'", env);
        int deleted = 0;
        for (Map<String, Object> row : existing) {
            String key = row.get("from_table") + "|" + row.get("to_table") + "|" + row.get("join_columns");
            if (!liveKeys.contains(key)) {
                jdbc.update("DELETE FROM dii_er_relation WHERE id=?", row.get("id"));
                deleted++;
            }
        }
        return deleted;
    }

    /** 按置信度统计（rebuild 返回用）。返回 {HIGH,MEDIUM,LOW,total}。 */
    public Map<String, Object> countByConfidence(String env) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT " +
                " SUM(confidence='HIGH')   AS high, " +
                " SUM(confidence='MEDIUM') AS medium, " +
                " SUM(confidence='LOW')    AS low, " +
                " COUNT(*) AS total " +
                " FROM dii_er_relation WHERE env=?", env);
        return row;
    }

    /**
     * 列出有关系的表（from ∪ to 去重），支持关键字模糊。给画布选中心表用。
     */
    public List<String> listTables(String env, String keyword) {
        // UNION ALL 让每条关系两端各计一次 → GROUP BY 后 COUNT(*) = 该表的关系度（连了多少条边）
        // 按度降序，让"最有料"的表排最前（前端自动选中心表时挑第一个）。IGNORED 不计。
        // v3：页面只展示 HIGH（联合主键全覆盖），列表只数 HIGH 关系，
        // 保证前端自动选的中心表在 HIGH 下一定有图。
        StringBuilder sb = new StringBuilder(
                "SELECT t FROM ( " +
                "  SELECT from_table AS t FROM dii_er_relation WHERE env=? AND status<>'IGNORED' AND confidence='HIGH' " +
                "  UNION ALL SELECT to_table AS t FROM dii_er_relation WHERE env=? AND status<>'IGNORED' AND confidence='HIGH' " +
                ") u WHERE 1=1");
        List<Object> args = new ArrayList<>();
        args.add(env); args.add(env);
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND t LIKE ?");
            args.add("%" + keyword.trim().toLowerCase() + "%");
        }
        sb.append(" GROUP BY t ORDER BY COUNT(*) DESC, t LIMIT 500");
        return jdbc.queryForList(sb.toString(), String.class, args.toArray());
    }

    /**
     * 以中心表为起点的 N 跳邻居子图：返回涉及的全部关系行。
     * 1 跳 = 与中心表直接相连（from 或 to 之一是中心表）；
     * 2 跳 = 再把 1 跳邻居作为新中心扩一层。
     *
     * @param minConfidence HIGH/MEDIUM/LOW —— 只要 ≥ 此置信度的边；IGNORED 状态的边永远排除
     */
    public List<Map<String, Object>> subgraph(String env, String centerTable, int hops, String minConfidence) {
        int minRank = confRank(minConfidence);
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(centerTable.toLowerCase());
        Set<String> visited = new LinkedHashSet<>(frontier);
        // 关系去重：用 id
        Map<Object, Map<String, Object>> edges = new java.util.LinkedHashMap<>();

        int effHops = Math.max(1, Math.min(hops, 3));
        for (int h = 0; h < effHops && !frontier.isEmpty(); h++) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String tbl : frontier) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT id, from_table, to_table, join_columns, key_type, key_col_count, " +
                        "       confidence, status FROM dii_er_relation " +
                        " WHERE env=? AND status <> 'IGNORED' " +
                        "   AND (from_table=? OR to_table=?) " +
                        "   AND " + CONF_RANK_CASE + " >= ?",
                        env, tbl, tbl, minRank);
                for (Map<String, Object> r : rows) {
                    edges.putIfAbsent(r.get("id"), r);
                    String ft = String.valueOf(r.get("from_table"));
                    String tt = String.valueOf(r.get("to_table"));
                    if (visited.add(ft)) nextFrontier.add(ft);
                    if (visited.add(tt)) nextFrontier.add(tt);
                }
            }
            frontier = nextFrontier;
        }
        return new ArrayList<>(edges.values());
    }

    /** 全量（仅给「概览」，通常限 HIGH），带 minConfidence 过滤。 */
    public List<Map<String, Object>> listAll(String env, String minConfidence, int limit) {
        int minRank = confRank(minConfidence);
        int eff = Math.min(Math.max(limit, 1), 5000);
        return jdbc.queryForList(
                "SELECT id, from_table, to_table, join_columns, key_type, key_col_count, confidence, status " +
                " FROM dii_er_relation WHERE env=? AND status <> 'IGNORED' AND " + CONF_RANK_CASE + " >= ? " +
                " ORDER BY " + CONF_RANK_CASE + " DESC, from_table LIMIT ?",
                env, minRank, eff);
    }

    /** 人工修正 status。 */
    public int setStatus(long id, String status) {
        return jdbc.update("UPDATE dii_er_relation SET status=? WHERE id=?", status, id);
    }

    public Map<String, Object> findById(long id) {
        try {
            return jdbc.queryForMap("SELECT * FROM dii_er_relation WHERE id=?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 导出查询：按 minConfidence 过滤的全部关系（含 IGNORED，导出要全留痕）。 */
    public List<Map<String, Object>> listForExport(String env, String minConfidence) {
        int minRank = confRank(minConfidence);
        return jdbc.queryForList(
                "SELECT from_table, to_table, join_columns, key_type, key_col_count, confidence, status, " +
                "       created_at, updated_at FROM dii_er_relation " +
                " WHERE env=? AND " + CONF_RANK_CASE + " >= ? " +
                " ORDER BY " + CONF_RANK_CASE + " DESC, from_table, to_table",
                env, minRank);
    }
}
