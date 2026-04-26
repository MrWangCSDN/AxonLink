package com.axonlink.ai.daoindex.sqlinspect.explain;

import com.axonlink.ai.daoindex.sqlinspect.dto.ColumnInfo;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 SQL 上下文推断每个 {@code ?} 占位符对应的字段和类型，
 * 为 EXPLAIN 生成类型安全的字面量替换值。
 *
 * <h3>背景</h3>
 * GaussDB 5 不支持 {@code EXPLAIN (GENERIC_PLAN)} 选项，EXECUTE 时传 NULL 会让
 * {@code WHERE x = NULL} 恒假、优化器短路。把 {@code ?} 替换成<b>字段类型对应的真实字面量</b>
 * 才能让优化器生成有效 plan。
 *
 * <h3>覆盖模式</h3>
 * <ul>
 *   <li>{@code field OP ?} — {@code =}, {@code <>}, {@code !=}, {@code >}, {@code <}, {@code >=}, {@code <=}</li>
 *   <li>{@code field LIKE ?} / {@code field NOT LIKE ?}</li>
 *   <li>{@code field IN (?, ?, ?)} — 多个 {@code ?} 都对应同一字段</li>
 *   <li>{@code field BETWEEN ? AND ?} — 两个 {@code ?} 都对应同一字段</li>
 * </ul>
 *
 * <p>未覆盖的情况（子查询、表达式左值、函数调用），对应 {@code ?} 会标记为未知类型，
 * 调用方退化成 NULL 替换。
 */
public class ParameterTypeResolver {

    private static final Logger log = LoggerFactory.getLogger(ParameterTypeResolver.class);

    /**
     * 每个 {@code ?} 在 SQL 中的位置 + 对应字段名（小写、不带 schema）。
     */
    public static class ParamCtx {
        /** SQL 字符串里 {@code ?} 的字符索引（起始位置）。 */
        public final int index;
        /** 推断出的字段名，null 表示识别失败。 */
        public final String columnName;

        public ParamCtx(int index, String columnName) {
            this.index = index;
            this.columnName = columnName;
        }
    }

