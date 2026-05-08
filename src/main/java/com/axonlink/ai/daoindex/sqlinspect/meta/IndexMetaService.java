package com.axonlink.ai.daoindex.sqlinspect.meta;

import com.axonlink.ai.daoindex.sqlinspect.dto.IndexMeta;
import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标库索引元数据查询服务。
 *
 * <p>基于 {@code pg_index + pg_attribute + pg_class + pg_namespace} 联合查询，
 * 支持 GaussDB / openGauss（它们都继承 PostgreSQL 的系统目录）。
 * 查询结果按 {@code (env, table)} 为 key 在内存里缓存 24 小时，避免每次 SQL 分析都打表。
 *
 * <p>列顺序严格按索引定义时的位置（{@code pg_index.indkey} 的数组顺序），
 * 这是最左匹配判定的关键输入。
 */
@Service
public class IndexMetaService {

    private static final Logger log = LoggerFactory.getLogger(IndexMetaService.class);

    /**
     * 查询表的全部索引及其列顺序。
     *
     * <p>返回的每一行是 {@code index} 的一个 column，多列索引会返回多行（按 ordinal 排序）。
     * 上层再按 index_name 分组组装。
     *
     * <p>{@code unnest(indkey) WITH ORDINALITY} 是 PG 9.4+ 特性，GaussDB 5 支持。
     */
    /**
     * 第 1 阶段：查索引元数据 + {@code indkey::text}（int2vector 序列化为空格分隔的位置字符串）。
     * 避开 {@code unnest WITH ORDINALITY}（GaussDB 不支持）。
     */
    private static final String SQL_INDEXES =
            "SELECT " +
            "  n.nspname      AS schema_name, " +
            "  i.relname      AS index_name, " +
            "  ix.indisunique AS is_unique, " +
            "  ix.indisprimary AS is_primary, " +
            "  am.amname      AS index_type, " +
            "  ix.indrelid    AS table_oid, " +
            "  ix.indkey::text AS indkey_text " +
            "FROM pg_index ix " +
            "JOIN pg_class t     ON t.oid = ix.indrelid " +
            "JOIN pg_class i     ON i.oid = ix.indexrelid " +
            "JOIN pg_am am       ON am.oid = i.relam " +
            "JOIN pg_namespace n ON n.oid = t.relnamespace " +
            "WHERE lower(t.relname) = lower(?) " +
            "  AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast','dbe_pldeveloper','snapshot','sqladvisor') " +
            "  AND n.nspname NOT LIKE 'pg_temp_%' " +
            "  AND n.nspname NOT LIKE 'pg_toast_temp_%' " +
            "ORDER BY n.nspname, ix.indisprimary DESC, ix.indisunique DESC, i.relname";

    /**
     * 第 2 阶段：根据 (table_oid, attnum) 拿到列名。
     * 逐个 attnum 查一次，保证顺序；table 索引列一般 1~4 列，循环次数少。
     */
    private static final String SQL_COLUMN_NAME =
            "SELECT attname FROM pg_attribute WHERE attrelid = ? AND attnum = ? AND NOT attisdropped";

    private final TargetDataSourceRegistry targetRegistry;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 非空结果缓存秒数：默认 5 分钟（300s）。
     * 设计目标：让一次批量分析（通常 1~10 分钟）内的同表查询能去重，
     * 同时让 DBA 加 / 删索引后最迟 5 分钟自动生效，不需要手动调 invalidate。
     */
    @org.springframework.beans.factory.annotation.Value(
            "${dao-index-analysis.index-meta.cache-seconds:300}")
    private long cacheSeconds;

    /**
     * 空结果缓存秒数：默认 30 秒。
     * 用来抵御短时网络抖 / 临时连接失败造成的假阳性"空"，
     * 但保持足够短让权限或连通性修复后系统能快速自愈。
     */
    @org.springframework.beans.factory.annotation.Value(
            "${dao-index-analysis.index-meta.empty-cache-seconds:30}")
    private long emptyCacheSeconds;

    public IndexMetaService(TargetDataSourceRegistry targetRegistry) {
        this.targetRegistry = targetRegistry;
    }

    /**
     * 获取指定 env 上某张表的全部索引元数据，带缓存。
     *
     * @param env       目标库 env（dev/sit/uat），留空走 default-env
     * @param tableName 表名（建议小写，实际查询用原串匹配 pg_class.relname）
     * @return 索引列表；若表不存在或无索引则返回空列表
     */
    public List<IndexMeta> getIndexes(String env, String tableName) {
        return getIndexes(env, tableName, false);
    }

