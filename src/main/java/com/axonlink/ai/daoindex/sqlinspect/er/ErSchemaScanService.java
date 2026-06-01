package com.axonlink.ai.daoindex.sqlinspect.er;

import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErKeySet;
import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 全库批量扫描目标库的索引 + 列元数据（ER 推断的数据来源）。
 *
 * <p>区别于 {@code IndexMetaService}（逐表查、带缓存、给规则引擎用），本类一次性
 * 批量拉<b>全库</b>，只跑 2 条 SQL：
 * <ol>
 *   <li>SQL-1：全库所有 PK/UK 索引（含联合，indkey 位置串）</li>
 *   <li>SQL-2：全库所有列（table_oid → attnum → attname）</li>
 * </ol>
 * 用 SQL-2 的 (oid,attnum,attname) 把 SQL-1 的 indkey 翻译成键列名。表名/列名归一小写。
 */
@Service
public class ErSchemaScanService {

    private static final Logger log = LoggerFactory.getLogger(ErSchemaScanService.class);

    /** 系统 schema 过滤（与 IndexMetaService 一致）。 */
    private static final String SCHEMA_FILTER =
            " n.nspname NOT IN ('pg_catalog','information_schema','pg_toast','dbe_pldeveloper','snapshot','sqladvisor') " +
            " AND n.nspname NOT LIKE 'pg_temp_%' AND n.nspname NOT LIKE 'pg_toast_temp_%' ";

    /** 全库 PK/UK 索引。 */
    private static final String SQL_INDEXES =
            "SELECT t.relname AS table_name, ix.indisprimary AS is_primary, " +
            "       ix.indisunique AS is_unique, ix.indrelid AS table_oid, " +
            "       ix.indkey::text AS indkey_text " +
            "  FROM pg_index ix " +
            "  JOIN pg_class t     ON t.oid = ix.indrelid " +
            "  JOIN pg_namespace n ON n.oid = t.relnamespace " +
            " WHERE (ix.indisprimary OR ix.indisunique) AND t.relkind = 'r' AND " + SCHEMA_FILTER;

    /** 全库列。 */
    private static final String SQL_COLUMNS =
            "SELECT a.attrelid AS table_oid, t.relname AS table_name, " +
            "       a.attnum AS attnum, a.attname AS attname " +
            "  FROM pg_attribute a " +
            "  JOIN pg_class t     ON t.oid = a.attrelid " +
            "  JOIN pg_namespace n ON n.oid = t.relnamespace " +
            " WHERE a.attnum > 0 AND NOT a.attisdropped AND t.relkind = 'r' AND " + SCHEMA_FILTER;

    private final TargetDataSourceRegistry targetRegistry;

    public ErSchemaScanService(TargetDataSourceRegistry targetRegistry) {
        this.targetRegistry = targetRegistry;
    }

    /** 扫描结果聚合体。 */
    public static class ScanResult {
        /** tableName(小写) → 键集合列表（PK + 每个 UK）。 */
        public final Map<String, List<ErKeySet>> keySets = new LinkedHashMap<>();
        /** tableName(小写) → 全部列名集合（小写）。 */
        public final Map<String, Set<String>> tableColumns = new LinkedHashMap<>();
        /** columnName(小写) → 出现该列的表数。 */
        public final Map<String, Integer> columnTableCount = new HashMap<>();
        /** 扫到的表总数（含无 PK/UK 的表）。 */
        public int tableCount;
    }

    /**
     * 扫描指定 env 目标库全库元数据。
     * @param env dev/sit/uat（空走 default-env）
     */
    public ScanResult scan(String env) {
        String effEnv = (env == null || env.isBlank()) ? targetRegistry.getDefaultEnv() : env.trim();
        ScanResult result = new ScanResult();

        DataSource ds;
        try {
            ds = targetRegistry.getByEnv(effEnv);
        } catch (IllegalArgumentException e) {
            log.warn("[er-scan] 目标库不可用 env={}: {}", effEnv, e.getMessage());
            return result;
        }

        // ── SQL-2 先跑：建 (table_oid → attnum → attname) + 每表全列集 + columnTableCount ──
        // oid → (attnum → attname)
        Map<Long, Map<Integer, String>> oidAttrName = new HashMap<>();
        // oid → tableName（小写）
        Map<Long, String> oidTable = new HashMap<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_COLUMNS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long oid = rs.getLong("table_oid");
                String table = lower(rs.getString("table_name"));
                int attnum = rs.getInt("attnum");
                String col = lower(rs.getString("attname"));
                oidTable.put(oid, table);
                oidAttrName.computeIfAbsent(oid, k -> new HashMap<>()).put(attnum, col);
                result.tableColumns.computeIfAbsent(table, k -> new HashSet<>()).add(col);
            }
        } catch (Exception e) {
            log.error("[er-scan] SQL-2 全库列扫描失败 env={}: {}", effEnv, e.getMessage(), e);
            return result;
        }
        // columnTableCount：列在多少张表出现
        for (Set<String> cols : result.tableColumns.values()) {
            for (String c : cols) {
                result.columnTableCount.merge(c, 1, Integer::sum);
            }
        }
        result.tableCount = result.tableColumns.size();

        // ── SQL-1：全库 PK/UK 索引，翻译 indkey → 键列名 ──
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INDEXES);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = lower(rs.getString("table_name"));
                boolean primary = rs.getBoolean("is_primary");
                long oid = rs.getLong("table_oid");
                String indkey = rs.getString("indkey_text");
                Map<Integer, String> attrMap = oidAttrName.get(oid);
                if (attrMap == null) continue;

                List<String> cols = new ArrayList<>();
                boolean valid = true;
                for (String tok : indkey == null ? new String[0] : indkey.trim().split("\\s+")) {
                    int attnum;
                    try { attnum = Integer.parseInt(tok); } catch (NumberFormatException e) { continue; }
                    if (attnum <= 0) { valid = false; break; }   // 表达式索引，无真实列名，整键作废
                    String col = attrMap.get(attnum);
                    if (col == null) { valid = false; break; }
                    cols.add(col);
                }
                if (!valid || cols.isEmpty()) continue;

                result.keySets.computeIfAbsent(table, k -> new ArrayList<>())
                        .add(new ErKeySet(primary ? "PK" : "UNIQUE", cols));
            }
        } catch (Exception e) {
            log.error("[er-scan] SQL-1 全库索引扫描失败 env={}: {}", effEnv, e.getMessage(), e);
            // 列已拿到，索引失败则 keySets 为空 → 推不出关系，返回部分结果
        }

        log.info("[er-scan] env={} 扫描完成：表 {} 张，有键表 {} 张，列种类 {}",
                effEnv, result.tableCount, result.keySets.size(), result.columnTableCount.size());
        return result;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
