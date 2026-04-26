package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.axonlink.ai.daoindex.sqlinspect.dto.ColumnInfo;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexMeta;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 索引键长度估算器。
 *
 * <p>银行 DBA 规范：<b>联合索引字段声明长度之和不得 > 200</b>。
 * 字符串字段按声明长度 N 计；text/json 等无长度类型视为超大；数值/时间字段按字节权重。
 *
 * <p>提供两层用途：
 * <ul>
 *   <li>分析已有索引：判 {@link #estimate(IndexMeta, TableMetadata)} 是否超限</li>
 *   <li>兜底校验 LLM 推荐的新索引：{@link #estimateFromCreateIndex(String, TableMetadata)}</li>
 * </ul>
 */
public final class IndexSizeEstimator {

    /** 联合索引键长度上限（字符/字节综合权重）。 */
    public static final int LIMIT = 200;

    /** {@code text / json / jsonb / bytea} 等无明确长度的视为此值，触发超限。 */
    private static final int UNBOUNDED_TEXT = 1000;

    /** 从 {@code varchar(30)} / {@code character varying(40)} 里抽出 30/40。 */
    private static final Pattern LENGTH_PATTERN = Pattern.compile("\\(\\s*(\\d+)");

    /** 从 CREATE INDEX DDL 里抽出列列表：{@code CREATE INDEX x ON t(a, b, c)} → {@code a, b, c} */
    private static final Pattern CREATE_IDX_COLS = Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+\\S+\\s+ON\\s+\\S+\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    private IndexSizeEstimator() {}

    /** 估算结果。 */
    public static final class Estimate {
        public final int keyLength;
        public final boolean exceedsLimit;
        /** 人话原因："varchar(500) 字段 rmrk 占 500, 超过 200 限额"。 */
        public final String reason;

        public Estimate(int keyLength, boolean exceedsLimit, String reason) {
            this.keyLength = keyLength;
            this.exceedsLimit = exceedsLimit;
            this.reason = reason;
        }
    }

    /**
     * 估算已有索引的键总长度。
     */
    public static Estimate estimate(IndexMeta idx, TableMetadata md) {
        if (idx == null || idx.getColumns() == null) return new Estimate(0, false, null);
        Map<String, ColumnInfo> colByName = indexByName(md);
        int total = 0;
        StringBuilder breakdown = new StringBuilder();
        for (String colName : idx.getColumns()) {
            int w = lengthOfColumn(colName, colByName);
            total += w;
            if (breakdown.length() > 0) breakdown.append(" + ");
            breakdown.append(colName).append("(").append(w).append(")");
        }
        boolean exceeds = total > LIMIT;
        String reason = exceeds
                ? "索引 " + idx.getIndexName() + " 键长 = " + breakdown + " = " + total + " > " + LIMIT
                : null;
        return new Estimate(total, exceeds, reason);
    }

    /**
     * 从 {@code CREATE INDEX} DDL 估算新索引的键长度。
     * 用于对 LLM 返回的建议 DDL 做兜底校验。
     *
     * @return 估算结果；DDL 格式非法时 {@code keyLength=0,exceedsLimit=false}
     */
    public static Estimate estimateFromCreateIndex(String ddl, TableMetadata md) {
        if (ddl == null || md == null) return new Estimate(0, false, null);
        Matcher m = CREATE_IDX_COLS.matcher(ddl);
        if (!m.find()) return new Estimate(0, false, null);

        String colsPart = m.group(1);
        String[] rawCols = colsPart.split(",");
        Map<String, ColumnInfo> colByName = indexByName(md);
        int total = 0;
        StringBuilder breakdown = new StringBuilder();
        for (String raw : rawCols) {
            // 去掉 DESC / ASC / NULLS FIRST 等后缀，只留列名
            String colName = raw.trim().split("\\s+")[0].replaceAll("[\"`]", "");
            int w = lengthOfColumn(colName, colByName);
            total += w;
            if (breakdown.length() > 0) breakdown.append(" + ");
            breakdown.append(colName).append("(").append(w).append(")");
        }
        boolean exceeds = total > LIMIT;
        String reason = exceeds
                ? "该 DDL 新建索引键长 = " + breakdown + " = " + total + " > " + LIMIT
                : null;
        return new Estimate(total, exceeds, reason);
    }

    /** 找字段定义，找不到按保守值 8 计。 */
    private static int lengthOfColumn(String colName, Map<String, ColumnInfo> colByName) {
        if (colName == null) return 8;
        ColumnInfo ci = colByName.get(colName.toLowerCase(Locale.ROOT));
        if (ci == null || ci.getDataType() == null) return 8;
        return weightOfType(ci.getDataType());
    }

    /**
     * 按 PostgreSQL 风格 {@code format_type} 类型名估算长度。
     */
    static int weightOfType(String dataType) {
        if (dataType == null) return 8;
        String t = dataType.toLowerCase(Locale.ROOT).trim();

        // 带长度的字符串类型：取声明长度 N
        if (t.startsWith("character varying") || t.startsWith("varchar")
                || t.startsWith("character") || t.startsWith("char")) {
            Matcher m = LENGTH_PATTERN.matcher(t);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignore) {}
            }
            // 无长度的 varchar / char → 保守视为超大
            return UNBOUNDED_TEXT;
        }
        // 无明确长度的字符串 / 二进制类型 → 视为超大
        if (t.startsWith("text") || t.startsWith("json") || t.startsWith("jsonb")
                || t.startsWith("bytea") || t.startsWith("xml")) {
            return UNBOUNDED_TEXT;
        }
        // UUID 固定 16 字节
        if (t.startsWith("uuid")) return 16;
        // 8 字节类
        if (t.startsWith("bigint") || t.startsWith("numeric") || t.startsWith("decimal")
                || t.startsWith("double precision") || t.startsWith("timestamp")) return 8;
        // 4 字节类
        if (t.startsWith("integer") || t.startsWith("int4") || t.startsWith("real")
                || t.startsWith("date") || t.startsWith("time")) return 4;
        // 2 字节
        if (t.startsWith("smallint") || t.startsWith("int2")) return 2;
        // 1 字节
        if (t.startsWith("boolean") || t.equals("bool")) return 1;
        // 未知保守
        return 8;
    }

    /** 把 {@link TableMetadata} 的列表 index 成 map，方便按名查。 */
    private static Map<String, ColumnInfo> indexByName(TableMetadata md) {
        Map<String, ColumnInfo> m = new java.util.HashMap<>();
        if (md == null || md.getColumns() == null) return m;
        for (ColumnInfo ci : md.getColumns()) {
            if (ci.getName() != null) m.put(ci.getName().toLowerCase(Locale.ROOT), ci);
        }
        return m;
    }
}
