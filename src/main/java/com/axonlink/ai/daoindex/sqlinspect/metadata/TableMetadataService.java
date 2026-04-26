package com.axonlink.ai.daoindex.sqlinspect.metadata;

import com.axonlink.ai.daoindex.sqlinspect.dto.ColumnInfo;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收集目标库的表"完整画像"：DDL + 业务注释 + 行数 + 大小 + 列分布。
 *
 * <p>结果按 {@code (env, table)} 缓存 24 小时。所有失败都吞到 warnings，
 * 不影响主分析链路；元数据是"喂 LLM 的语料"，缺一块也能跑。
 */
@Service
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class TableMetadataService {

    private static final Logger log = LoggerFactory.getLogger(TableMetadataService.class);

    /** 表级信息（行数、大小、上次 ANALYZE 时间）。先按 schema 不限制，跨 schema 取第一个匹配。 */
    private static final String SQL_TABLE_INFO =
            "SELECT n.nspname AS schema_name, " +
            "       c.relname AS table_name, " +
            "       obj_description(c.oid) AS table_comment, " +
            "       COALESCE(s.n_live_tup, c.reltuples) AS live_tuples, " +
            "       pg_total_relation_size(c.oid) AS total_size_bytes, " +
            "       to_char(GREATEST(s.last_analyze, s.last_autoanalyze), 'YYYY-MM-DD HH24:MI:SS') AS last_analyze " +
            "FROM pg_class c " +
            "JOIN pg_namespace n ON n.oid = c.relnamespace " +
            "LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid " +
            "WHERE c.relkind IN ('r','p') " +
            "  AND lower(c.relname) = lower(?) " +
            "  AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast','dbe_pldeveloper','snapshot','sqladvisor') " +
            "  AND n.nspname NOT LIKE 'pg_temp_%' " +
            "  AND n.nspname NOT LIKE 'pg_toast_temp_%' " +
            "ORDER BY n.nspname LIMIT 1";

    /** 字段定义（含注释）。一次查所有列。 */
    private static final String SQL_COLUMNS =
            "SELECT a.attname AS column_name, " +
            "       format_type(a.atttypid, a.atttypmod) AS data_type, " +
            "       NOT a.attnotnull AS is_nullable, " +
            "       pg_get_expr(d.adbin, d.adrelid) AS default_value, " +
            "       a.attnum AS ordinal_position, " +
            "       col_description(c.oid, a.attnum) AS column_comment " +
            "FROM pg_attribute a " +
            "JOIN pg_class c ON c.oid = a.attrelid " +
            "JOIN pg_namespace n ON n.oid = c.relnamespace " +
            "LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum " +
            "WHERE c.oid = ?::oid AND a.attnum > 0 AND NOT a.attisdropped " +
            "ORDER BY a.attnum";

    /** 列分布统计（来自 pg_stats）。最常见值占比用来推 skew level。 */
    private static final String SQL_COLUMN_STATS =
            "SELECT attname AS column_name, " +
            "       n_distinct AS distinct_count, " +
            "       null_frac AS null_fraction, " +
            "       most_common_freqs[1] AS top_freq " +
            "FROM pg_stats " +
            "WHERE schemaname = ? AND tablename = ?";

    /** 顺手取 c.oid，免得多查一次。 */
    private static final String SQL_TABLE_OID =
            "SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
            "WHERE c.relkind IN ('r','p') AND lower(c.relname) = lower(?) AND n.nspname = ? LIMIT 1";

    private final TargetDataSourceRegistry targetRegistry;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public TableMetadataService(TargetDataSourceRegistry targetRegistry) {
        this.targetRegistry = targetRegistry;
    }

    /**
     * 表完整元数据；表不存在或权限不足返回 null。
     *
     * <p><b>缓存策略</b>：
     * <ul>
     *   <li>查到表（value 非 null） → 缓存 24h（表结构不频繁变）</li>
     *   <li>表不存在（value == null） → 缓存 5 分钟（DBA 修复建表后最多 5 分钟自动恢复）</li>
     * </ul>
     * 这样避免"表昨天没建 → 缓存 null 24h → 今天建好了 → 还是显示不存在"的问题。
     */
    public TableMetadata get(String env, String tableName) {
        String effEnv = (env == null || env.isBlank()) ? targetRegistry.getDefaultEnv() : env.trim();
        String norm = tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return null;

        CacheKey key = new CacheKey(effEnv, norm);
        CacheEntry e = cache.get(key);
        if (e != null && !e.expired()) return e.value;

        TableMetadata fresh = doFetch(effEnv, norm);
        // 查到用长 TTL，查不到用短 TTL（DDL 修复后快速自愈）
        Duration ttl = fresh != null ? Duration.ofHours(24) : Duration.ofMinutes(5);
        cache.put(key, new CacheEntry(fresh, Instant.now().plus(ttl)));
        return fresh;
    }

    /** 批量取多张表元数据，返回 {@code tableName -> TableMetadata}。 */
    public Map<String, TableMetadata> getAll(String env, List<String> tableNames) {
        Map<String, TableMetadata> out = new LinkedHashMap<>();
        if (tableNames == null) return out;
        for (String t : tableNames) {
            TableMetadata m = get(env, t);
            if (m != null) out.put(t.toLowerCase(Locale.ROOT), m);
        }
        return out;
    }

    public void invalidate(String env, String tableName) {
        if (tableName == null) return;
        cache.remove(new CacheKey(
                env == null || env.isBlank() ? targetRegistry.getDefaultEnv() : env,
                tableName.trim().toLowerCase(Locale.ROOT)));
    }

    public void invalidateAll() { cache.clear(); }

    private TableMetadata doFetch(String env, String tableName) {
        DataSource ds;
        try {
            ds = targetRegistry.getByEnv(env);
        } catch (Exception e) {
            log.warn("[dii-meta] env 不可用：{}", e.getMessage());
            return null;
        }

        TableMetadata md = new TableMetadata();
        md.setTableName(tableName);

        try (Connection conn = ds.getConnection()) {
            // 1. 表头：schema / 注释 / 行数 / 大小 / last_analyze
            try (PreparedStatement ps = conn.prepareStatement(SQL_TABLE_INFO)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        log.debug("[dii-meta] 表不存在：env={} table={}", env, tableName);
                        return null;
                    }
                    md.setSchemaName(rs.getString("schema_name"));
                    md.setTableComment(rs.getString("table_comment"));
                    md.setLiveTuples(getLong(rs, "live_tuples"));
                    md.setTotalSizeBytes(getLong(rs, "total_size_bytes"));
                    md.setLastAnalyzeTime(rs.getString("last_analyze"));
                    md.setSizeBucket(bucketOf(md.getLiveTuples()));
                }
            }

            // 2. 拿 oid 用于查列
            Long oid = null;
            try (PreparedStatement ps = conn.prepareStatement(SQL_TABLE_OID)) {
                ps.setString(1, tableName);
                ps.setString(2, md.getSchemaName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) oid = rs.getLong(1);
                }
            }
            if (oid == null) {
                md.getWarnings().add("无法获取表 OID，跳过列信息收集");
                return md;
            }

            // 3. 列定义
            Map<String, ColumnInfo> colMap = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(SQL_COLUMNS)) {
                ps.setLong(1, oid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ColumnInfo ci = new ColumnInfo();
                        String name = rs.getString("column_name");
                        ci.setName(name);
                        ci.setDataType(rs.getString("data_type"));
                        ci.setNullable(rs.getBoolean("is_nullable"));
                        ci.setDefaultValue(rs.getString("default_value"));
                        ci.setOrdinalPosition(rs.getInt("ordinal_position"));
                        ci.setComment(rs.getString("column_comment"));
                        colMap.put(name, ci);
                    }
                }
            }

            // 4. 列统计（pg_stats）
            try (PreparedStatement ps = conn.prepareStatement(SQL_COLUMN_STATS)) {
                ps.setString(1, md.getSchemaName());
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String col = rs.getString("column_name");
                        ColumnInfo ci = colMap.get(col);
                        if (ci == null) continue;
                        ci.setDistinctCount(getDouble(rs, "distinct_count"));
                        ci.setNullFraction(getDouble(rs, "null_fraction"));
                        Double topFreq = getDouble(rs, "top_freq");
                        ci.setSkewLevel(skewLevelOf(topFreq));
                    }
                }
            } catch (Exception statsEx) {
                md.getWarnings().add("pg_stats 不可用：" + statsEx.getMessage());
            }

            md.setColumns(new java.util.ArrayList<>(colMap.values()));
        } catch (Exception e) {
            log.warn("[dii-meta] 查表元数据失败 env={} table={}: {}", env, tableName, e.getMessage());
            md.getWarnings().add("收集失败：" + e.getMessage());
        }
        return md;
    }

    private static String bucketOf(Long tuples) {
        if (tuples == null) return "UNKNOWN";
        if (tuples < 10_000) return "SMALL";
        if (tuples < 1_000_000) return "MEDIUM";
        return "LARGE";
    }

    /** 把 most_common_freqs[1] 转成 skew 等级。 */
    private static String skewLevelOf(Double topFreq) {
        if (topFreq == null) return "LOW";
        if (topFreq > 0.5) return "HIGH";
        if (topFreq > 0.2) return "MEDIUM";
        return "LOW";
    }

    private static Long getLong(ResultSet rs, String col) {
        try {
            long v = rs.getLong(col);
            return rs.wasNull() ? null : v;
        } catch (Exception e) { return null; }
    }

    private static Double getDouble(ResultSet rs, String col) {
        try {
            double v = rs.getDouble(col);
            return rs.wasNull() ? null : v;
        } catch (Exception e) { return null; }
    }

    private record CacheKey(String env, String table) {}

    private static class CacheEntry {
        final TableMetadata value;
        final Instant expireAt;
        CacheEntry(TableMetadata value, Instant expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
        boolean expired() { return Instant.now().isAfter(expireAt); }
    }
}
