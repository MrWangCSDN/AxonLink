package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 从 {@code dii_analysis_item} 聚合统计"某张表被哪些 SQL 用了"，
 * 按谓词字段组合归档，提供给 LLM 看"全局视角"。
 *
 * <h3>用途</h3>
 * LLM 分析单条 SQL 时，能知道同一张表上还有其他哪些访问模式。
 * 这样给出的"建议加索引"建议能考虑"一次索引 DDL 能同时优化多条 SQL"的全局收益。
 */
@Service
public class SameTableAccessPatternCollector {

    private static final Logger log = LoggerFactory.getLogger(SameTableAccessPatternCollector.class);

    /** Top N predicate buckets，避免给 LLM 的 context 太长。 */
    private static final int TOP_BUCKETS = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SameTableAccessPatternCollector(JdbcTemplate diiResultJdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = diiResultJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 收集指定 env+table 的访问模式。
     *
     * @param env       环境
     * @param tableName 目标表名（小写）
     * @param excludeItemId 排除的 itemId（通常就是当前正在分析的那条，避免自己统计自己）
     */
    public SameTableAccessPattern collect(String env, String tableName, Long excludeItemId) {
        SameTableAccessPattern out = new SameTableAccessPattern(tableName);
        if (env == null || tableName == null) return out;

        // 查同表所有 item：过滤 env + involved_tables LIKE + 状态 DONE + 排除自己
        String sql = "SELECT id, overall_rating, table_ratings_json " +
                "FROM dii_analysis_item " +
                "WHERE env = ? AND status = 'DONE' " +
                "  AND involved_tables LIKE ? " +
                (excludeItemId != null ? " AND id <> ? " : "") +
                " ORDER BY id DESC LIMIT 1000";   // 安全上限 1000，避免大表汇总炸内存

        List<Map<String, Object>> rows;
        try {
            Object[] args = excludeItemId != null
                    ? new Object[]{env, "%" + tableName + "%", excludeItemId}
                    : new Object[]{env, "%" + tableName + "%"};
            rows = jdbc.queryForList(sql, args);
        } catch (Exception e) {
            log.warn("[dii-llm-collect] 查询同表 SQL 失败 env={} table={}: {}", env, tableName, e.getMessage());
            return out;
        }

        out.setTotalSqlCount(rows.size());
        // 谓词组合 → 评级 → 条数
        Map<String, Map<String, Integer>> bucket = new LinkedHashMap<>();
        Map<String, Integer> overallRatingCounts = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            String rating = (String) row.get("overall_rating");
            if (rating != null) {
                overallRatingCounts.merge(rating, 1, Integer::sum);
            }
            // 解析 table_ratings_json，找到当前表的 predicates
            String ratingsJson = (String) row.get("table_ratings_json");
            List<String> predicateFields = extractPredicateFieldsForTable(ratingsJson, tableName);
            if (predicateFields == null || predicateFields.isEmpty()) continue;

            String key = String.join(",", predicateFields);
            Map<String, Integer> dist = bucket.computeIfAbsent(key, k -> new LinkedHashMap<>());
            dist.merge(rating == null ? "UNKNOWN" : rating, 1, Integer::sum);
        }
        out.setRatingCounts(overallRatingCounts);

        // 按总数排序取 TOP N
        List<Map.Entry<String, Map<String, Integer>>> sorted = new ArrayList<>(bucket.entrySet());
        sorted.sort((a, b) -> sum(b.getValue()) - sum(a.getValue()));
        List<SameTableAccessPattern.PredicateBucket> topBuckets = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_BUCKETS, sorted.size()); i++) {
            Map.Entry<String, Map<String, Integer>> e = sorted.get(i);
            List<String> fields = e.getKey().isEmpty() ? Collections.emptyList()
                    : Arrays.asList(e.getKey().split(","));
            topBuckets.add(new SameTableAccessPattern.PredicateBucket(fields, sum(e.getValue()), e.getValue()));
        }
        out.setByPredicate(topBuckets);
        if (log.isDebugEnabled() && !topBuckets.isEmpty()) {
            log.debug("[dii-llm-collect] env={} table={} 同表 SQL 总数={} 评级分布={} TOP bucket={}",
                    env, tableName, out.getTotalSqlCount(), overallRatingCounts,
                    topBuckets.get(0).fields);
        }
        return out;
    }

    /** 从 table_ratings_json 里找到当前表的 equalityColumns 字段列表。 */
    private List<String> extractPredicateFieldsForTable(String ratingsJson, String targetTable) {
        if (ratingsJson == null || ratingsJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(ratingsJson);
            if (!root.isArray()) return null;
            for (JsonNode tr : root) {
                String t = tr.path("table").asText("");
                if (!t.equalsIgnoreCase(targetTable)) continue;
                JsonNode preds = tr.path("predicates").path("equalityColumns");
                if (!preds.isArray()) return Collections.emptyList();
                List<String> fields = new ArrayList<>();
                for (JsonNode f : preds) fields.add(f.asText());
                Collections.sort(fields);   // 规范化排序，让 a,b 和 b,a 归为同一 bucket
                return fields;
            }
        } catch (Exception e) {
            log.debug("[dii-llm-collect] 解析 table_ratings_json 失败：{}", e.getMessage());
        }
        return null;
    }

    private int sum(Map<String, Integer> m) {
        int total = 0;
        for (Integer v : m.values()) total += v == null ? 0 : v;
        return total;
    }
}