    /**
     * 同 {@link #getIndexes(String, String)}，但 {@code forceRefresh=true} 时绕过缓存重新查目标库。
     *
     * <h3>缓存策略</h3>
     * <ul>
     *   <li><b>非空结果：</b>缓存 {@code cache-seconds}（默认 300s = 5 分钟）。
     *       一次批量分析内同表去重 + DDL 变更 5 分钟内自动生效。</li>
     *   <li><b>空结果：</b>缓存 {@code empty-cache-seconds}（默认 30s）。
     *       抵御短时连接抖动，但保持足够短让权限/连通性修复后能快速自愈。</li>
     * </ul>
     * <p>DBA 改完 DDL 想立即生效，可以调
     * {@code POST /api/ai/dao-index/debug/indexes/invalidate}（无需等缓存过期）。
     */
    public List<IndexMeta> getIndexes(String env, String tableName, boolean forceRefresh) {
        String effEnv = resolveEnv(env);
        String normTable = tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
        if (normTable.isEmpty()) {
            return Collections.emptyList();
        }
        CacheKey key = new CacheKey(effEnv, normTable);
        if (!forceRefresh) {
            CacheEntry cached = cache.get(key);
            if (cached != null && !cached.isExpired()) {
                return cached.indexes;
            }
        }
        List<IndexMeta> fresh = query(effEnv, normTable);
        boolean isEmpty = fresh == null || fresh.isEmpty();
        long ttlSec = isEmpty
                ? Math.max(0, emptyCacheSeconds)
                : Math.max(0, cacheSeconds);
        cache.put(key, new CacheEntry(fresh, Instant.now().plus(Duration.ofSeconds(ttlSec))));
        if (isEmpty) {
            log.warn("[dii-sqlinspect] 索引元数据为空 env={} table={}（缓存 {}s 后自动重试）",
                    effEnv, normTable, ttlSec);
        }
        return fresh;
    }

    /** 手动失效：比如目标库 DDL 变更后外部主动触发。 */
    public void invalidate(String env, String tableName) {
        if (tableName == null) return;
        cache.remove(new CacheKey(resolveEnv(env), tableName.trim().toLowerCase(Locale.ROOT)));
    }

    public void invalidateAll() {
        cache.clear();
    }

    private String resolveEnv(String env) {
        if (env == null || env.isBlank()) {
            return targetRegistry.getDefaultEnv();
        }
        return env.trim();
    }

    private List<IndexMeta> query(String env, String tableName) {
        DataSource ds;
        try {
            ds = targetRegistry.getByEnv(env);
        } catch (IllegalArgumentException e) {
            log.warn("[dii-sqlinspect] {}", e.getMessage());
            return Collections.emptyList();
        }
        // 第 1 阶段：拿全部索引的元数据 + indkey 位置串
        List<IndexRow> rows = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INDEXES)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexRow r = new IndexRow();
                    r.schemaName = rs.getString("schema_name");
                    r.indexName  = rs.getString("index_name");
                    r.unique     = rs.getBoolean("is_unique");
                    r.primary    = rs.getBoolean("is_primary");
                    r.indexType  = rs.getString("index_type");
                    r.tableOid   = rs.getLong("table_oid");
                    r.indkeyText = rs.getString("indkey_text");
                    rows.add(r);
                }
            }
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 第 1 阶段查索引元数据失败 env={} table={}，原因：{}",
                    env, tableName, e.getMessage(), e);
            return Collections.emptyList();
        }

        // 第 2 阶段：按 attnum 解析列位置，查 pg_attribute 拿列名
        List<IndexMeta> result = new ArrayList<>(rows.size());
        try (Connection conn = ds.getConnection();
             PreparedStatement psCol = conn.prepareStatement(SQL_COLUMN_NAME)) {
            for (IndexRow r : rows) {
                List<Integer> attnums = parseIndkey(r.indkeyText);
                List<String> columns = new ArrayList<>(attnums.size());
                for (Integer attnum : attnums) {
                    if (attnum == null || attnum <= 0) {
                        // attnum=0 表示表达式索引，无真实列名，跳过或占位
                        columns.add("<expr>");
                        continue;
                    }
                    psCol.setLong(1, r.tableOid);
                    psCol.setInt(2, attnum);
                    try (ResultSet rs = psCol.executeQuery()) {
                        if (rs.next()) {
                            columns.add(rs.getString(1));
                        } else {
                            columns.add("<unknown#" + attnum + ">");
                        }
                    }
                }
                result.add(new IndexMeta(r.schemaName, tableName, r.indexName, columns,
                        r.unique, r.primary, r.indexType));
            }
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 第 2 阶段查列名失败 env={} table={}，原因：{}",
                    env, tableName, e.getMessage(), e);
            return Collections.emptyList();
        }

        log.info("[dii-sqlinspect] env={} table={} 命中索引 {} 条（跨 schema 搜）",
                env, tableName, result.size());
        return result;
    }

    /**
     * int2vector 序列化成文本是空格分隔的整数串，例如 {@code "1 3 5"} 表示索引含 attnum=1、3、5 三列。
     * 位置顺序就是索引的最左匹配顺序。
     */
    private static List<Integer> parseIndkey(String indkeyText) {
        List<Integer> out = new ArrayList<>();
        if (indkeyText == null || indkeyText.isBlank()) return out;
        for (String token : indkeyText.trim().split("\\s+")) {
            try {
                out.add(Integer.parseInt(token));
            } catch (NumberFormatException ignore) { /* 跳过 */ }
        }
        return out;
    }

    /** 第 1 阶段结果行（仅内部使用）。 */
    private static class IndexRow {
        String schemaName;
        String indexName;
        boolean unique;
        boolean primary;
        String indexType;
        long tableOid;
        String indkeyText;
    }

    private record CacheKey(String env, String table) {}

    private static class CacheEntry {
        final List<IndexMeta> indexes;
        final Instant expireAt;

        CacheEntry(List<IndexMeta> indexes, Instant expireAt) {
            this.indexes = indexes;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expireAt);
        }
    }
}