    /**
     * 扫描 SQL，返回每个 {@code ?} 的位置 + 对应字段名。
     * 列表按 {@code ?} 在 SQL 中出现的顺序返回，不识别的字段名为 null。
     */
    public static List<ParamCtx> resolve(String sql) {
        List<ParamCtx> out = new ArrayList<>();
        if (sql == null) return out;

        // 先把字符串字面量 / 注释 / 标识符引号里的 ? 遮蔽掉，避免误匹配
        String masked = maskNonCodePositions(sql);

        // 找所有 ? 的位置
        List<Integer> questionPositions = new ArrayList<>();
        for (int i = 0; i < masked.length(); i++) {
            if (masked.charAt(i) == '?') questionPositions.add(i);
        }
        if (questionPositions.isEmpty()) return out;

        // 1. 识别 field OP ? 模式
        //    例：txn_dt = ?, acct_no <> ?, amt >= ?
        Pattern pSimple = Pattern.compile(
                "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(=|<>|!=|>=|<=|>|<|LIKE|NOT\\s+LIKE|ILIKE)\\s*\\?",
                Pattern.CASE_INSENSITIVE);
        // 2. 识别 field IN (?, ?, ...)
        Pattern pIn = Pattern.compile(
                "\\b([A-Za-z_][A-Za-z0-9_]*)\\s+(NOT\\s+)?IN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        // 3. 识别 field BETWEEN ? AND ?
        Pattern pBetween = Pattern.compile(
                "\\b([A-Za-z_][A-Za-z0-9_]*)\\s+(NOT\\s+)?BETWEEN\\s+\\?\\s+AND\\s+\\?",
                Pattern.CASE_INSENSITIVE);

        // posFieldMap: ? 的位置 → 字段名
        java.util.Map<Integer, String> posFieldMap = new java.util.HashMap<>();

        // 匹配 field OP ?（仅记最后一个 ? 在 SQL 中的位置）
        Matcher m = pSimple.matcher(masked);
        while (m.find()) {
            String field = m.group(1).toLowerCase(Locale.ROOT);
            int qPos = masked.indexOf('?', m.start(2));  // 从操作符之后找 ?
            if (qPos >= 0 && qPos < m.end()) {
                posFieldMap.put(qPos, field);
            }
        }

        // 匹配 IN
        Matcher mIn = pIn.matcher(masked);
        while (mIn.find()) {
            String field = mIn.group(1).toLowerCase(Locale.ROOT);
            int parenStart = masked.indexOf('(', mIn.start());
            int parenEnd = mIn.end() - 1;  // ) 位置
            for (int q : questionPositions) {
                if (q > parenStart && q < parenEnd) {
                    posFieldMap.put(q, field);
                }
            }
        }

        // 匹配 BETWEEN
        Matcher mB = pBetween.matcher(masked);
        while (mB.find()) {
            String field = mB.group(1).toLowerCase(Locale.ROOT);
            int segStart = mB.start();
            int segEnd = mB.end();
            for (int q : questionPositions) {
                if (q > segStart && q < segEnd) {
                    posFieldMap.put(q, field);
                }
            }
        }

        // 按 ? 出现顺序组装
        for (int q : questionPositions) {
            out.add(new ParamCtx(q, posFieldMap.get(q)));
        }
        return out;
    }

    /**
     * 按优先级生成每个 {@code ?} 的替换字面量：
     * <ol>
     *   <li><b>真实采样</b>（{@link ColumnSampleService}）：从目标库查一条该字段非 NULL 的真实值。
     *       优化器按真实值生成 plan，能彻底避开 NULL 短路。</li>
     *   <li>字段全 NULL 或采样失败 → 按字段类型用 {@link #literalFor} 生成默认字面量</li>
     *   <li>字段无法识别（SQL 复杂或字段不在元数据里） → {@code NULL}</li>
     * </ol>
     *
     * <p>表 / 字段不存在等"数据层问题"抛 {@link ColumnSampleService.ColumnSampleException}，
     * 由调用方捕获写入 explain_error + warnings。
     *
     * @param paramCtx         resolve() 的输出
     * @param tableMetadataMap 涉及表的元数据（供类型回退用）
     * @param sampleService    可为 null；为 null 时跳过真实采样直接走类型默认
     * @param env              目标库 env（采样用）
     * @param ownerTable       {@code ?} 所在 SQL 涉及的表名（用于采样），一般取 predMap 的首个 key
     */
    public static List<String> toLiterals(List<ParamCtx> paramCtx,
                                          Map<String, TableMetadata> tableMetadataMap,
                                          ColumnSampleService sampleService,
                                          String env,
                                          String ownerTable) {
        List<String> out = new ArrayList<>(paramCtx.size());
        for (ParamCtx ctx : paramCtx) {
            if (ctx.columnName == null) {
                out.add("NULL");
                continue;
            }
            // 先定位字段归属的表
            String tableForColumn = findOwnerTable(ctx.columnName, tableMetadataMap, ownerTable);
            if (tableForColumn == null) {
                out.add("NULL");
                continue;
            }

            // 1. 优先真实采样
            if (sampleService != null) {
                try {
                    String sampled = sampleService.sampleLiteral(env, tableForColumn, ctx.columnName);
                    if (sampled != null) {
                        out.add(sampled);
                        continue;
                    }
                    // sampled == null：字段全 NULL（合法情况），回退类型默认
                } catch (ColumnSampleService.SchemaDriftException e) {
                    // 🚨 表 / 字段不存在：schema 漂移是严重数据错误，必须暴露到上层入库
                    // 不做任何 fallback，直接向上抛，让 ExplainExecutor 捕获后写进 explain_error
                    throw e;
                } catch (ColumnSampleService.ColumnSampleException e) {
                    // 纯技术异常（查询超时、连接失败）：降级用类型默认字面量继续 EXPLAIN
                    log.warn("[dii-sample] 字段采样技术故障 table={} column={}：{}，回退类型默认",
                            tableForColumn, ctx.columnName, e.getMessage());
                }
            }

            // 2. 类型默认字面量
            String dataType = findColumnType(ctx.columnName, tableMetadataMap);
            out.add(dataType == null ? "NULL" : literalFor(dataType));
        }
        return out;
    }

    /**
     * 旧签名保留（无采样服务时用纯类型默认值）。
     * Phase 2b 之后新代码都应该用带 sampleService 的版本。
     */
    public static List<String> toLiterals(List<ParamCtx> paramCtx,
                                          Map<String, TableMetadata> tableMetadataMap) {
        return toLiterals(paramCtx, tableMetadataMap, null, null, null);
    }

    /** 按字段名在元数据里找归属的表；找不到时用 ownerTable 作为兜底。 */
    private static String findOwnerTable(String columnName,
                                          Map<String, TableMetadata> map,
                                          String ownerTable) {
        if (map == null) return ownerTable;
        String lowered = columnName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, TableMetadata> e : map.entrySet()) {
            TableMetadata md = e.getValue();
            if (md == null || md.getColumns() == null) continue;
            for (ColumnInfo ci : md.getColumns()) {
                if (ci.getName() != null && ci.getName().equalsIgnoreCase(lowered)) {
                    return e.getKey();
                }
            }
        }
        return ownerTable;
    }

