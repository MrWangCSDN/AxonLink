package com.axonlink.ai.daoindex.sqlinspect.explain;

import com.axonlink.ai.daoindex.sqlinspect.dto.ColumnInfo;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import com.axonlink.ai.daoindex.sqlinspect.metadata.TableMetadataService;
import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 {@code (env, schema.table, column)} 从数据库采样一个真实值，
 * 作为 EXPLAIN 替换 {@code ?} 占位符的素材。
 *
 * <h3>查询原则</h3>
 * <ul>
 *   <li>每字段只查一行：{@code SELECT col FROM schema.table WHERE col IS NOT NULL LIMIT 1}</li>
 *   <li>单次查询超时 {@code 5s}（防止大表扫飞）</li>
 *   <li>按 {@code (env, schema, table, column)} 缓存 24 小时，同批量内重复字段不重复查</li>
 *   <li>字段全为 NULL 也缓存（缓存"无真实值"的结论）</li>
 * </ul>
 *
 * <h3>错误处理</h3>
 * <ul>
 *   <li>表不存在 → 抛 {@link ColumnSampleException}</li>
 *   <li>字段不存在 → 抛 {@link ColumnSampleException}</li>
 *   <li>查询超时 / 权限失败 → 抛 {@link ColumnSampleException}</li>
 *   <li>字段值全 NULL（合法） → 返回 null，调用方可回退到类型默认字面量</li>
 * </ul>
 */
@Service
public class ColumnSampleService {

    private static final Logger log = LoggerFactory.getLogger(ColumnSampleService.class);
    /** 单次采样超时，防止大表扫描慢。 */
    private static final int SAMPLE_QUERY_TIMEOUT_SECONDS = 5;
    /** 成功结果的缓存 TTL：字段真实值变化不频繁，24h 足够。 */
    private static final Duration CACHE_TTL_SUCCESS = Duration.ofHours(24);
    /**
     * Schema 漂移错误缓存 TTL：5 分钟。
     * <p>表/字段修复后，最长 5 分钟就能自动恢复，避免长期走旧的"不存在"缓存。
     */
    private static final Duration CACHE_TTL_SCHEMA_ERROR = Duration.ofMinutes(5);
    /**
     * 查询技术错误缓存 TTL：1 分钟。
     * <p>超时/连接失败等通常是瞬时故障，短缓存避免重试风暴，也能快速恢复。
     */
    private static final Duration CACHE_TTL_QUERY_ERROR = Duration.ofMinutes(1);

    private final TargetDataSourceRegistry targetRegistry;
    private final TableMetadataService metadataService;
    /** key: env + table + column（都小写）。 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ColumnSampleService(TargetDataSourceRegistry targetRegistry,
                               TableMetadataService metadataService) {
        this.targetRegistry = targetRegistry;
        this.metadataService = metadataService;
    }

    /**
     * 拿一个字段的真实值字面量（可直接拼进 SQL）。
     *
     * @param env         目标库 env
     * @param tableName   表名（不带 schema）
     * @param columnName  字段名
     * @return 真实值对应的 SQL 字面量；字段全 NULL 时返回 null（调用方回退用类型默认值）
     * @throws SchemaDriftException  表不存在 / 字段不存在（schema 漂移，**必须暴露给调用方**）
     * @throws ColumnSampleException 查询失败 / 超时等技术异常（调用方可以 fallback）
     */
    public String sampleLiteral(String env, String tableName, String columnName) {
        String effEnv = (env == null || env.isBlank()) ? targetRegistry.getDefaultEnv() : env;
        String tbl = tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
        String col = columnName == null ? "" : columnName.trim().toLowerCase(Locale.ROOT);
        if (tbl.isEmpty() || col.isEmpty()) {
            throw new ColumnSampleException("表名或字段名为空");
        }

        String cacheKey = effEnv + "|" + tbl + "|" + col;
        CacheEntry hit = cache.get(cacheKey);
        if (hit != null && !hit.expired()) {
            // 命中缓存时按当时的错误类型抛对应异常
            if (hit.schemaError != null) throw new SchemaDriftException(hit.schemaError);
            if (hit.error != null) throw new ColumnSampleException(hit.error);
            return hit.literal;
        }

        try {
            String literal = doSample(effEnv, tbl, col);
            cache.put(cacheKey, CacheEntry.ok(literal));
            return literal;
        } catch (SchemaDriftException e) {
            // schema drift（表/字段不存在）：缓存错误类型，后续批量同字段一次性都报同一错
            cache.put(cacheKey, CacheEntry.schemaFail(e.getMessage()));
            throw e;
        } catch (ColumnSampleException e) {
            // 查询失败/超时等：缓存，避免反复查
            cache.put(cacheKey, CacheEntry.fail(e.getMessage()));
            throw e;
        }
    }

