package com.axonlink.ai.daoindex.sqlinspect.analyzer;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.util.JdbcConstants;
import com.axonlink.ai.daoindex.sqlinspect.dto.PredicateExtract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于 Druid SchemaStatVisitor 的 SQL 谓词抽取器。
 *
 * <p>2a.1 版本：仅抽取等值谓词（{@code =}、{@code IN}）。
 * 范围、LIKE、函数失效识别等在 2a.2 追加。
 *
 * <p>输出按表聚合（一条 SQL 可能涉及多张表，如 JOIN / 子查询）。
 */
@Service
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class SqlPredicateAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SqlPredicateAnalyzer.class);

    /** SQL 语句类型，决定是否进入规则引擎评级。 */
    public enum SqlKind {
        SELECT,
        /** UPDATE / DELETE — 走 WHERE 条件做索引评级，和 SELECT 同路径。 */
        UPDATE,
        DELETE,
        /** INSERT VALUES — 没 WHERE，不参与评级（规则引擎出 NOT_APPLICABLE）。 */
        INSERT_VALUES,
        /** INSERT ... SELECT — 分析内部 SELECT 的 WHERE。 */
        INSERT_SELECT,
        /** 无法识别或 Druid 解析失败。 */
        UNKNOWN
    }

    /** 谓词抽取结果 + SQL 类型。 */
    public static class Result {
        public final SqlKind kind;
        public final Map<String, PredicateExtract> predicatesByTable;
        public Result(SqlKind kind, Map<String, PredicateExtract> predicatesByTable) {
            this.kind = kind;
            this.predicatesByTable = predicatesByTable;
        }
    }

    /** Druid 条件类型常量。等值为主，其余在 2a.2 扩展。 */
    private static final String OP_EQ = "=";
    private static final String OP_IN = "in";

    /**
     * 银行内部 DAO 模板占位符预处理规则。
     * sunline / iBatis / MyBatis 等会写成 {@code #xxx#}、{@code ${xxx}}、{@code :xxx}，
     * 这些不是标准 SQL，Druid 解析会失败。统一替换成 {@code ?}。
     */
    private static final Pattern SUNLINE_HASH_PLACEHOLDER = Pattern.compile("#([A-Za-z_][A-Za-z0-9_.]*)#");
    private static final Pattern DOLLAR_BRACE_PLACEHOLDER = Pattern.compile("\\$\\{[^}]+\\}");
    /**
     * Spring NamedParameterJdbcTemplate 形式的 {@code :paramName}；
     * 但要避开 PG 的 {@code ::type} 强制类型转换（前导 {@code :} 后紧跟另一个 {@code :}）。
     */
    private static final Pattern COLON_NAMED_PLACEHOLDER  =
            Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)(?![\\w:])");

    /**
     * 解析 SQL，按表聚合谓词。
     *
     * @param sql 原始 SQL
     * @return 以 "小写表名" 为 key 的 {@link PredicateExtract}；解析失败返回空 map
     */
    /**
     * 兼容老签名：只返回谓词 Map，调用方不关心 SQL 类型时使用。
     */
    public Map<String, PredicateExtract> extract(String sql) {
        return analyze(sql).predicatesByTable;
    }

    /**
     * 完整分析：返回 SQL 类型 + 谓词 Map。
     * 支持 SELECT / UPDATE / DELETE / INSERT(VALUES 或 SELECT)。
     */
    public Result analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Result(SqlKind.UNKNOWN, new LinkedHashMap<>());
        }
        // 银行 DAO 模板占位符预处理：#xxx# / ${xxx} / :xxx → ?
        String normalizedSql = normalizePlaceholders(sql);

        List<SQLStatement> statements;
        try {
            // GaussDB / openGauss 兼容 PG 语法
            statements = SQLUtils.parseStatements(normalizedSql, JdbcConstants.POSTGRESQL);
        } catch (Exception e) {
            log.warn("[dii-sqlinspect] Druid 解析 SQL 失败：{}；原 SQL：{}", e.getMessage(), truncate(sql, 200));
            return new Result(SqlKind.UNKNOWN, new LinkedHashMap<>());
        }
        if (statements == null || statements.isEmpty()) {
            return new Result(SqlKind.UNKNOWN, new LinkedHashMap<>());
        }

        SQLStatement stmt = statements.get(0);
        SqlKind kind = classify(stmt);

        // INSERT VALUES 无 WHERE，不需要索引评级，直接返回表名即可
        if (kind == SqlKind.INSERT_VALUES) {
            Map<String, PredicateExtract> result = new LinkedHashMap<>();
            collectTableNames(stmt, result);
            return new Result(kind, result);
        }

        com.alibaba.druid.sql.dialect.postgresql.visitor.PGSchemaStatVisitor visitor =
                new com.alibaba.druid.sql.dialect.postgresql.visitor.PGSchemaStatVisitor();
        try {
            stmt.accept(visitor);
        } catch (Exception e) {
            log.warn("[dii-sqlinspect] Druid visitor 执行失败：{}", e.getMessage());
            return new Result(kind, new LinkedHashMap<>());
        }

        Map<String, PredicateExtract> result = new LinkedHashMap<>();
        // 用 visitor.getConditions() 直接拿到 "column 操作符 value" 三元组，最适合我们场景
        for (TableStat.Condition cond : visitor.getConditions()) {
            TableStat.Column col = cond.getColumn();
            if (col == null) continue;
            String tableRaw = col.getTable();
            String colRaw = col.getName();
            if (tableRaw == null || colRaw == null || tableRaw.isBlank() || colRaw.isBlank()) {
                continue;
            }
            String table = stripSchema(tableRaw).toLowerCase(Locale.ROOT);
            String column = colRaw.toLowerCase(Locale.ROOT);
            String op = cond.getOperator();
            if (op == null) continue;
            String opNorm = op.trim().toLowerCase(Locale.ROOT);

            PredicateExtract pe = result.computeIfAbsent(table, PredicateExtract::new);
            if (OP_EQ.equals(opNorm) || OP_IN.equals(opNorm)) {
                pe.getEqualityColumns().add(column);
            }
            // 其他操作符（>、<、LIKE...）2a.1 暂忽略
        }

        // 即使没有任何谓词，也要把 SQL 涉及的表登记进来（方便规则引擎知道"这张表一个谓词都没有"）
        for (TableStat.Name tableName : visitor.getTables().keySet()) {
            if (tableName == null || tableName.getName() == null) continue;
            String t = stripSchema(tableName.getName()).toLowerCase(Locale.ROOT);
            result.computeIfAbsent(t, PredicateExtract::new);
        }

        // ORDER BY
        try {
            for (TableStat.Column col : visitor.getOrderByColumns()) {
                if (col == null || col.getName() == null || col.getTable() == null) continue;
                String t = stripSchema(col.getTable()).toLowerCase(Locale.ROOT);
                String c = col.getName().toLowerCase(Locale.ROOT);
                PredicateExtract pe = result.computeIfAbsent(t, PredicateExtract::new);
                if (!pe.getOrderByColumns().contains(c)) {
                    pe.getOrderByColumns().add(c);
                }
            }
        } catch (Throwable ignore) { /* 某些 SQL 可能没有 ORDER BY visitor 方法异常，吞掉 */ }

        // GROUP BY
        try {
            for (TableStat.Column col : visitor.getGroupByColumns()) {
                if (col == null || col.getName() == null || col.getTable() == null) continue;
                String t = stripSchema(col.getTable()).toLowerCase(Locale.ROOT);
                String c = col.getName().toLowerCase(Locale.ROOT);
                PredicateExtract pe = result.computeIfAbsent(t, PredicateExtract::new);
                if (!pe.getGroupByColumns().contains(c)) {
                    pe.getGroupByColumns().add(c);
                }
            }
        } catch (Throwable ignore) { /* 类似 */ }

        return new Result(kind, result);
    }

    /** 判定 SQL 类型。 */
    private SqlKind classify(SQLStatement stmt) {
        if (stmt instanceof SQLSelectStatement) return SqlKind.SELECT;
        if (stmt instanceof SQLUpdateStatement) return SqlKind.UPDATE;
        if (stmt instanceof SQLDeleteStatement) return SqlKind.DELETE;
        if (stmt instanceof SQLInsertStatement) {
            SQLInsertStatement ins = (SQLInsertStatement) stmt;
            // INSERT ... SELECT 走 SELECT 分析分支
            if (ins.getQuery() != null) return SqlKind.INSERT_SELECT;
            return SqlKind.INSERT_VALUES;
        }
        return SqlKind.UNKNOWN;
    }

    /** INSERT VALUES 专用：只提取目标表名，构造空 predicates。 */
    private void collectTableNames(SQLStatement stmt, Map<String, PredicateExtract> out) {
        if (stmt instanceof SQLInsertStatement) {
            SQLInsertStatement ins = (SQLInsertStatement) stmt;
            if (ins.getTableName() != null) {
                String t = stripSchema(ins.getTableName().getSimpleName()).toLowerCase(Locale.ROOT);
                out.computeIfAbsent(t, PredicateExtract::new);
            }
        }
    }

    /**
     * 把银行 DAO 常见的模板占位符统一替换成 JDBC 标准 {@code ?}，便于 Druid 解析。
     * <ul>
     *   <li>{@code #xxxYyy#} — sunline / iBatis 等</li>
     *   <li>{@code ${xxxYyy}} — MyBatis 等</li>
     *   <li>{@code :xxxYyy}  — Spring NamedParameterJdbcTemplate 等</li>
     * </ul>
     * 注意：{@code :xxx} 要避开 PostgreSQL 的 {@code ::} 类型转换语法（不处理这种情况）。
     */
    public static String normalizePlaceholders(String sql) {
        if (sql == null) return "";
        String s = SUNLINE_HASH_PLACEHOLDER.matcher(sql).replaceAll("?");
        s = DOLLAR_BRACE_PLACEHOLDER.matcher(s).replaceAll("?");
        s = COLON_NAMED_PLACEHOLDER.matcher(s).replaceAll("?");
        return s;
    }

    /** 去掉 schema 前缀：{@code public.t_acct_log} → {@code t_acct_log}。 */
    private static String stripSchema(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 提供给测试代码：列表形态的全部条件，便于断言。 */
    public List<String> debugExtractOperators(String sql) {
        List<String> ops = new ArrayList<>();
        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.POSTGRESQL);
            if (statements == null || statements.isEmpty()) return ops;
            com.alibaba.druid.sql.dialect.postgresql.visitor.PGSchemaStatVisitor v =
                    new com.alibaba.druid.sql.dialect.postgresql.visitor.PGSchemaStatVisitor();
            statements.get(0).accept(v);
            for (TableStat.Condition c : v.getConditions()) {
                ops.add(c.toString());
            }
        } catch (Exception ignored) { /* 测试辅助，吞错 */ }
        return ops;
    }
}
