package com.axonlink.ai.daoindex.sqlinspect.analyzer;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.util.JdbcConstants;
import com.axonlink.ai.daoindex.sqlinspect.dto.PredicateExtract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 语句类型 + 涉及表名 抽取器。
 *
 * <p><b>「EXPLAIN 优先」管线重构后职责收窄</b>：
 * <ul>
 *   <li>语句类型判定全部交给 {@link SqlKindDetector}（首关键字正则，不依赖 Druid 解析成功）；</li>
 *   <li>本类只负责「尽力抽取 SQL 涉及的表名」，喂给表元数据采集 / EXPLAIN / involved_tables；</li>
 *   <li>不再做最左匹配规则引擎需要的谓词（等值 / ORDER BY / GROUP BY）抽取——规则引擎已下线。</li>
 * </ul>
 *
 * <p>{@link #normalizePlaceholders} 仍保留：EXPLAIN 路径（{@code SqlInspectionService}）
 * 也复用它把银行 DAO 模板占位符规范化成 JDBC {@code ?}。
 */
@Service
public class SqlPredicateAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SqlPredicateAnalyzer.class);

    /** SQL 语句类型。复用给 {@link SqlKindDetector} 作为返回枚举，避免另造类型。 */
    public enum SqlKind {
        SELECT,
        /** UPDATE / DELETE — 有 WHERE，走 EXPLAIN。 */
        UPDATE,
        DELETE,
        /** INSERT VALUES — 不参与索引评级（overall_rating=NOT_APPLICABLE，不 EXPLAIN）。 */
        INSERT_VALUES,
        /** INSERT ... SELECT — 新管线同样按 INSERT 处理（NOT_APPLICABLE，不 EXPLAIN）。 */
        INSERT_SELECT,
        /** 无法识别。新管线对 UNKNOWN 仍尝试 EXPLAIN。 */
        UNKNOWN
    }

    /** 抽取结果：SQL 类型 + 按表聚合的占位对象（仅含表名，谓词字段恒为空）。 */
    public static class Result {
        public final SqlKind kind;
        /** key = 小写表名；value 是只填了 tableName 的 {@link PredicateExtract}（谓词集合留空）。 */
        public final Map<String, PredicateExtract> predicatesByTable;
        public Result(SqlKind kind, Map<String, PredicateExtract> predicatesByTable) {
            this.kind = kind;
            this.predicatesByTable = predicatesByTable;
        }
    }

    /**
     * sunline / iBatis 风格 {@code #xxx#} 占位符。
     *
     * <p>放宽到匹配 {@code #} 之间任意非 {@code #}、非换行字符，以兼容真实业务 SQL 里
     * 出现的复杂表达式占位符，例如：
     * <ul>
     *   <li>{@code #FLOOR(EXTRACT(EPOCH FROM NOW()) * 1000000)#} — 嵌套函数 + 算术</li>
     *   <li>{@code #FUNC(a, b)#} — 多参函数</li>
     *   <li>{@code #col + 1#} — 算术表达式</li>
     * </ul>
     * 否则这类占位符不会被替换成 {@code ?}，残留进 Druid 会直接解析失败。
     *
     * <p>使用 reluctant {@code [^#\n]*?} 而非 {@code .*?} 避免跨行 / 跨多个占位符吞匹配。
     */
    private static final Pattern SUNLINE_HASH_PLACEHOLDER = Pattern.compile("#([^#\\n]*?)#");
    private static final Pattern DOLLAR_BRACE_PLACEHOLDER = Pattern.compile("\\$\\{[^}]+\\}");
    /**
     * Spring NamedParameterJdbcTemplate 形式的 {@code :paramName}；
     * 但要避开 PG 的 {@code ::type} 强制类型转换（前导 {@code :} 后紧跟另一个 {@code :}）。
     */
    private static final Pattern COLON_NAMED_PLACEHOLDER  =
            Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)(?![\\w:])");

    /**
     * 兼容老签名：只返回「表名 → 空 PredicateExtract」Map，调用方只关心涉及哪些表时使用。
     */
    public Map<String, PredicateExtract> extract(String sql) {
        return analyze(sql).predicatesByTable;
    }

    /**
     * 分析 SQL：类型用 {@link SqlKindDetector} 判，表名用 Druid 尽力抽。
     *
     * <p>Druid 解析失败不影响结果（type 已由正则判出），仅表名 Map 为空——
     * 上层（表元数据 / EXPLAIN）对空表名 Map 有完整兜底，不会因此中断。
     *
     * @param sql 原始 SQL
     * @return {@link Result}：{@code kind} 来自 {@link SqlKindDetector}，
     *         {@code predicatesByTable} 的 key 是抽到的小写表名
     */
    public Result analyze(String sql) {
        // 类型判定：不依赖 Druid，直接用首关键字正则
        SqlKind kind = SqlKindDetector.detect(sql);

        Map<String, PredicateExtract> tables = new LinkedHashMap<>();
        if (sql == null || sql.isBlank()) {
            return new Result(kind, tables);
        }

        // 银行 DAO 模板占位符预处理：#xxx# / ${xxx} / :xxx → ?，便于 Druid 解析
        String normalizedSql = normalizePlaceholders(sql);

        try {
            // GaussDB / openGauss 兼容 PG 语法
            List<SQLStatement> statements = SQLUtils.parseStatements(normalizedSql, JdbcConstants.POSTGRESQL);
            if (statements == null || statements.isEmpty()) {
                return new Result(kind, tables);
            }
            SQLStatement stmt = statements.get(0);

            // 用通用 SchemaStatVisitor 只取 getTables()，不再读 conditions/orderBy/groupBy
            SchemaStatVisitor visitor = new SchemaStatVisitor();
            stmt.accept(visitor);
            for (TableStat.Name tableName : visitor.getTables().keySet()) {
                if (tableName == null || tableName.getName() == null) continue;
                String t = stripSchema(tableName.getName()).toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    tables.computeIfAbsent(t, PredicateExtract::new);
                }
            }

            // 兜底：visitor 偶尔抓不全（部分方言 / INSERT 目标表），再从语句 AST 兜一层
            collectTableNamesFallback(stmt, tables);
        } catch (Exception e) {
            // 解析失败不致命：类型已判出，表名缺失由上层兜底
            log.warn("[dii-sqlinspect] Druid 抽表名失败（不影响类型判定 kind={}）：{}", kind, e.getMessage());
        }
        return new Result(kind, tables);
    }

    /**
     * 兜底表名抽取：直接遍历语句 AST 里的表源。
     * 仅在 visitor.getTables() 没抓到任何表时补一层，避免 involved_tables 整列为空。
     */
    private void collectTableNamesFallback(SQLStatement stmt, Map<String, PredicateExtract> out) {
        if (!out.isEmpty()) {
            return;
        }
        try {
            stmt.accept(new com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter() {
                @Override
                public boolean visit(SQLExprTableSource x) {
                    if (x != null && x.getName() != null) {
                        String t = stripSchema(x.getName().getSimpleName()).toLowerCase(Locale.ROOT);
                        if (!t.isEmpty()) {
                            out.computeIfAbsent(t, PredicateExtract::new);
                        }
                    }
                    return true;
                }
            });
        } catch (Exception ignore) {
            /* 兜底失败就算了，表名缺失上层有处理 */
        }
    }

    /**
     * 把银行 DAO 常见的模板占位符统一替换成 JDBC 标准 {@code ?}，便于 Druid 解析 / EXPLAIN。
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
}