    /** 真正查库。 */
    private String doSample(String env, String tableName, String columnName) {
        // 1. 校验表存在 → schema drift 错误类型
        TableMetadata md = metadataService.get(env, tableName);
        if (md == null) {
            throw new SchemaDriftException(
                    "表不存在：env=" + env + " table=" + tableName);
        }
        ColumnInfo ci = null;
        if (md.getColumns() != null) {
            for (ColumnInfo c : md.getColumns()) {
                if (c.getName() != null && c.getName().equalsIgnoreCase(columnName)) {
                    ci = c;
                    break;
                }
            }
        }
        if (ci == null) {
            throw new SchemaDriftException(
                    "字段不存在：env=" + env + " table=" + tableName + " column=" + columnName);
        }

        String schema = md.getSchemaName();
        String sampleSql = "SELECT " + quoteId(columnName) + " FROM "
                + quoteId(schema) + "." + quoteId(tableName)
                + " WHERE " + quoteId(columnName) + " IS NOT NULL LIMIT 1";

        DataSource ds = targetRegistry.getByEnv(env);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.setQueryTimeout(SAMPLE_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sampleSql)) {
                if (rs.next()) {
                    Object v = rs.getObject(1);
                    if (v == null) return null;  // 理论不会，但防御
                    String literal = toSqlLiteral(v, ci.getDataType());
                    log.debug("[dii-sample] env={} {}.{} → {}", env, tableName, columnName,
                            literal.length() > 60 ? literal.substring(0, 60) + "..." : literal);
                    return literal;
                }
                // 表可能为空表，或字段所有值都是 NULL
                log.debug("[dii-sample] env={} {}.{} 无真实数据，调用方回退", env, tableName, columnName);
                return null;
            }
        } catch (Exception e) {
            throw new ColumnSampleException(
                    "采样失败：env=" + env + " " + tableName + "." + columnName + ": " + e.getMessage(), e);
        }
    }

    /** 把 JDBC 读出的 Java 值转成 GaussDB SQL 字面量。 */
    private static String toSqlLiteral(Object v, String pgType) {
        if (v == null) return "NULL";
        String t = pgType == null ? "" : pgType.toLowerCase(Locale.ROOT).trim();

        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof byte[]) {
            return "NULL";  // 二进制不做字面量，让调用方退到默认
        }
        // 时间 / 日期 / 时间戳：统一用字符串 + :: 强转，GaussDB 会按类型解析
        String s = v.toString();
        String escaped = s.replace("'", "''");
        if (t.startsWith("timestamp")) return "'" + escaped + "'::timestamp";
        if (t.startsWith("date"))      return "'" + escaped + "'::date";
        if (t.startsWith("time"))      return "'" + escaped + "'::time";
        if (t.startsWith("uuid"))      return "'" + escaped + "'::uuid";
        // 字符串类
        return "'" + escaped + "'";
    }

    /** 标识符引号：{@code schema} / {@code table} / {@code column} 都用 "" 包裹防注入。 */
    private static String quoteId(String id) {
        if (id == null) return "\"\"";
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    public void invalidateAll() { cache.clear(); }

    // ────────────────────────────── 内部类 ──────────────────────────────

    /** 技术异常（查询超时、连接失败等）；调用方可 fallback 到类型默认。 */
    public static class ColumnSampleException extends RuntimeException {
        public ColumnSampleException(String msg) { super(msg); }
        public ColumnSampleException(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * Schema 漂移：表或字段在目标库里不存在。<b>必须暴露</b>，不允许 fallback，
     * 由上层写入 {@code dii_analysis_item.explain_error} 让 DBA 可见可筛查。
     */
    public static class SchemaDriftException extends ColumnSampleException {
        public SchemaDriftException(String msg) { super(msg); }
    }

    /** 缓存条目：区分成功、schema 错、查询错三种情况。 */
    private static class CacheEntry {
        final String literal;       // 成功；null = 字段全 NULL
        final String error;         // 技术错误（查询失败）
        final String schemaError;   // schema 漂移错误（表/字段不存在）
        final Instant expireAt;

        private CacheEntry(String literal, String error, String schemaError, Instant expireAt) {
            this.literal = literal;
            this.error = error;
            this.schemaError = schemaError;
            this.expireAt = expireAt;
        }
        /** 成功（或字段全 NULL）用长 TTL，节省资源。 */
        static CacheEntry ok(String literal) {
            return new CacheEntry(literal, null, null, Instant.now().plus(CACHE_TTL_SUCCESS));
        }
        /** 查询技术错误用短 TTL（1 分钟），故障恢复后快速重试。 */
        static CacheEntry fail(String error) {
            return new CacheEntry(null, error, null, Instant.now().plus(CACHE_TTL_QUERY_ERROR));
        }
        /**
         * Schema 漂移错误用短 TTL（5 分钟）。
         * <p>DBA 修复表/字段后，最长等 5 分钟就能自动恢复；
         * 这期间新请求仍看到错误是"可接受的短暂延迟"，换来的是不长时间被旧缓存卡住。
         */
        static CacheEntry schemaFail(String schemaError) {
            return new CacheEntry(null, null, schemaError, Instant.now().plus(CACHE_TTL_SCHEMA_ERROR));
        }
        boolean expired() { return Instant.now().isAfter(expireAt); }
    }
}
