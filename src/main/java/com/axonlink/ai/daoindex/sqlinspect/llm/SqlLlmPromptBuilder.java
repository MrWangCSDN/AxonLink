package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.axonlink.ai.daoindex.sqlinspect.dto.*;
import com.axonlink.ai.daoindex.sqlinspect.meta.IndexMetaService;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 SQL 所有语料组装成喂给 LLM 的 Prompt（「EXPLAIN 优先」管线版）。
 *
 * <h3>Prompt 结构</h3>
 * <pre>
 * System Prompt: 角色设定 + 5 视角硬性规则 + 输出 JSON Schema + 硬约束
 * User Prompt:   structured context JSON
 * </pre>
 *
 * <h3>语料构成（规则引擎下线后）</h3>
 * <ul>
 *   <li>{@code sql}：SQL 文本 + env</li>
 *   <li>{@code explainResult}：overall_rating（由执行计划派生）+ 真实计划摘要
 *       （topCost / estRows / hasSeqScan / explainError / rawPlanExcerpt）</li>
 *   <li>{@code tables[]}：每张表的画像（liveTuples=数据量 / 列分布 / DDL / 索引清单）。
 *       索引清单不再从 tableRatings 取（规则引擎已下线），改为
 *       {@link IndexMetaService} 按 involved_tables 直查目标库。</li>
 * </ul>
 *
 * <p>已移除：{@code ruleEngineResult} / {@code matchedIndex} / {@code disagreement} 段
 * （随规则引擎下线一并去掉，避免给 LLM 不存在的字段）。
 */
