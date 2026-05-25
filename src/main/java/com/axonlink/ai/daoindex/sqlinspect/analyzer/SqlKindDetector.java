package com.axonlink.ai.daoindex.sqlinspect.analyzer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于「首关键字正则」的 SQL 语句类型判定器。
 *
 * <p>设计动机：旧管线靠 Druid 解析成功后才能判定语句类型，一旦 Druid 解析失败
 * （银行 DAO 模板里常见的复杂占位符 / 方言函数）就退化成 UNKNOWN，导致后续
 * EXPLAIN / LLM 全部跳过。新管线只需要知道「是不是 INSERT」即可决定走不走
 * EXPLAIN，不依赖 Druid 是否能解析整条语句，因此用一个轻量正则即可。
 *
 * <p>判定规则（大小写不敏感）：
 * <ol>
 *   <li>剥掉前导的块注释 {@code /* ... *&#47;} 与行注释 {@code -- ...} 以及空白</li>
 *   <li>取剩余文本的首关键字：
 *     <ul>
 *       <li>{@code INSERT} → 再看语句里有没有独立的 {@code VALUES} 关键字：
 *           有 → {@link SqlPredicateAnalyzer.SqlKind#INSERT_VALUES}；
 *           无（即 INSERT ... SELECT）→ {@link SqlPredicateAnalyzer.SqlKind#INSERT_SELECT}</li>
 *       <li>{@code SELECT} / {@code WITH} → {@link SqlPredicateAnalyzer.SqlKind#SELECT}</li>
 *       <li>{@code UPDATE} → {@link SqlPredicateAnalyzer.SqlKind#UPDATE}</li>
 *       <li>{@code DELETE} → {@link SqlPredicateAnalyzer.SqlKind#DELETE}</li>
 *       <li>其它（含空串 / 无法识别）→ {@link SqlPredicateAnalyzer.SqlKind#UNKNOWN}</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>复用 {@link SqlPredicateAnalyzer.SqlKind} 枚举，不另造类型。
 */
public final class SqlKindDetector {

    /**
     * 前导注释 + 空白剥离用的正则。
     *
     * <p>{@code \A} 锚定字符串开头；交替匹配「块注释」「行注释」「空白」，
     * {@code +} 表示连续多段（例如 SQL 头部有好几行 {@code -- xxx}）。
     * {@code (?s)} 让 {@code .} 跨行匹配，块注释里换行也能吃掉。
     * 块注释用 reluctant {@code .*?} + 显式 {@code \*​/} 收尾，避免吞掉后面真正的 SQL。
     */
    private static final Pattern LEADING_NOISE = Pattern.compile(
            "(?s)\\A(?:/\\*.*?\\*/|--[^\\n]*(?:\\n|$)|\\s)+");

    /**
     * 首关键字正则：在已剥噪的文本开头抓第一个单词。
     *
     * <p>{@code (?i)} 大小写不敏感；{@code \A} 锚定开头；{@code [a-z]+} 抓连续字母，
     * 第一个非字母字符（空格 / 括号 / 引号等）即终止，刚好取到关键字本身。
     */
    private static final Pattern FIRST_KEYWORD = Pattern.compile("(?i)\\A([a-z]+)");

    /**
     * 「是否存在独立 VALUES 关键字」判定。
     *
     * <p>{@code (?i)} 大小写不敏感；{@code \b} 单词边界，确保匹配的是关键字
     * {@code VALUES} 而不是把它当成某个标识符的一部分（如列名 {@code my_values}）。
     */
    private static final Pattern HAS_VALUES = Pattern.compile("(?i)\\bvalues\\b");

    /** 工具类不允许实例化。 */
    private SqlKindDetector() {
    }

    /**
     * 判定 SQL 语句类型。
     *
     * @param sql 原始 SQL（可能含模板占位符 / 注释 / 多余空白；可为 null）
     * @return 与 {@link SqlPredicateAnalyzer.SqlKind} 对应的枚举值；
     *         null / 空串 / 无法识别均返回 {@link SqlPredicateAnalyzer.SqlKind#UNKNOWN}
     */
    public static SqlPredicateAnalyzer.SqlKind detect(String sql) {
        // null / 空白：直接 UNKNOWN（与旧 analyze() 行为一致）
        if (sql == null || sql.isBlank()) {
            return SqlPredicateAnalyzer.SqlKind.UNKNOWN;
        }

        // 1. 去掉前导块注释 / 行注释 / 空白，定位真正的语句首词
        //    Matcher#replaceFirst 只替换字符串开头那一段连续噪声
        String stripped = LEADING_NOISE.matcher(sql).replaceFirst("");

        // 2. 抓首关键字
        Matcher m = FIRST_KEYWORD.matcher(stripped);
        if (!m.find()) {
            // 剥噪后开头不是字母（可能是 '(' 包裹的子查询等），无法用首词判定
            return SqlPredicateAnalyzer.SqlKind.UNKNOWN;
        }
        // group(1) 为关键字，统一转大写做 switch 比较
        String kw = m.group(1).toUpperCase();

        // 3. 按首关键字分流
        switch (kw) {
            case "INSERT":
                // INSERT 再细分：含独立 VALUES → INSERT_VALUES；否则按 INSERT ... SELECT 处理
                return HAS_VALUES.matcher(stripped).find()
                        ? SqlPredicateAnalyzer.SqlKind.INSERT_VALUES
                        : SqlPredicateAnalyzer.SqlKind.INSERT_SELECT;
            case "SELECT":
            case "WITH":
                // 普通查询，以及 CTE（WITH ... SELECT）都按 SELECT 处理
                return SqlPredicateAnalyzer.SqlKind.SELECT;
            case "UPDATE":
                return SqlPredicateAnalyzer.SqlKind.UPDATE;
            case "DELETE":
                return SqlPredicateAnalyzer.SqlKind.DELETE;
            default:
                // MERGE / TRUNCATE / CALL / 未识别 DDL 等：归 UNKNOWN，
                // 由上层决定是否仍尝试 EXPLAIN（新管线对 UNKNOWN 也会跑 EXPLAIN）
                return SqlPredicateAnalyzer.SqlKind.UNKNOWN;
        }
    }
}
