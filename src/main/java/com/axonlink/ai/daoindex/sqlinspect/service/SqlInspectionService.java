package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer;
import com.axonlink.ai.daoindex.sqlinspect.dto.ColumnInfo;
import com.axonlink.ai.daoindex.sqlinspect.dto.ExplainResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexMeta;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import com.axonlink.ai.daoindex.sqlinspect.dto.PredicateExtract;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionRequest;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableRating;
import com.axonlink.ai.daoindex.sqlinspect.explain.ExplainExecutor;
import com.axonlink.ai.daoindex.sqlinspect.explain.RuntimeRatingDeriver;
import com.axonlink.ai.daoindex.sqlinspect.meta.IndexMetaService;
import com.axonlink.ai.daoindex.sqlinspect.metadata.TableMetadataService;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.rule.IndexHitRuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单条 SQL 的索引命中分析门面（Phase 2a.1）。
 *
 * <p>编排顺序：
 * <pre>
 *   1. SqlPredicateAnalyzer → 按表抽取 equality 谓词
 *   2. IndexMetaService     → 查每张表的现有索引（缓存）
 *   3. IndexHitRuleEngine   → 按最左匹配规则为每张表评级
 *   4. 聚合 overallRating（多表取最差档）
 * </pre>
 *
 * <p>Phase 2a.1 不接入 LLM、不落库、不执行 EXPLAIN。
 */
