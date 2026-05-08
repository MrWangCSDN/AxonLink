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
        // 唯一策略：按字段类型生成类型安全字面量替换 ?（绝不出 NULL）
        //
        // WHERE txn_dt = ? AND acct_no = ?
        //   ↓ ParameterTypeResolver 识别每个 ? 对应的字段 + 元数据查类型
        //   ↓ ParameterTypeResolver.literalFor(dataType) 返回类型默认值，永不返回 NULL
        // WHERE txn_dt = CURRENT_DATE AND acct_no = 'X'
        //
        // 不再做：① 真实数据采样（避开 SELECT 权限和 DB 开销）
        //         ② NULL 兜底（避免 GaussDB 把 col=NULL 短路成 One-Time Filter:false）
        //         ③ PREPARE+GENERIC_PLAN（typed 替换已稳定有效，无需参数化兜底）
        // ════════════════════════════════════════════════════════════════════
        String typedSql;
        try {
            typedSql = substituteQuestionMarksTyped(sql, env, tableMetadataMap);
        } catch (ColumnSampleService.SchemaDriftException e) {
            // 🚨 表 / 字段不存在（如果未来再开 sampler）：直接终止 EXPLAIN，写入 explain_error
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[dii-explain] Schema 漂移导致 EXPLAIN 终止：{}", e.getMessage());
            return ExplainResult.failed("[SCHEMA_DRIFT] " + e.getMessage(), elapsed);
        }

        // 优先带 schema 限定执行；失败再退回不限定 + SET search_path
        String qualifiedTypedSql = rewriteWithSchema(typedSql, schemaHint);
        ExplainResult result = runExplainOnce(ds,
                "EXPLAIN (FORMAT JSON, COSTS) " + qualifiedTypedSql, null, start);
        if (result.isSuccess()) {
            log.info("[dii-explain] 类型安全替换成功，cost={} seqScan={} elapsed={}ms",
                    result.getTopCost(), result.isHasSeqScan(), result.getElapsedMs());
            return result;
        }
        // 第一次带 schema 失败时，再不限定试一次（用 SET search_path 提示）
        if (!qualifiedTypedSql.equals(typedSql) && schemaHint != null && !schemaHint.isBlank()) {
            ExplainResult retry = runExplainOnce(ds,
                    "EXPLAIN (FORMAT JSON, COSTS) " + typedSql, schemaHint, start);
            if (retry.isSuccess()) {
                log.info("[dii-explain] 类型安全替换+search_path 成功，cost={} seqScan={} elapsed={}ms",
                        retry.getTopCost(), retry.isHasSeqScan(), retry.getElapsedMs());
                return retry;
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.warn("[dii-explain] 失败 env={} schemaHint={} elapsed={}ms reason={}",
                env, schemaHint, elapsed, result.getErrorMessage());
        return result;
    }

    /**
     * 按字段类型逐个替换 {@code ?} 为类型安全的 SQL 字面量。
     *
     * <p><b>已改为纯"类型默认值"模式，不再做真实数据采样</b>：
     * <ul>
     *   <li>避开 ColumnSampleService 对目标库 SELECT 权限的依赖</li>
     *   <li>省掉每个 ? 一次 DB 往返（一条 SQL 多个 ? 时差距明显）</li>
     *   <li>类型默认值（'X' / 1 / CURRENT_DATE / 'X'::uuid 等）已覆盖银行 GaussDB 全部高频类型，
     *       未识别的字段类型也兜底成 {@code 'X'}，不会出现 NULL → 不会触发优化器短路</li>
     * </ul>
     * <p>plan 准确性影响：很小。优化器对单点等值条件的 selectivity 估计基于直方图统计，
     * 跟字面量是否"真实存在"没关系；只要类型匹配 + 不是 NULL 即可。
     */
    String substituteQuestionMarksTyped(String sql, String env,
                                        Map<String, TableMetadata> tableMetadataMap) {
        if (sql == null || sql.indexOf('?') < 0) return sql;
        List<ParameterTypeResolver.ParamCtx> ctxs = ParameterTypeResolver.resolve(sql);
        // 不再传 sampler，强制走纯类型默认值路径
        List<String> literals = ParameterTypeResolver.toLiterals(
                ctxs, tableMetadataMap, /* sampleService */ null, env, /* ownerTable */ null);

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
