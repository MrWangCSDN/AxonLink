package com.axonlink.ai.daoindex.sqlinspect.explain;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.util.JdbcConstants;
import com.axonlink.ai.daoindex.sqlinspect.dto.ExplainResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在目标库（GaussDB / openGauss）上跑 {@code EXPLAIN (GENERIC_PLAN, FORMAT JSON)}，
 * 解析 JSON 提取关键派生指标。
 *
 * <h3>为什么用 GENERIC_PLAN</h3>
 * <p>原始 SQL 含占位符（{@code ?} / {@code #xxx#} 已替换成 {@code ?}），无法绑定真实参数。
 * GaussDB 的 GENERIC_PLAN 选项允许只对参数化语句出"通用计划"，无需绑参。
 *
 * <h3>失败兜底</h3>
 * <p>EXPLAIN 失败（如表不存在、语法不兼容、GaussDB 版本不支持 GENERIC_PLAN）
 * 不会抛异常，统一返回 {@link ExplainResult#failed}，调用方落到 {@code explain_error} 字段即可。
 */
@Service
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class ExplainExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExplainExecutor.class);

    private final TargetDataSourceRegistry targetRegistry;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<ColumnSampleService> sampleServiceProvider;

    public ExplainExecutor(TargetDataSourceRegistry targetRegistry,
                           ObjectMapper objectMapper,
                           org.springframework.beans.factory.ObjectProvider<ColumnSampleService> sampleServiceProvider) {
        this.targetRegistry = targetRegistry;
        this.objectMapper = objectMapper;
        this.sampleServiceProvider = sampleServiceProvider;
    }

    /** 老调用方保留：不传 schema。 */
    public ExplainResult explain(String env, String sql) {
        return explain(env, sql, null, null);
    }

    /** 两参版本保留兼容。 */
    public ExplainResult explain(String env, String sql, String schemaHint) {
        return explain(env, sql, schemaHint, null);
    }

    /**
     * 跑 EXPLAIN 并解析。
     *
     * @param env        目标库 env
     * @param sql        已经把占位符规范化为 {@code ?} 的标准 SQL
     * @param schemaHint 可选：要 {@code SET search_path TO schemaHint} 的 schema 名；
     *                   用于 GaussDB 分布式部署下表所在 schema 不在默认 search_path 时的兜底。
     *                   调用方一般从 {@link TableMetadataService} 拿到首个表的 schema。
     */
    /**
     * 完整版 EXPLAIN 入口。
     *
     * @param env              目标库 env
     * @param sql              原始 SQL（含 {@code ?} 占位符）
     * @param schemaHint       表所在 schema（用于 GaussDB 分布式下表名限定）
     * @param tableMetadataMap 涉及表的元数据（含字段类型）。
     *                         非空时会按字段类型给 {@code ?} 生成真实字面量替换；
     *                         为空时退化为 NULL 替换（runtime_rating 会因短路失效）。
     */
    public ExplainResult explain(String env, String sql, String schemaHint,
                                 Map<String, TableMetadata> tableMetadataMap) {
        long start = System.currentTimeMillis();
        if (sql == null || sql.isBlank()) {
            return ExplainResult.failed("SQL 为空", 0);
        }

        DataSource ds;
        try {
            ds = targetRegistry.getByEnv(env);
        } catch (Exception e) {
            return ExplainResult.failed("env 不存在：" + e.getMessage(), 0);
        }

        // ════════════════════════════════════════════════════════════════════
        // 策略 1：按字段类型生成类型安全字面量替换 ?（最佳方案）
        //
        // WHERE txn_dt = ? AND acct_no = ?
        //   ↓ 解析每个 ? 的字段 + 查元数据字段类型
        // WHERE txn_dt = CURRENT_DATE AND acct_no = 'X'
        //
        // 优化器能按真实值生成 plan，不会被 NULL 短路。
        // ════════════════════════════════════════════════════════════════════
        if (tableMetadataMap != null && !tableMetadataMap.isEmpty() && sql.indexOf('?') >= 0) {
            String typedSql;
            try {
                typedSql = substituteQuestionMarksTyped(sql, env, tableMetadataMap);
            } catch (ColumnSampleService.SchemaDriftException e) {
                // 🚨 表 / 字段不存在：直接终止整个 EXPLAIN，写入 explain_error，DBA 报表可筛
                long elapsed = System.currentTimeMillis() - start;
                log.warn("[dii-explain] Schema 漂移导致 EXPLAIN 终止：{}", e.getMessage());
                return ExplainResult.failed("[SCHEMA_DRIFT] " + e.getMessage(), elapsed);
            }
            String qualifiedTypedSql = rewriteWithSchema(typedSql, schemaHint);
            ExplainResult typed = runExplainOnce(ds,
                    "EXPLAIN (FORMAT JSON, COSTS) " + qualifiedTypedSql, null, start);
            if (typed.isSuccess() && !typed.isOneTimeFilterFalse()) {
                log.info("[dii-explain] 类型安全替换成功，cost={} seqScan={} elapsed={}ms",
                        typed.getTopCost(), typed.isHasSeqScan(), typed.getElapsedMs());
                return typed;
            }
            if (typed.isSuccess()) {
                log.debug("[dii-explain] 类型安全替换仍短路（可能某字段类型识别失败），继续走兜底");
            } else {
                log.debug("[dii-explain] 类型安全替换失败：{}", typed.getErrorMessage());
            }
        }

        // 占位符规范化：? → $1, $2, ...
        String sqlWithDollar = substituteQuestionMarks(sql);
        int paramCount = countDollarParams(sqlWithDollar);

        // Schema 限定：把表名重写成 schema.table
        String qualifiedSql = rewriteWithSchema(sqlWithDollar, schemaHint);

        // ════════════════════════════════════════════════════════════════════
        // 策略 1：PREPARE + EXECUTE + EXPLAIN GENERIC_PLAN（带参数 SQL 的正确姿势）
        //
        // 为什么必须用 PREPARE 而不是直接 EXPLAIN ... WHERE x = $1：
        //   直接形式下，GaussDB 把 $1 当 NULL 字面量处理，整条 SQL 变成 WHERE x = NULL（恒假），
        //   优化器短路，plan 里出现 "One-Time Filter: false"，结构上的 Seq Scan 永远不会真跑。
        //   导致 runtime_rating 系统性误判为"差"。
        //
        //   PREPARE 形式下，$1 成为真正的"未知参数"，GENERIC_PLAN 让优化器按参数化通用场景估算，
        //   plan 能真实反映实际运行表现。
        // ════════════════════════════════════════════════════════════════════
        if (paramCount > 0) {
            log.info("[dii-explain] 走 PREPARE 路径，paramCount={} schemaHint={}", paramCount, schemaHint);
            ExplainResult prepared = tryPreparedExplain(ds, qualifiedSql, paramCount, schemaHint, start);
            if (prepared.isSuccess()) {
                log.info("[dii-explain] PREPARE 成功，cost={} seqScan={} oneTimeFilterFalse={} elapsed={}ms",
                        prepared.getTopCost(), prepared.isHasSeqScan(),
                        prepared.isOneTimeFilterFalse(), prepared.getElapsedMs());
                return prepared;
            }
            // PREPARE 失败，继续兜底
            log.warn("[dii-explain] PREPARE 方案失败，走 NULL 兜底：{}", prepared.getErrorMessage());
        } else {
            log.info("[dii-explain] 无参数 SQL，直接跑 EXPLAIN");
        }

        // ════════════════════════════════════════════════════════════════════
        // 策略 2：NULL 兜底（无参数或 PREPARE 失败时用）
        //
        // 把 $n / ? 全换成 NULL，plan 可能被短路（One-Time Filter: false），
        // 但 RuntimeRatingDeriver 会识别这种情况并返回 null，避免系统性误判。
        // ════════════════════════════════════════════════════════════════════
        String sqlWithNull = substituteQuestionMarksWithNull(sql);
        String qualifiedSqlWithNull = rewriteWithSchema(sqlWithNull, schemaHint);

        List<Attempt> attempts = new ArrayList<>();
        attempts.add(new Attempt("EXPLAIN (FORMAT JSON, COSTS) " + qualifiedSqlWithNull, null));
        if (!qualifiedSqlWithNull.equals(sqlWithNull)) {
            attempts.add(new Attempt("EXPLAIN (FORMAT JSON, COSTS) " + sqlWithNull, schemaHint));
        }

        Throwable lastErr = null;
        for (Attempt att : attempts) {
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(true);
                stmt.setQueryTimeout(15);

                if (att.schemaToSet != null && !att.schemaToSet.isBlank()) {
                    String safeSchema = att.schemaToSet.replaceAll("[^A-Za-z0-9_]", "");
                    if (!safeSchema.isEmpty()) {
                        stmt.execute("SET search_path TO " + safeSchema + ", public");
                    }
                }

                try (ResultSet rs = stmt.executeQuery(att.explainSql)) {
                    StringBuilder json = new StringBuilder();
                    while (rs.next()) {
                        json.append(rs.getString(1));
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    return parsePlan(json.toString(), elapsed);
                }
            } catch (Throwable t) {
                lastErr = t;
                log.debug("[dii-explain] 兜底尝试失败：{}", t.getMessage());
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        String msg = lastErr == null ? "未知失败" : (lastErr.getClass().getSimpleName() + ": " + lastErr.getMessage());
        log.warn("[dii-explain] 失败 env={} schemaHint={} elapsed={}ms reason={}",
                env, schemaHint, elapsed, msg);
        return ExplainResult.failed(msg, elapsed);
    }

    /**
     * PREPARE + EXECUTE + EXPLAIN GENERIC_PLAN 三段式执行。
     *
     * <p>关键点：
     * <ul>
     *   <li>PREPARE 时不绑参数值，{@code $n} 成为真正的 Placeholder</li>
     *   <li>EXECUTE 时传 NULL 值数组，但 GENERIC_PLAN 选项让优化器忽略这些具体值，
     *       生成"通用计划"——即不依赖具体参数的 plan</li>
     *   <li>finally 必清理 DEALLOCATE，避免 prepared stmt 累积</li>
     * </ul>
     */
    private ExplainResult tryPreparedExplain(DataSource ds, String preparedSql, int paramCount,
                                             String schemaHint, long start) {
        // 唯一语句名，避免同 session 复用（理论上每次新连接，但防御式保护）
        String stmtName = "dii_stmt_" + Thread.currentThread().getId() + "_" + Math.abs(System.nanoTime());
        // EXECUTE 的参数列表：按数量填 NULL
        StringBuilder nullArgs = new StringBuilder();
        for (int i = 1; i <= paramCount; i++) {
            if (i > 1) nullArgs.append(", ");
            nullArgs.append("NULL");
        }

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.setQueryTimeout(15);

            // 1. SET search_path
            if (schemaHint != null && !schemaHint.isBlank()) {
                String safeSchema = schemaHint.replaceAll("[^A-Za-z0-9_]", "");
                if (!safeSchema.isEmpty()) {
                    stmt.execute("SET search_path TO " + safeSchema + ", public");
                    log.debug("[dii-explain-prep] set search_path={}", safeSchema);
                }
            }

            // 2. PREPARE
            String prepSql = "PREPARE " + stmtName + " AS " + preparedSql;
            log.debug("[dii-explain-prep] PREPARE sql: {}", prepSql);
            stmt.execute(prepSql);

            try {
                // 3. EXPLAIN EXECUTE（不加 GENERIC_PLAN，GaussDB 5 不支持这个选项）
                //
                // 注意：没有 GENERIC_PLAN 时，GaussDB 会按 EXECUTE 传入的具体值优化 plan。
                // 我们传的是 NULL，所以 `WHERE x = NULL` 会恒假，plan 出现 One-Time Filter: false。
                // 这时候 RuntimeRatingDeriver 会识别到短路并返回 null（不派生 runtime_rating），
                // 避免系统性误判；规则引擎评级依然有效。
                //
                // 对于无 WHERE 参数的 SQL（如 SELECT COUNT(*) FROM t），plan 不会短路，runtime_rating 正常派生。
                String explainSql = "EXPLAIN (FORMAT JSON, COSTS) " +
                        "EXECUTE " + stmtName + "(" + nullArgs + ")";
                log.debug("[dii-explain-prep] EXPLAIN sql: {}", explainSql);
                try (ResultSet rs = stmt.executeQuery(explainSql)) {
                    StringBuilder json = new StringBuilder();
                    while (rs.next()) {
                        json.append(rs.getString(1));
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    ExplainResult r = parsePlan(json.toString(), elapsed);
                    log.debug("[dii-explain-prep] PREPARE EXECUTE 返回 json 长度={}, success={}",
                            json.length(), r.isSuccess());
                    return r;
                }
            } finally {
                // 4. 清理：必跑，即便 EXPLAIN 抛异常
                try {
                    stmt.execute("DEALLOCATE " + stmtName);
                } catch (Exception ignore) { /* 清理失败不影响主逻辑 */ }
            }
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[dii-explain-prep] 失败：{} ({})", t.getClass().getSimpleName(), t.getMessage());
            return ExplainResult.failed("PREPARE+EXECUTE 失败：" + t.getMessage(), elapsed);
        }
    }

    /** 统计 SQL 里 {@code $n} 占位符的最大编号（即所需参数个数）。 */
    private static int countDollarParams(String sql) {
        if (sql == null) return 0;
        int max = 0;
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                int j = i + 1;
                int num = 0;
                while (j < n && Character.isDigit(sql.charAt(j))) {
                    num = num * 10 + (sql.charAt(j) - '0');
                    j++;
                }
                if (num > max) max = num;
                i = j;
            } else {
                i++;
            }
        }
        return max;
    }

    /** 一次 EXPLAIN 尝试：SQL 文本 + 是否需要先 SET search_path。 */
    private static class Attempt {
        final String explainSql;
        final String schemaToSet;
        Attempt(String explainSql, String schemaToSet) {
            this.explainSql = explainSql;
            this.schemaToSet = schemaToSet;
        }
    }

    /**
     * 把 SQL 里的 {@code ?} 占位符依次替换成 {@code $1}, {@code $2}, ...
     *
     * <p>为什么不用 NULL：
     * <ul>
     *   <li>{@code WHERE a = NULL} 永远为 false，优化器会选择 Seq Scan（哪怕列上有索引），
     *       导致 runtime_rating 系统性偏差（总是被判为"差"）。</li>
     *   <li>{@code $n} 是 PostgreSQL / GaussDB 的原生参数标记，优化器会按"未知值"估算，
     *       产生的 plan 更贴近真实运行。</li>
     * </ul>
     *
     * <p>跳过字符串字面量、标识符引号、SQL 注释，不误伤里面的 {@code ?}。
     */
    static String substituteQuestionMarks(String sql) {
        return substituteQuestionMarksImpl(sql, null, true);
    }

    /**
     * 按字段类型逐个替换 {@code ?} 为类型安全的 SQL 字面量。
     * 优先从数据库采样真实值，采样不到再退类型默认。
     */
    String substituteQuestionMarksTyped(String sql, String env,
                                        Map<String, TableMetadata> tableMetadataMap) {
        if (sql == null || sql.indexOf('?') < 0) return sql;
        List<ParameterTypeResolver.ParamCtx> ctxs = ParameterTypeResolver.resolve(sql);
        String ownerTable = (tableMetadataMap == null || tableMetadataMap.isEmpty())
                ? null : tableMetadataMap.keySet().iterator().next();
        ColumnSampleService sampler = sampleServiceProvider == null ? null : sampleServiceProvider.getIfAvailable();
        List<String> literals = ParameterTypeResolver.toLiterals(
                ctxs, tableMetadataMap, sampler, env, ownerTable);

        StringBuilder out = new StringBuilder(sql.length() + 32);
        int i = 0, n = sql.length();
        int qIdx = 0;
        // 复用 ImplT 扫描器跳过字符串 / 注释，仅替换裸 ?
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(c); i++;
                while (i < n) {
                    char ch = sql.charAt(i); out.append(ch);
                    if (ch == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { out.append('\''); i += 2; }
                        else { i++; break; }
                    } else i++;
                }
            } else if (c == '\"') {
                out.append(c); i++;
                while (i < n) { char ch = sql.charAt(i); out.append(ch); i++; if (ch == '\"') break; }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') { out.append(sql.charAt(i)); i++; }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) { out.append(sql.substring(i)); i = n; }
                else { out.append(sql, i, end + 2); i = end + 2; }
            } else if (c == '?') {
                String literal = (qIdx < literals.size() && literals.get(qIdx) != null)
                        ? literals.get(qIdx) : "NULL";
                out.append(literal);
                qIdx++;
                i++;
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
    }

    /** 封装一次 EXPLAIN 调用，便于复用；失败返回 failed 对象，不抛。 */
    private ExplainResult runExplainOnce(DataSource ds, String explainSql, String schemaHint, long start) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.setQueryTimeout(15);
            if (schemaHint != null && !schemaHint.isBlank()) {
                String safeSchema = schemaHint.replaceAll("[^A-Za-z0-9_]", "");
                if (!safeSchema.isEmpty()) {
                    stmt.execute("SET search_path TO " + safeSchema + ", public");
                }
            }
            try (ResultSet rs = stmt.executeQuery(explainSql)) {
                StringBuilder json = new StringBuilder();
                while (rs.next()) json.append(rs.getString(1));
                return parsePlan(json.toString(), System.currentTimeMillis() - start);
            }
        } catch (Throwable t) {
            return ExplainResult.failed(
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    /** 兜底版：把 ? 全部替换成 NULL，用于 GaussDB 不支持 $n 语法的 fallback。 */
    static String substituteQuestionMarksWithNull(String sql) {
        return substituteQuestionMarksImpl(sql, "NULL", false);
    }

    /** 内部实现：mode=true 用 $n 递增，mode=false 用 fixed token（如 NULL）。 */
    private static String substituteQuestionMarksImpl(String sql, String token, boolean numbered) {
        if (sql == null || sql.indexOf('?') < 0) return sql;
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int i = 0, n = sql.length();
        int paramIdx = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(c); i++;
                while (i < n) {
                    char ch = sql.charAt(i); out.append(ch);
                    if (ch == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { out.append('\''); i += 2; }
                        else { i++; break; }
                    } else i++;
                }
            } else if (c == '\"') {
                out.append(c); i++;
                while (i < n) { char ch = sql.charAt(i); out.append(ch); i++; if (ch == '\"') break; }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') { out.append(sql.charAt(i)); i++; }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) { out.append(sql.substring(i)); i = n; }
                else { out.append(sql, i, end + 2); i = end + 2; }
            } else if (c == '?') {
                if (numbered) { paramIdx++; out.append('$').append(paramIdx); }
                else out.append(token);
                i++;
            } else { out.append(c); i++; }
        }
        return out.toString();
    }

    /**
     * 用 Druid SQL AST 把每张表名补上 {@code schema.} 前缀。
     *
     * <p>示例：{@code SELECT * FROM t WHERE a=?} → {@code SELECT * FROM ccbs_uat_db.t WHERE a=?}
     *
     * <p>已经带 schema 的表（如 {@code other.t}）不动。解析失败时返回原 SQL。
     */
    private String rewriteWithSchema(String sql, String schemaHint) {
        if (sql == null || schemaHint == null || schemaHint.isBlank()) return sql;
        String safeSchema = schemaHint.replaceAll("[^A-Za-z0-9_]", "");
        if (safeSchema.isEmpty()) return sql;

        try {
            SQLStatement stmt = SQLUtils.parseSingleStatement(sql, JdbcConstants.POSTGRESQL);
            stmt.accept(new SQLASTVisitorAdapter() {
                @Override
                public boolean visit(SQLExprTableSource x) {
                    if (x.getSchema() == null || x.getSchema().isBlank()) {
                        x.setSchema(safeSchema);
                    }
                    return true;
                }
            });
            String rewritten = stmt.toString();
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten;
            }
        } catch (Throwable t) {
            log.debug("[dii-explain] 补 schema 失败（语法不兼容），回退到原 SQL：{}", t.getMessage());
        }
        return sql;
    }

    /**
     * GaussDB EXPLAIN FORMAT JSON 输出形如：
     * <pre>
     * [
     *   {
     *     "Plan": {
     *       "Node Type": "Index Scan",
     *       "Total Cost": 8.31,
     *       "Plan Rows": 1,
     *       "Plan Width": 256,
     *       "Relation Name": "kapb_txn_log",
     *       "Index Name": "idx_xxx",
     *       "Plans": [...]
     *     }
     *   }
     * ]
     * </pre>
     */
    private ExplainResult parsePlan(String rawJson, long elapsedMs) {
        ExplainResult r = new ExplainResult();
        r.setElapsedMs(elapsedMs);
        if (rawJson == null || rawJson.isBlank()) {
            r.setSuccess(false);
            r.setErrorMessage("EXPLAIN 返回空");
            return r;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode topPlan;
            if (root.isArray() && !root.isEmpty()) {
                topPlan = root.get(0).path("Plan");
            } else {
                topPlan = root.path("Plan");
            }
            if (topPlan.isMissingNode() || topPlan.isNull()) {
                r.setSuccess(false);
                r.setErrorMessage("EXPLAIN JSON 缺少 Plan 节点");
                return r;
            }

            r.setSuccess(true);
            r.setRawPlan(root);
            r.setTopCost(topPlan.path("Total Cost").asDouble(0d));
            r.setTopPlanRows(topPlan.path("Plan Rows").asLong(0L));
            r.setTopPlanWidth(topPlan.path("Plan Width").asInt(0));

            Set<String> nodeTypes = new LinkedHashSet<>();
            Set<String> tables = new LinkedHashSet<>();
            Set<String> indexes = new LinkedHashSet<>();
            walkPlan(topPlan, nodeTypes, tables, indexes);

            r.setNodeTypes(new ArrayList<>(nodeTypes));
            r.setScannedTables(new ArrayList<>(tables));
            r.setUsedIndexes(new ArrayList<>(indexes));
            r.setHasSeqScan(nodeTypes.stream().anyMatch(s -> s.contains("Seq Scan")));
            r.setHasIndexScan(nodeTypes.stream().anyMatch(s -> s.contains("Index Scan") || s.contains("Index Only Scan")));
            r.setHasSort(nodeTypes.stream().anyMatch("Sort"::equals));
            r.setHasJoin(nodeTypes.stream().anyMatch(s -> s.contains("Join") || "Nested Loop".equals(s)));

            // 顶层 One-Time Filter: "false" 表示优化器把整条 SQL 视为恒假短路（参数替换导致的假阳性）
            JsonNode oneTime = topPlan.path("One-Time Filter");
            if (!oneTime.isMissingNode() && !oneTime.isNull()) {
                String v = oneTime.asText("").trim().toLowerCase();
                r.setOneTimeFilterFalse("false".equals(v));
            }
            return r;
        } catch (Exception e) {
            r.setSuccess(false);
            r.setErrorMessage("解析 EXPLAIN JSON 失败：" + e.getMessage());
            log.warn("[dii-explain] JSON 解析失败 elapsed={}ms json={}", elapsedMs, truncate(rawJson, 200));
            return r;
        }
    }

    /** 递归遍历计划树，抽取 NodeType / RelationName / IndexName。 */
    private void walkPlan(JsonNode plan, Set<String> nodeTypes, Set<String> tables, Set<String> indexes) {
        if (plan == null || plan.isMissingNode() || plan.isNull()) return;
        String nodeType = plan.path("Node Type").asText("");
        if (!nodeType.isEmpty()) nodeTypes.add(nodeType);
        String rel = plan.path("Relation Name").asText("");
        if (!rel.isEmpty()) tables.add(rel);
        String idx = plan.path("Index Name").asText("");
        if (!idx.isEmpty()) indexes.add(idx);

        JsonNode subPlans = plan.path("Plans");
        if (subPlans.isArray()) {
            for (JsonNode sub : subPlans) {
                walkPlan(sub, nodeTypes, tables, indexes);
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