    /** 按字段名在所有表的 metadata 里查找类型。 */
    private static String findColumnType(String columnName, Map<String, TableMetadata> map) {
        if (map == null) return null;
        String lowered = columnName.toLowerCase(Locale.ROOT);
        for (TableMetadata md : map.values()) {
            if (md == null || md.getColumns() == null) continue;
            for (ColumnInfo ci : md.getColumns()) {
                if (ci.getName() != null && ci.getName().equalsIgnoreCase(lowered)) {
                    return ci.getDataType();
                }
            }
        }
        return null;
    }

    /**
     * 根据 PostgreSQL 风格的类型名（{@code format_type} 输出）返回类型安全字面量。
     *
     * <p>覆盖银行场景高频类型，未覆盖的返回 {@code 'X'}（用户要求的默认字符串）。
     */
    public static String literalFor(String dataType) {
        if (dataType == null) return "'X'";
        String t = dataType.toLowerCase(Locale.ROOT).trim();

        // 整数
        if (t.startsWith("smallint") || t.startsWith("integer") || t.startsWith("int")
                || t.startsWith("bigint") || t.startsWith("serial")) {
            return "1";
        }
        // 小数
        if (t.startsWith("numeric") || t.startsWith("decimal")
                || t.startsWith("real") || t.startsWith("double")) {
            return "1";
        }
        // 布尔
        if (t.startsWith("boolean") || t.equals("bool")) {
            return "true";
        }
        // 时间戳（timestamp / timestamp(0) / timestamp without time zone ...）
        if (t.startsWith("timestamp")) {
            return "CURRENT_TIMESTAMP";
        }
        // 日期
        if (t.startsWith("date")) {
            return "CURRENT_DATE";
        }
        // 时间
        if (t.startsWith("time")) {
            return "CURRENT_TIME";
        }
        // 二进制
        if (t.startsWith("bytea")) {
            return "'\\x00'::bytea";
        }
        // UUID
        if (t.startsWith("uuid")) {
            return "'00000000-0000-0000-0000-000000000000'::uuid";
        }
        // 字符串（character / character varying / text / varchar / char）— 默认兜底
        return "'X'";
    }

    /**
     * 把 SQL 里的 字符串字面量 / 注释 / 双引号标识符 全部用空格替换，避免误匹配 {@code ?}。
     */
    private static String maskNonCodePositions(String sql) {
        if (sql == null) return "";
        char[] chars = sql.toCharArray();
        int n = chars.length;
        int i = 0;
        while (i < n) {
            char c = chars[i];
            if (c == '\'') {
                chars[i] = ' ';
                i++;
                while (i < n) {
                    char ch = chars[i];
                    chars[i] = ' ';
                    if (ch == '\'') {
                        if (i + 1 < n && chars[i + 1] == '\'') {
                            chars[i + 1] = ' '; i += 2;
                        } else { i++; break; }
                    } else i++;
                }
            } else if (c == '\"') {
                chars[i] = ' '; i++;
                while (i < n) {
                    char ch = chars[i]; chars[i] = ' '; i++;
                    if (ch == '\"') break;
                }
            } else if (c == '-' && i + 1 < n && chars[i + 1] == '-') {
                while (i < n && chars[i] != '\n') { chars[i] = ' '; i++; }
            } else if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) { while (i < n) { chars[i] = ' '; i++; } }
                else { for (int k = i; k < end + 2 && k < n; k++) chars[k] = ' '; i = end + 2; }
            } else i++;
        }
        return new String(chars);
    }
}