@Service
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class SqlInspectionService {

    private static final Logger log = LoggerFactory.getLogger(SqlInspectionService.class);

    private final SqlPredicateAnalyzer analyzer;
    private final IndexMetaService indexMetaService;
    private final IndexHitRuleEngine ruleEngine;
    private final DiiAnalysisItemDao itemDao;
    private final DaoIndexAnalysisProperties props;
    private final ExplainExecutor explainExecutor;
    private final TableMetadataService tableMetadataService;
    private final RuntimeRatingDeriver runtimeRatingDeriver;
    private final ObjectMapper objectMapper;

    public SqlInspectionService(SqlPredicateAnalyzer analyzer,
                                IndexMetaService indexMetaService,
                                IndexHitRuleEngine ruleEngine,
                                DiiAnalysisItemDao itemDao,
                                DaoIndexAnalysisProperties props,
                                ExplainExecutor explainExecutor,
                                TableMetadataService tableMetadataService,
                                RuntimeRatingDeriver runtimeRatingDeriver,
                                ObjectMapper objectMapper) {
        this.analyzer = analyzer;
        this.indexMetaService = indexMetaService;
        this.ruleEngine = ruleEngine;
        this.itemDao = itemDao;
        this.props = props;
        this.explainExecutor = explainExecutor;
        this.tableMetadataService = tableMetadataService;
        this.runtimeRatingDeriver = runtimeRatingDeriver;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量巡检入口：与 {@link #inspect} 行为一致，但额外接收 {@code taskId} 和 SQL 来源信息，
     * 会一并写入 {@code dii_analysis_item}。
     */
    public SqlInspectionResult inspectForBatch(SqlInspectionRequest request,
                                               long taskId,
                                               com.axonlink.ai.daoindex.sqlinspect.dto.SqlCandidate source) {
        SqlInspectionResult result = inspect(request);
        // 如果走了 5 分钟幂等复用，reusedItemId 已经有值，不重复落库；否则刚插过新记录，补充 task 关联
        if (result.getReusedItemId() == null && result.getItemId() > 0 && taskId > 0 && source != null) {
            try {
                itemDao.linkToTask(result.getItemId(), taskId,
                        source.getProjectName(), source.getClassFqn(),
                        source.getMethodName(), source.getSourceFile());
            } catch (Exception e) {
                log.warn("[dii-sqlinspect] 关联 taskId 失败 itemId={} taskId={}: {}",
                        result.getItemId(), taskId, e.getMessage());
            }
        }
        return result;
    }

    public SqlInspectionResult inspect(SqlInspectionRequest request) {
        if (request == null || request.getSql() == null || request.getSql().isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        long start = System.currentTimeMillis();

        SqlInspectionResult result = new SqlInspectionResult();
        String normalizedSql = request.getSql().trim();
        result.setSql(normalizedSql);
        result.setEnv(request.getEnv());
        result.setSqlHash(sha256Hex(normalizedSql));

        // ── 0. 5 分钟幂等窗口：同 sql_hash + env 最近已分析过，直接复用旧结果 ──
        int reuseMinutes = props.getConcurrentReuseMinutes();
        if (reuseMinutes > 0) {
            Long reusedId = itemDao.findRecentDone(result.getSqlHash(), result.getEnv(), reuseMinutes);
            if (reusedId != null) {
                Map<String, Object> cached = itemDao.loadById(reusedId);
                if (cached != null) {
                    log.info("[dii-sqlinspect] 命中 {} 分钟幂等窗口，复用 itemId={} env={} sqlHash={}",
                            reuseMinutes, reusedId, result.getEnv(), result.getSqlHash());
                    result.setOverallRating(parseRating((String) cached.get("overall_rating")));
                    result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);
                    result.getWarnings().add("命中 " + reuseMinutes + " 分钟幂等窗口，复用 itemId=" + reusedId);
                    // 把关键字段复用出来给调用方看
                    result.setReusedItemId(reusedId);
                    return result;
                }
            }
        }

        // 1. 解析 SQL（含 SQL 类型 + 按表的谓词）
        com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer.Result parsed =
                analyzer.analyze(normalizedSql);
        Map<String, PredicateExtract> predMap = parsed.predicatesByTable;

        if (parsed.kind == com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer.SqlKind.UNKNOWN) {
            result.setOverallRating(IndexRating.POOR);
            result.getWarnings().add("SQL 解析失败或不支持该 SQL 类型");
            result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);
            // 落库让审计链完整：解析失败的记录也要留痕
            persistResult(result, parsed);
            return result;
        }

        // INSERT VALUES 不需要索引评级：不是"差"，是"不适用"；
        // 但仍然要落库留痕，保证 total_sqls 和 item 表行数对得上。
        if (parsed.kind == com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer.SqlKind.INSERT_VALUES) {
            result.setOverallRating(IndexRating.NOT_APPLICABLE);
            result.getWarnings().add("INSERT VALUES 语句无 WHERE 条件，不参与索引评级");
            for (PredicateExtract pe : predMap.values()) {
                TableRating tr = new TableRating();
                tr.setTable(pe.getTableName());
                tr.setRating(IndexRating.NOT_APPLICABLE);
                tr.setReason("INSERT VALUES 不需要索引评级");
                tr.setPredicates(pe);
                result.getTableRatings().add(tr);
            }
            result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);
            log.info("[dii-sqlinspect] INSERT VALUES 落库 env={} sqlHash={}",
                    request.getEnv(), result.getSqlHash());
            persistResult(result, parsed);
            return result;
        }

        // SELECT / UPDATE / DELETE / INSERT_SELECT 都走同一套规则引擎
        IndexRating overall = null;
        for (PredicateExtract pe : predMap.values()) {
            List<IndexMeta> indexes = indexMetaService.getIndexes(request.getEnv(), pe.getTableName());
            TableRating tr = ruleEngine.rateTable(pe, indexes);
            result.getTableRatings().add(tr);
            overall = (overall == null) ? tr.getRating() : IndexRating.worstOf(overall, tr.getRating());
        }

        result.setOverallRating(overall == null ? IndexRating.POOR : overall);
        result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);

        // ── EXPLAIN + 表元数据 + runtime_rating 派生 ──
        // 全过程包在 try-catch，任何失败都不影响规则结果落库
        try {
            enrichWithExplainAndMetadata(result, normalizedSql, request.getEnv(), predMap);
        } catch (Throwable t) {
            log.warn("[dii-sqlinspect] EXPLAIN/metadata 收集失败 sqlHash={}: {}",
                    result.getSqlHash(), t.getMessage());
            result.setExplainError("收集失败：" + t.getMessage());
        }

        persistResult(result, parsed);

        log.info("[dii-sqlinspect] 分析完成 env={} kind={} rating={} tables={} elapsed={}ms itemId={} sqlHash={}",
                request.getEnv(), parsed.kind, result.getOverallRatingLabel(),
                result.getTableRatings().size(), result.getRuleEngineElapsedMs(),
                result.getItemId(), result.getSqlHash());
        return result;
    }

    /**
     * 统一的落库入口：不论是 SELECT / UPDATE / DELETE / INSERT VALUES / UNKNOWN，
     * 只要走到规则引擎就应该在 {@code dii_analysis_item} 表留一条记录，
     * 确保 {@code total_sqls} 和表行数能对得上。
     *
     * <p><b>LLM 触发条件（极简版）</b>：只看 {@code runtime_rating} ∈ {POOR, GOOD}。
     * <ul>
     *   <li>runtime_rating = POOR / GOOD → 标 llm_pending=1，后续异步跑 LLM 解读</li>
     *   <li>runtime_rating = EXCELLENT → 不跑 LLM（执行计划已经够好，无需解读）</li>
     *   <li>runtime_rating = NULL（EXPLAIN 失败 / NOT_APPLICABLE 等）→ 不跑 LLM（没有执行计划，LLM 瞎编没价值）</li>
     * </ul>
     * 不再看 overall_rating、disagreement、explain_error —— 规则简化到单一判据。
     */
    private void persistResult(SqlInspectionResult result,
                               com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer.Result parsed) {
        try {
            DiiAnalysisItemDao.SqlSource source = DiiAnalysisItemDao.SqlSource.ofKind(
                    parsed == null || parsed.kind == null ? "UNKNOWN" : parsed.kind.name());
            long newId = itemDao.insertFromResult(result, source);
            result.setItemId(newId);

            // ── LLM 触发：runtime_rating 是 GOOD 或 POOR ──
            IndexRating rr = result.getRuntimeRating();
            boolean needsLlm = (rr == IndexRating.POOR || rr == IndexRating.GOOD);

            if (newId > 0 && needsLlm) {
                try {
                    itemDao.markLlmPending(newId);
                    log.info("[dii-sqlinspect] 标记 llm_pending itemId={} runtimeRating={}",
                            newId, rr);
                } catch (Exception e) {
                    log.warn("[dii-sqlinspect] 标记 llm_pending 失败 itemId={}: {}", newId, e.getMessage());
                }
            } else if (newId > 0) {
                log.debug("[dii-sqlinspect] 跳过 LLM itemId={} runtimeRating={}（非 GOOD/POOR）",
                        newId, rr);
            }
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 落库失败 env={} sqlHash={}: {}",
                    result.getEnv(), result.getSqlHash(), e.getMessage(), e);
            result.getWarnings().add("落库失败：" + e.getMessage());
        }
    }

    /**
     * 跑 EXPLAIN + 收集涉及表的元数据 + 派生 runtime_rating + 算 disagreement，
     * 把所有结果填到 {@link SqlInspectionResult}，由 persistResult 统一落库。
     *
     * <p>三件事都允许部分失败：EXPLAIN 失败仍可填表元数据，反之亦然。
     */
    private void enrichWithExplainAndMetadata(SqlInspectionResult result,
                                              String normalizedSql,
                                              String env,
                                              Map<String, PredicateExtract> predMap) {
        // 1. 先拿表元数据，顺便拿到 schema，给 EXPLAIN 用
        //    元数据查询失败（表不存在、pg_stats 没数据等）同样不抛异常，仅影响 schemaHint 和 LLM 语料。
        Map<String, TableMetadata> tableMetadataMap = tableMetadataService.getAll(env,
                new java.util.ArrayList<>(predMap.keySet()));

        // 找出 SQL 引用但数据库里不存在的表（业务侧漂移的典型信号）
        java.util.List<String> missingTables = new java.util.ArrayList<>();
        for (String t : predMap.keySet()) {
            if (!tableMetadataMap.containsKey(t.toLowerCase(java.util.Locale.ROOT))) {
                missingTables.add(t);
            }
        }
        if (!missingTables.isEmpty()) {
            result.getWarnings().add("下列表在目标库中查不到（可能表名大小写/schema 问题，或表未部署）：" + missingTables);
            log.info("[dii-sqlinspect] 表不存在但继续流程 sqlHash={} missingTables={}",
                    result.getSqlHash(), missingTables);
        }

        String schemaHint = tableMetadataMap.values().stream()
                .map(TableMetadata::getSchemaName)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);

        // 2. EXPLAIN（带 schema 提示，避开 GaussDB 分布式下 search_path 问题）
        //    ⚠️ 失败不抛异常，也不阻塞后续流程（表元数据、规则结果照常落库）：
        //      - 代码里 SQL 字段不存在 → EXPLAIN 报 "column does not exist"
        //      - 表被删除 / 尚未部署 → EXPLAIN 报 "relation does not exist"
        //      - DB 连接异常 → EXPLAIN 报 timeout 等
        //    上述错误全部记到 explain_error + warnings，DBA 可筛可查，但本条 SQL 的其他分析不丢。
        //
        //    重要：EXPLAIN 只认 JDBC 标准占位符 ?，原始 SQL 里的 #xxx# / ${xxx} / :xxx
        //    要先规范化，否则 GaussDB 会把 #d# 解析成列名 "d#" 并报错。
        String sqlForExplain = com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer
                .normalizePlaceholders(normalizedSql);
        // 把表元数据传给 EXPLAIN，用于按字段类型生成真实字面量替换 ?
        // （绕开 GaussDB 5 不支持 GENERIC_PLAN 的限制）
        ExplainResult explain = explainExecutor.explain(env, sqlForExplain, schemaHint, tableMetadataMap);
        result.setExplainElapsedMs(explain.getElapsedMs());
        if (explain.isSuccess()) {
            result.setExplainTopCost(explain.getTopCost());
            result.setExplainEstRows(explain.getTopPlanRows());
            result.setExplainHasSeqScan(explain.isHasSeqScan());
            try {
                if (explain.getRawPlan() != null) {
                    result.setExplainPlanJson(objectMapper.writeValueAsString(explain.getRawPlan()));
                }
            } catch (Exception e) {
                log.debug("[dii-sqlinspect] EXPLAIN JSON 序列化失败：{}", e.getMessage());
            }
        } else {
            result.setExplainError(explain.getErrorMessage());
            String errMsg = explain.getErrorMessage() == null ? "" : explain.getErrorMessage();
            if (errMsg.startsWith("[SCHEMA_DRIFT]")) {
                // 🚨 表/字段不存在：这是严重数据问题，不只是 EXPLAIN 失败
                result.getWarnings().add("⚠️ SCHEMA 漂移：" + errMsg.substring("[SCHEMA_DRIFT]".length()).trim()
                        + "（代码引用了目标库中不存在的表/字段，必须修正）");
                log.error("[dii-sqlinspect] SCHEMA 漂移 sqlHash={} env={} error={}",
                        result.getSqlHash(), env, errMsg);
            } else {
                // 普通 EXPLAIN 失败
                result.getWarnings().add("EXPLAIN 未成功（规则评级不受影响）：" + errMsg);
                log.info("[dii-sqlinspect] EXPLAIN 失败但继续流程 sqlHash={} env={} reason={}",
                        result.getSqlHash(), env, errMsg);
            }
        }

        // 3. 表元数据转 JSON 落库（喂 LLM 用）
        if (!tableMetadataMap.isEmpty()) {
            result.setTableStatsJson(buildTableStatsJson(tableMetadataMap));
            result.setColumnStatsJson(buildColumnStatsJson(tableMetadataMap));
            result.setTableDdlJson(buildTableDdlJson(tableMetadataMap));
        }

        // 4. runtime_rating 派生 + disagreement 比对
        IndexRating runtime = runtimeRatingDeriver.derive(explain, tableMetadataMap);
        if (runtime != null) {
            result.setRuntimeRating(runtime);
            RuntimeRatingDeriver.Disagreement d = runtimeRatingDeriver.compare(
                    result.getOverallRating(), runtime, explain);
            result.setDisagreement(d.disagree);
            result.setDisagreementReason(d.reason);
        } else if (explain.isSuccess() && explain.isOneTimeFilterFalse()) {
            // EXPLAIN 成功但优化器把 WHERE 判为恒假短路（参数值被当作 NULL 导致）。
            // GaussDB 5 不支持 GENERIC_PLAN EXPLAIN 选项，无法让优化器忽略具体参数值，
            // 这是 GaussDB 版本限制，不是我们代码缺陷。
            result.getWarnings().add("EXPLAIN 计划被优化器按 NULL 参数短路（GaussDB 5 不支持 GENERIC_PLAN），" +
                    "runtime_rating 无法派生，以规则评级为准。");
        }
    }

    /** 表统计信息的紧凑 JSON：{table:{liveTuples,sizeBytes,sizeBucket,lastAnalyze}}。 */
    private String buildTableStatsJson(Map<String, TableMetadata> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, TableMetadata> e : map.entrySet()) {
            TableMetadata md = e.getValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("liveTuples", md.getLiveTuples());
            v.put("sizeBytes", md.getTotalSizeBytes());
            v.put("sizeBucket", md.getSizeBucket());
            v.put("lastAnalyze", md.getLastAnalyzeTime());
            v.put("schema", md.getSchemaName());
            out.put(e.getKey(), v);
        }
        return safeJson(out);
    }

    /** 列分布信息：{table:{col:{distinct,nullFrac,skew}}}。 */
    private String buildColumnStatsJson(Map<String, TableMetadata> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, TableMetadata> e : map.entrySet()) {
            Map<String, Object> cols = new LinkedHashMap<>();
            for (ColumnInfo ci : e.getValue().getColumns()) {
                if (ci.getDistinctCount() == null && ci.getNullFraction() == null) continue;
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("distinct", ci.getDistinctCount());
                v.put("nullFrac", ci.getNullFraction());
                v.put("skew", ci.getSkewLevel());
                cols.put(ci.getName(), v);
            }
            if (!cols.isEmpty()) out.put(e.getKey(), cols);
        }
        return safeJson(out);
    }

    /** 表 DDL：{table:{comment,columns:[{name,type,nullable,comment}]}}。 */
    private String buildTableDdlJson(Map<String, TableMetadata> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, TableMetadata> e : map.entrySet()) {
            TableMetadata md = e.getValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("comment", md.getTableComment());
            java.util.List<Map<String, Object>> colList = new java.util.ArrayList<>();
            for (ColumnInfo ci : md.getColumns()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", ci.getName());
                c.put("type", ci.getDataType());
                c.put("nullable", ci.isNullable());
                if (ci.getComment() != null) c.put("comment", ci.getComment());
                colList.add(c);
            }
            v.put("columns", colList);
            out.put(e.getKey(), v);
        }
        return safeJson(out);
    }

    private String safeJson(Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    /** 把数据库里存的枚举字符串还原成 IndexRating。 */
    private IndexRating parseRating(String name) {
        if (name == null) return IndexRating.POOR;
        try {
            return IndexRating.valueOf(name);
        } catch (IllegalArgumentException e) {
            return IndexRating.POOR;
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