@Component
public class SqlLlmPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(SqlLlmPromptBuilder.class);

    /** Prompt 模板版本号，便于追溯。改 prompt 内容后必须升版本。 */
    public static final String PROMPT_VERSION = "sql-v8";

    private final ObjectMapper objectMapper;
    private final IndexMetaService indexMetaService;

    public SqlLlmPromptBuilder(ObjectMapper objectMapper, IndexMetaService indexMetaService) {
        this.objectMapper = objectMapper;
        this.indexMetaService = indexMetaService;
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
            "下面会给你一条 SQL 的完整语料（EXPLAIN 真实计划 + 表元数据 + 表上现有索引清单 + 同表其他 SQL 访问模式）。\n" +
            "该 SQL 因「全表扫描，或虽命中索引但 EXPLAIN 估算扫描行数 ≥ 1000」被判为需整改候选，故送你复核。\n" +
            "你必须从以下 5 个视角综合分析，每个视角缺一不可：\n" +
            "\n" +
            "【视角 1：索引是否覆盖】\n" +
            "  - 结合 explainResult.rawPlanExcerpt 看 WHERE / JOIN / ORDER BY / GROUP BY 字段是否走了索引。\n" +
            "  - explainResult.hasSeqScan=true（出现全表扫描）时，必须输出一条 finding 指出哪张表全表扫描、\n" +
            "    并给出该加什么字段的索引（高基数字段放最左）。\n" +
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
            "   - 有 Seq Scan / 大表 / 估算行数偏高 等，都要作为 finding 单独列出；\n" +
            "   - 评级为 优（无 Seq Scan、成本低）→ 输出一条 severity=LOW 的 finding（type 可为 OTHER），\n" +
            "     description 写\"执行计划全走索引，成本低，建议保持\"，让 DBA 读得出结论。\n" +
            "5. suggestions：\n" +
            "   - 如果评级已是 优 且无同表其他 POOR 的 SQL，suggestions 可以只输出一条 NO_ACTION（scope=SQL）；\n" +
            "   - 但 findings 仍然不能为空（见规则 4）。\n" +
            "6. 如果语料不够确定判断，把 confidence 设成 LOW 而不是编造。\n" +
            "7. **隐式类型转换（implicit cast）相关一律不要分析、不要输出 finding、不要输出 suggestion**：\n" +
            "   - 即使在 explain_plan 的 Filter 字段里看到 col::text = 'xxx'::text 这类强制转换，也要忽略。\n" +
            "   - 不要输出 type=IMPLICIT_CAST 的 finding，也不要输出 type=FIX_IMPLICIT_CAST 的 suggestion。\n" +
            "   - 原因：GaussDB 在大多数情况下 cast 不会真的让索引失效；LLM 在此层判断会大量误报。\n" +
            "8. **必须输出 fixVerdict（整改判定），二选一，不可缺省**：\n" +
            "   - fixVerdict=NEED_FIX：该 SQL（全表扫描，或命中索引但扫描行数偏大）确有可落地优化\n" +
            "     （加索引 / 改写 SQL）才给；\n" +
            "   - fixVerdict=NO_NEED：经综合分析无需任何整改 / 现状可接受（如小表全表扫描成本很低、\n" +
            "     或加索引收益不抵成本）；此时 suggestions 可以只含一条 type=NO_ACTION(scope=SQL)；\n" +
            "   - 判定要与 suggestions 一致：给了 CREATE_INDEX/MERGE_INDEX/DROP_INDEX/REWRITE_SQL\n" +
            "     等可落地动作 → 必须 NEED_FIX；只有 NO_ACTION → 必须 NO_NEED。\n" +
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
            "     \"evidence\": \"指向语料里的字段，如 explainResult.hasSeqScan=true\"}\n" +
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
            "  \"confidence\": \"HIGH|MEDIUM|LOW\",\n" +
            // NEED_FIX=该需整改候选 SQL 确需整改（有可落地优化）；NO_NEED=经分析无需任何整改/现状可接受
            "  \"fixVerdict\": \"NEED_FIX | NO_NEED\"\n" +
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
            // 规则引擎已下线：只给 EXPLAIN 派生评级 + 真实计划摘要
            payload.put("explainResult", mapOf(
                    "overallRating", ir.getOverallRating() == null ? null : ir.getOverallRating().name(),
                    "topCost", ir.getExplainTopCost(),
                    "estRows", ir.getExplainEstRows(),
                    "hasSeqScan", ir.getExplainHasSeqScan(),
                    "error", ir.getExplainError(),
                    // rawPlanExcerpt 砍到 500 char：关键指标已派生成 topCost/estRows/hasSeqScan
                    "rawPlanExcerpt", truncate(ir.getExplainPlanJson(), 500)
            ));
        }

        // 每张涉及表的完整上下文（索引 + 基础画像 + 同表访问模式）
        String env = ir == null ? null : ir.getEnv();
        List<Map<String, Object>> tablesInfo = new ArrayList<>();
        if (ctx.tableMetadataMap != null) {
            for (Map.Entry<String, TableMetadata> e : ctx.tableMetadataMap.entrySet()) {
                String tableKey = e.getKey();
                TableMetadata md = e.getValue();
                if (md == null) continue;
                tablesInfo.add(buildTableContextEntry(tableKey, md, env,
                        ctx.sameTablePatterns == null ? null : ctx.sameTablePatterns.get(tableKey)));
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
                                                       String env,
                                                       SameTableAccessPattern pattern) {
        // 索引清单：规则引擎下线后改为按 involved_tables 直查目标库（带 5 分钟缓存）
        List<IndexMeta> indexes = queryIndexes(env, tableKey);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", md.getTableName());
        t.put("schema", md.getSchemaName());
        t.put("comment", md.getTableComment());
        t.put("liveTuples", md.getLiveTuples());
        t.put("sizeBucket", md.getSizeBucket());
        t.put("indexCount", indexes.size());

        // 表字段 DDL + 列分布（规则引擎下线后没有"SQL 用到的字段"清单，给全表列让 LLM 自行判断）
        t.put("columns", columnsContext(md));

        // 表上所有索引，每个带 keyLength / exceedsLengthLimit
        t.put("allIndexes", indexesWithKeyLength(indexes, md));

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

    /** 按 (env, table) 查目标库现有索引；查询失败/无索引时返回空列表，不影响 prompt 组装。 */
    private List<IndexMeta> queryIndexes(String env, String tableKey) {
        try {
            List<IndexMeta> idx = indexMetaService.getIndexes(env, tableKey);
            return idx == null ? new ArrayList<>() : idx;
        } catch (Exception e) {
            log.warn("[dii-llm-prompt] 查索引失败 env={} table={}: {}", env, tableKey, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 索引清单 + key length 标注。 */
    private List<Map<String, Object>> indexesWithKeyLength(List<IndexMeta> indexes, TableMetadata md) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (IndexMeta idx : indexes) {
            if (idx == null) continue;
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
        return list;
    }

    /**
     * 表字段上下文（DDL + 列分布）。
     * 规则引擎下线后没有"本 SQL 用到的字段"清单，这里给全表列；
     * 上限 80 列防止超宽表把 prompt 撑爆（银行表通常 < 80 列）。
     */
    private List<Map<String, Object>> columnsContext(TableMetadata md) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (md == null || md.getColumns() == null) return out;
        int max = 80;
        for (ColumnInfo ci : md.getColumns()) {
            if (ci == null || ci.getName() == null) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", ci.getName());
            c.put("type", ci.getDataType());
            c.put("nullable", ci.isNullable());
            c.put("comment", ci.getComment());
            if (ci.getDistinctCount() != null) c.put("distinct", ci.getDistinctCount());
            if (ci.getNullFraction() != null) c.put("nullFrac", ci.getNullFraction());
            if (ci.getSkewLevel() != null) c.put("skewLevel", ci.getSkewLevel());
            out.add(c);
            if (out.size() >= max) break;
        }
        return out;
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
