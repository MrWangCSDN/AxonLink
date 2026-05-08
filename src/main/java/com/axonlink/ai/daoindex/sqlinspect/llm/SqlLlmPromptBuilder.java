package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.axonlink.ai.daoindex.sqlinspect.dto.*;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 SQL 所有语料组装成喂给 LLM 的 Prompt。
 *
 * <h3>Prompt 结构</h3>
 * <pre>
 * System Prompt:
 *   角色设定 + 5 视角硬性规则 + 输出 JSON Schema + 硬约束
 *
 * User Prompt:
 *   structured context JSON
 * </pre>
 *
 * <h3>5 视角</h3>
 * <ol>
 *   <li>索引优化：加索引</li>
 *   <li>索引合并：多个索引列序可合并</li>
 *   <li>重复索引：完全冗余</li>
 *   <li>索引键总长 ≤ 200</li>
 *   <li>索引数量控制：≤5 可加、6~10 慎加、&gt;10 禁加</li>
 * </ol>
 */
@Component
public class SqlLlmPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(SqlLlmPromptBuilder.class);

    /** Prompt 模板版本号，便于追溯。改 prompt 内容后必须升版本。 */
    public static final String PROMPT_VERSION = "sql-v5";

    private final ObjectMapper objectMapper;

    public SqlLlmPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 输入语料 bundle。 */
    public static class Context {
        public SqlInspectionResult inspectionResult;
        public Map<String, TableMetadata> tableMetadataMap;
        public Map<String, SameTableAccessPattern> sameTablePatterns;
    }

    /** 构造完整 Prompt。 */
    public AnalysisPrompt build(Context ctx) {
        AnalysisPrompt p = new AnalysisPrompt();
        p.setPromptVersion(PROMPT_VERSION);
        p.setSystemPrompt(SYSTEM_PROMPT);
        p.setUserPrompt(buildUserPrompt(ctx));
        return p;
    }

    /** System prompt —— 固定内容，不随每次分析变化。 */
    private static final String SYSTEM_PROMPT =
            "你是 GaussDB 数据库索引优化专家，负责对银行 DAO 层 SQL 做索引合规与性能 review。\n" +
            "\n" +
            "下面会给你一条 SQL 的完整语料（规则引擎结论 + EXPLAIN 真实计划 + 表元数据 + 同表其他 SQL 访问模式）。\n" +
            "你必须从以下 5 个视角综合分析，每个视角缺一不可：\n" +
            "\n" +
            "【视角 1：索引是否覆盖】\n" +
            "  - WHERE / JOIN / ORDER BY / GROUP BY 字段是否被某个现有索引最左前缀覆盖？\n" +
            "  - 未覆盖时，建议加什么字段的索引？字段顺序如何排（高基数字段放前）？\n" +
            "  - 只要 matchedIndex.matchedColumnCount < matchedIndex.totalColumnCount，\n" +
            "    必须输出一条 INDEX_NOT_FULLY_COVERED finding（severity 根据 unused 列数的比例给），\n" +
            "    让 DBA 知道为什么评级不是'优'。\n" +
            "\n" +
            "【视角 2：索引合并】\n" +
            "  - 表上的多个索引列序是否可以合并？比如 idx_a(a) 可以被 idx_ab(a,b) 完全替代。\n" +
            "  - 合并时，建议的新索引要同时满足其他同表 SQL 的访问模式（看 sameTableAccessPattern）。\n" +
            "\n" +
            "【视角 3：重复索引】\n" +
            "  - 是否有两个索引列序完全相同，或前缀重叠度 >80%？建议删除冗余。\n" +
            "\n" +
            "【视角 4：索引键总长限制 ≤ 200】\n" +
            "  - 每个索引的 keyLength 字段已经算好了，exceedsLengthLimit=true 表示超 200 限额。\n" +
            "  - 你推荐的新索引，字段之和也应该 ≤ 200（系统会兜底校验并回写 riskWarning，但你最好自己先估算避免给出超限建议）。\n" +
            "\n" +
            "【视角 5：索引数量控制】\n" +
            "  - 表上已有索引总数（tableContext.allIndexes.length）：\n" +
            "    * ≤ 5 ：可以建议新建索引\n" +
            "    * 6 ~ 10：优先建议合并/删除冗余，谨慎新建\n" +
            "    * > 10：禁止新建新索引，必须先减索引\n" +
            "\n" +
            "【硬性约束】\n" +
            "1. 只用语料里的字段名和索引名，绝不编造。\n" +
            "2. 建议区分 scope：\n" +
            "   - scope=TABLE：DDL 动作（CREATE_INDEX / DROP_INDEX / MERGE_INDEX），此建议同时影响同表其他 SQL。\n" +
            "   - scope=SQL：仅本 SQL 相关的修改（REWRITE_SQL / CODE_LEVEL / NO_ACTION）。\n" +
            "3. DDL 建议尽量能覆盖同表多条 SQL 的访问模式（看 sameTableAccessPattern.byPredicate）。\n" +
            "4. **findings 数组必须至少包含 1 条**，空数组是不可接受的。即使评级已是 优，也要说明：\n" +
            "   - matchedColumnCount < totalColumnCount → 输出 INDEX_NOT_FULLY_COVERED（severity 按未用列比例：>=50% → MEDIUM，否则 LOW）；\n" +
            "   - 评级为 优 且索引完全匹配 → 输出一条 severity=LOW 的 finding（type 可为 OTHER），\n" +
            "     description 写\"当前执行计划走 idx_xxx，利用率 x/x，建议保持\"，让 DBA 读得出结论；\n" +
            "   - 有 Seq Scan / 隐式类型转换 / 大表 / 估算行数偏高 等，都要作为 finding 单独列出。\n" +
            "5. suggestions：\n" +
            "   - 如果评级已是 优 且无同表其他 POOR 的 SQL，suggestions 可以只输出一条 NO_ACTION（scope=SQL）；\n" +
            "   - 但 findings 仍然不能为空（见规则 4）。\n" +
            "6. 如果语料不够确定判断，把 confidence 设成 LOW 而不是编造。\n" +
            "7. **隐式类型转换（implicit cast）相关一律不要分析、不要输出 finding、不要输出 suggestion**：\n" +
            "   - 即使在 explain_plan 的 Filter 字段里看到 col::text = 'xxx'::text 这类强制转换，也要忽略。\n" +
            "   - 不要输出 type=IMPLICIT_CAST 的 finding，也不要输出 type=FIX_IMPLICIT_CAST 的 suggestion。\n" +
            "   - 原因：GaussDB 在大多数情况下 cast 不会真的让索引失效，规则引擎已专门处理；LLM 在此层判断会大量误报。\n" +
            "\n" +
            "【输出长度硬约束（为节省 token 和响应时间，严格控制）】\n" +
            "- summary ≤ 30 字\n" +
            "- findings 最多 3 条，每条 description ≤ 60 字，evidence ≤ 40 字\n" +
            "- suggestions 最多 2 条，每条 reason ≤ 60 字，ddl 必须是单行标准 SQL\n" +
            "- 不要输出 markdown 代码块（```json），不要输出任何解释性前言或结语\n" +
            "- 不要输出 <think> 思考块或任何推理过程，直接给结论\n" +
            "- 直接以 { 开头，} 结尾，输出纯 JSON，第一个字符必须是 {\n" +
            "\n" +
            "【输出 JSON Schema（严格遵守，不要加 markdown 代码块）】\n" +
            "{\n" +
            "  \"summary\": \"一句话总结，30 字以内\",\n" +
            "  \"findings\": [\n" +
            "    {\"type\": \"INDEX_NOT_FULLY_COVERED|POTENTIAL_INDEX_MERGE|REDUNDANT_INDEX|OVERSIZED_INDEX_KEY|INDEX_COUNT_WARNING|MISSING_HOT_PATH|LOW_SELECTIVITY|OTHER\",\n" +
            "     \"severity\": \"HIGH|MEDIUM|LOW\",\n" +
            "     \"description\": \"人话描述 30~80 字\",\n" +
            "     \"evidence\": \"指向语料里的字段，如 matchedIndex.matchedColumnCount=1/4\"}\n" +
            "  ],\n" +
            "  \"suggestions\": [\n" +
            "    {\"scope\": \"TABLE\",\n" +
            "     \"type\": \"CREATE_INDEX|DROP_INDEX|MERGE_INDEX|ALTER_INDEX_FIELD_ORDER\",\n" +
            "     \"priority\": 1,\n" +
            "     \"ddl\": \"CREATE INDEX idx_xxx ON t (a, b)\",\n" +
            "     \"reason\": \"理由\"},\n" +
            "    {\"scope\": \"SQL\",\n" +
            "     \"type\": \"REWRITE_SQL|CODE_LEVEL|NO_ACTION\",\n" +
            "     \"priority\": 2,\n" +
            "     \"newSql\": \"若是 REWRITE_SQL 才填\",\n" +
            "     \"reason\": \"理由\"}\n" +
            "  ],\n" +
            "  \"confidence\": \"HIGH|MEDIUM|LOW\"\n" +
            "}\n";

    /** User prompt：把当前 SQL 的所有语料打成一个 JSON。 */
    private String buildUserPrompt(Context ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();

        SqlInspectionResult ir = ctx.inspectionResult;
        if (ir != null) {
            payload.put("sql", mapOf(
                    "text", ir.getSql(),
                    "env", ir.getEnv()
            ));
            payload.put("ruleEngineResult", mapOf(
                    "overallRating", ir.getOverallRating() == null ? null : ir.getOverallRating().name(),
                    "tables", tableRatingsBrief(ir.getTableRatings())
            ));
            payload.put("explainResult", mapOf(
                    "runtimeRating", ir.getRuntimeRating() == null ? null : ir.getRuntimeRating().name(),
                    "topCost", ir.getExplainTopCost(),
                    "estRows", ir.getExplainEstRows(),
                    "hasSeqScan", ir.getExplainHasSeqScan(),
                    "error", ir.getExplainError(),
                    // rawPlanExcerpt 从 2000 砍到 500：
                    //   - 原始 JSON plan 里 Startup Cost / Plan Rows / Plan Width 等噪声 LLM 用不上
                    //   - 我们已经把关键指标派生成 topCost / estRows / hasSeqScan 单独字段了
                    //   - 砍到 500 char 约省 400 input tokens，aihub 处理时间 ↓ 10~15%
                    "rawPlanExcerpt", truncate(ir.getExplainPlanJson(), 500)
            ));
            payload.put("disagreement", mapOf(
                    "isDisagree", ir.isDisagreement(),
                    "reason", ir.getDisagreementReason()
            ));
        }

        // 每张涉及表的完整上下文（索引 + 基础画像 + 同表访问模式）
        List<Map<String, Object>> tablesInfo = new ArrayList<>();
        if (ctx.tableMetadataMap != null) {
            for (Map.Entry<String, TableMetadata> e : ctx.tableMetadataMap.entrySet()) {
                String tableKey = e.getKey();
                TableMetadata md = e.getValue();
                if (md == null) continue;
                tablesInfo.add(buildTableContextEntry(tableKey, md,
                        ctx.sameTablePatterns == null ? null : ctx.sameTablePatterns.get(tableKey),
                        ir));
            }
        }
        payload.put("tables", tablesInfo);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[dii-llm-prompt] 序列化 user prompt 失败：{}", e.getMessage());
            return "{}";
        }
    }

    /** 构造单张表的完整上下文，含索引清单（已标注 keyLength + exceedsLengthLimit）。 */
    private Map<String, Object> buildTableContextEntry(String tableKey, TableMetadata md,
                                                       SameTableAccessPattern pattern,
                                                       SqlInspectionResult ir) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", md.getTableName());
        t.put("schema", md.getSchemaName());
        t.put("comment", md.getTableComment());
        t.put("liveTuples", md.getLiveTuples());
        t.put("sizeBucket", md.getSizeBucket());
        t.put("indexCount", md.getColumns() == null ? 0 : extractIndexCount(ir, tableKey));

        // 涉及的字段 DDL（只带 SQL 用到的字段，节省 token）
        t.put("relevantColumns", relevantColumns(md, ir, tableKey));

        // 表上所有索引，每个带 keyLength / exceedsLengthLimit
        t.put("allIndexes", allIndexesWithKeyLength(md, ir, tableKey));

        // 同表其他 SQL 的访问模式
        if (pattern != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("totalSqlCount", pattern.getTotalSqlCount());
            p.put("ratingCounts", pattern.getRatingCounts());
            List<Map<String, Object>> buckets = new ArrayList<>();
            for (SameTableAccessPattern.PredicateBucket b : pattern.getByPredicate()) {
                buckets.add(mapOf("fields", b.fields, "sqlCount", b.sqlCount, "ratingDist", b.ratingDist));
            }
            p.put("topPredicateBuckets", buckets);
            t.put("sameTableAccessPattern", p);
        }
        return t;
    }

    /** 从 ir 的 tableRatings 里找出该表的所有索引并标注 keyLength。 */
    private List<Map<String, Object>> allIndexesWithKeyLength(TableMetadata md,
                                                              SqlInspectionResult ir, String tableKey) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (ir == null || ir.getTableRatings() == null) return list;
        for (TableRating tr : ir.getTableRatings()) {
            if (tr == null || !tableKey.equalsIgnoreCase(tr.getTable())) continue;
            if (tr.getAvailableIndexes() == null) continue;
            for (IndexMeta idx : tr.getAvailableIndexes()) {
                IndexSizeEstimator.Estimate est = IndexSizeEstimator.estimate(idx, md);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", idx.getIndexName());
                m.put("columns", idx.getColumns());
                m.put("unique", idx.isUnique());
                m.put("primary", idx.isPrimary());
                m.put("keyLength", est.keyLength);
                m.put("exceedsLengthLimit", est.exceedsLimit);
                m.put("limit", IndexSizeEstimator.LIMIT);
                list.add(m);
            }
        }
        return list;
    }

    /** 从 ir 里拿出指定表的索引总数（放在 tableContext 根上，方便 LLM 按阈值判断数量控制）。 */
    private int extractIndexCount(SqlInspectionResult ir, String tableKey) {
        if (ir == null || ir.getTableRatings() == null) return 0;
        for (TableRating tr : ir.getTableRatings()) {
            if (tr != null && tableKey.equalsIgnoreCase(tr.getTable())
                    && tr.getAvailableIndexes() != null) {
                return tr.getAvailableIndexes().size();
            }
        }
        return 0;
    }

    /** 只给 SQL 用到的字段（WHERE/ORDER/GROUP/SELECT），节省 token。 */
    private List<Map<String, Object>> relevantColumns(TableMetadata md, SqlInspectionResult ir, String tableKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (md == null || md.getColumns() == null) return out;
        // 找出本 SQL 用到的字段
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        if (ir != null && ir.getTableRatings() != null) {
            for (TableRating tr : ir.getTableRatings()) {
                if (tr == null || !tableKey.equalsIgnoreCase(tr.getTable())) continue;
                PredicateExtract pe = tr.getPredicates();
                if (pe != null) {
                    if (pe.getEqualityColumns() != null) used.addAll(pe.getEqualityColumns());
                    if (pe.getRangeColumns() != null) used.addAll(pe.getRangeColumns());
                    if (pe.getOrderByColumns() != null) used.addAll(pe.getOrderByColumns());
                    if (pe.getGroupByColumns() != null) used.addAll(pe.getGroupByColumns());
                }
            }
        }
        for (ColumnInfo ci : md.getColumns()) {
            if (ci == null || ci.getName() == null) continue;
            if (!used.contains(ci.getName().toLowerCase(Locale.ROOT))) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", ci.getName());
            c.put("type", ci.getDataType());
            c.put("nullable", ci.isNullable());
            c.put("comment", ci.getComment());
            if (ci.getDistinctCount() != null) c.put("distinct", ci.getDistinctCount());
            if (ci.getNullFraction() != null) c.put("nullFrac", ci.getNullFraction());
            if (ci.getSkewLevel() != null) c.put("skewLevel", ci.getSkewLevel());
            out.add(c);
        }
        return out;
    }

    /** 把 ir.tableRatings 转成精简形态喂 LLM。 */
    private List<Map<String, Object>> tableRatingsBrief(List<TableRating> trs) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (trs == null) return list;
        for (TableRating tr : trs) {
            if (tr == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", tr.getTable());
            m.put("rating", tr.getRating() == null ? null : tr.getRating().name());
            m.put("predicates", tr.getPredicates());
            m.put("matchedIndex", tr.getMatchedIndex());
            m.put("reason", tr.getReason());
            list.add(m);
        }
        return list;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...<truncated>";
    }
}
