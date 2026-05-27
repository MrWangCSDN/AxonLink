package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlKindDetector;
import com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer;
import com.axonlink.ai.daoindex.sqlinspect.dto.ColumnInfo;
import com.axonlink.ai.daoindex.sqlinspect.dto.ExplainResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import com.axonlink.ai.daoindex.sqlinspect.dto.PredicateExtract;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionRequest;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableRating;
import com.axonlink.ai.daoindex.sqlinspect.explain.ExplainExecutor;
import com.axonlink.ai.daoindex.sqlinspect.explain.RuntimeRatingDeriver;
import com.axonlink.ai.daoindex.sqlinspect.metadata.TableMetadataService;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条 SQL 巡检门面（DII，「EXPLAIN 优先」管线）。
 *
 * <h3>新管线编排</h3>
 * <pre>
 *   1. 5min 幂等窗口命中 → 复用旧结果（不变）
 *   2. SqlKindDetector 首关键字判类型（不依赖 Druid 解析成功）
 *        ├─ INSERT(VALUES / SELECT) → overall_rating=NOT_APPLICABLE，落库返回
 *        │                            （不 EXPLAIN、不 LLM）
 *        └─ 其它（SELECT/UPDATE/DELETE/WITH/UNKNOWN…）→ 无条件跑 EXPLAIN
 *              ├─ EXPLAIN 失败 → explain_error；overall_rating = null（看板靠
 *              │                  explain_error 归"报错"档）；不 LLM
 *              └─ EXPLAIN 成功 → 由执行计划派生 overall_rating（是否需整改）：
 *                    - 全表扫描 / 命中索引但估算扫描行数≥1000 → POOR（需整改候选）
 *                      → 标 llm_pending（送 LLM）
 *                    - 命中索引且估算扫描行数&lt;1000 → EXCELLENT（无需整改）→ 不 LLM
 * </pre>
 *
 * <p><b>LLM 触发判据（v3）</b>：{@code overall_rating = POOR}（需整改候选：全表扫描
 * 或命中索引但估算扫描行数≥1000 才送 LLM 解读），不再用 {@code explain_has_seq_scan=1}
 * （命中索引≥1000 行没有 Seq Scan 也要送）。
 *
 * <p>规则引擎（最左匹配评级）已整体下线；表名抽取仍复用
 * {@link SqlPredicateAnalyzer}（仅取表名，不取谓词）供表元数据采集 / EXPLAIN 用。
 */
@Service
public class SqlInspectionService {

    private static final Logger log = LoggerFactory.getLogger(SqlInspectionService.class);

    private final SqlPredicateAnalyzer analyzer;
    private final DiiAnalysisItemDao itemDao;
    private final DaoIndexAnalysisProperties props;
    private final ExplainExecutor explainExecutor;
    private final TableMetadataService tableMetadataService;
    private final RuntimeRatingDeriver runtimeRatingDeriver;
    private final ObjectMapper objectMapper;
    // V16：白名单工作流——insert 后反查匹配 sql_hash 的 application 继承 wl 状态
    // 用 ObjectProvider 防循环依赖（Service 直接互相 @Autowired 在某些 Spring 启动阶段可能死锁）
    private final org.springframework.beans.factory.ObjectProvider<WhitelistApplicationService> whitelistServiceProvider;

    public SqlInspectionService(SqlPredicateAnalyzer analyzer,
                                DiiAnalysisItemDao itemDao,
                                DaoIndexAnalysisProperties props,
                                ExplainExecutor explainExecutor,
                                TableMetadataService tableMetadataService,
                                RuntimeRatingDeriver runtimeRatingDeriver,
                                ObjectMapper objectMapper,
                                org.springframework.beans.factory.ObjectProvider<WhitelistApplicationService> whitelistServiceProvider) {
        this.analyzer = analyzer;
        this.itemDao = itemDao;
        this.props = props;
        this.explainExecutor = explainExecutor;
        this.tableMetadataService = tableMetadataService;
        this.runtimeRatingDeriver = runtimeRatingDeriver;
        this.objectMapper = objectMapper;
        this.whitelistServiceProvider = whitelistServiceProvider;
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
        return inspect(request, true);
    }

    /**
     * 内部入口：{@code persist=false} 时只跑分析，不查幂等缓存、不写库；调用方自行决定如何持久化。
     * 用于 {@link #reinspect(long, String)} 这种"用同一 itemId UPDATE"的重跑场景。
     * <p>V16+ 起转为 public——{@link com.axonlink.ai.daoindex.sqlinspect.batch.PoolBatchInspector}
     * 也用此入口拿 EXPLAIN 结果但不写 item 表（pool 路径自行写回池表）。
     */
    public SqlInspectionResult inspect(SqlInspectionRequest request, boolean persist) {
        if (request == null || request.getSql() == null || request.getSql().isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        long start = System.currentTimeMillis();

        SqlInspectionResult result = new SqlInspectionResult();
        String normalizedSql = request.getSql().trim();
        result.setSql(normalizedSql);
        result.setEnv(request.getEnv());
        result.setSqlHash(sha256Hex(normalizedSql));

        // ── 0. 5 分钟幂等窗口（仅在 persist 模式下生效，重跑不查缓存） ──
        int reuseMinutes = props.getConcurrentReuseMinutes();
        if (persist && reuseMinutes > 0) {
            Long reusedId = itemDao.findRecentDone(result.getSqlHash(), result.getEnv(), reuseMinutes);
            if (reusedId != null) {
                Map<String, Object> cached = itemDao.loadById(reusedId);
                if (cached != null) {
                    log.info("[dii-sqlinspect] 命中 {} 分钟幂等窗口，复用 itemId={} env={} sqlHash={}",
                            reuseMinutes, reusedId, result.getEnv(), result.getSqlHash());
                    result.setOverallRating(parseRatingNullable((String) cached.get("overall_rating")));
                    result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);
                    result.getWarnings().add("命中 " + reuseMinutes + " 分钟幂等窗口，复用 itemId=" + reusedId);
                    // 把关键字段复用出来给调用方看
                    result.setReusedItemId(reusedId);
                    return result;
                }
            }
        }

        // ── 1. 用首关键字正则判 SQL 类型（不再依赖 Druid 解析成功） ──
        SqlPredicateAnalyzer.SqlKind kind = SqlKindDetector.detect(normalizedSql);

        // ── V16+：白名单短路——APPROVED 终态的 SQL 直接跳过 EXPLAIN + LLM ──
        // 在 INSERT 短路之前判：INSERT + 白名单仍然走白名单短路（语义优先级：白名单 > INSERT 不适用）
        // 不查询 application 表会更便宜（V15 列已冗余）但本路径单条入库无 V15 列可读
        // → 直接查 application（DB 索引 idx_sql_hash 已覆盖，单查询毫秒级）
        try {
            WhitelistApplicationService wlService = whitelistServiceProvider.getIfAvailable();
            if (wlService != null && wlService.isHashApproved(result.getSqlHash())) {
                // 标记为 EXCELLENT「无需整改」语义——白名单意味着 DBA 已确认可接受
                result.setOverallRating(IndexRating.EXCELLENT);
                result.setOverallRatingLabel("无需整改");
                result.getWarnings().add("白名单 SQL：跳过 EXPLAIN + LLM");
                result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);
                log.info("[dii-sqlinspect] 白名单短路 env={} sqlHash={} kind={}",
                        request.getEnv(), result.getSqlHash(), kind);
                if (persist) persistResult(result, kind);
                return result;
            }
        } catch (Exception e) {
            // 白名单查询失败不应阻断主流程——继续按常规走 EXPLAIN
            log.warn("[dii-sqlinspect] 白名单短路查询失败 sqlHash={}: {}",
                    result.getSqlHash(), e.getMessage());
        }

        // ── 2. INSERT（VALUES 或 SELECT）→ 不适用索引评级，落库留痕后返回 ──
        //       不跑 EXPLAIN、不送 LLM；overall_rating = NOT_APPLICABLE
        if (kind == SqlPredicateAnalyzer.SqlKind.INSERT_VALUES
                || kind == SqlPredicateAnalyzer.SqlKind.INSERT_SELECT) {
            result.setOverallRating(IndexRating.NOT_APPLICABLE);
            result.getWarnings().add("INSERT 语句不参与索引评级（不执行 EXPLAIN / LLM）");
            result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);
            log.info("[dii-sqlinspect] INSERT({}) 落库 env={} sqlHash={}",
                    kind, request.getEnv(), result.getSqlHash());
            if (persist) persistResult(result, kind);
            return result;
        }

        // ── 3. 其余一律进 EXPLAIN 主流程（含原 UNKNOWN：尽力 EXPLAIN，失败就记 explain_error） ──
        result.setRuleEngineElapsedMs(System.currentTimeMillis() - start);
        try {
            enrichWithExplainAndMetadata(result, normalizedSql, request.getEnv());
        } catch (Throwable t) {
            log.warn("[dii-sqlinspect] EXPLAIN/metadata 收集失败 sqlHash={}: {}",
                    result.getSqlHash(), t.getMessage());
            result.setExplainError("收集失败：" + t.getMessage());
            // 收集异常视同 EXPLAIN 失败：overall_rating 置 null，看板靠 explain_error 归"报错"档
            result.setOverallRating(null);
        }

        if (persist) persistResult(result, kind);

        log.info("[dii-sqlinspect] 分析完成 env={} kind={} rating={} hasSeqScan={} elapsed={}ms itemId={} sqlHash={}",
                request.getEnv(), kind, result.getOverallRatingLabel(),
                result.getExplainHasSeqScan(), result.getRuleEngineElapsedMs(),
                result.getItemId(), result.getSqlHash());
        return result;
    }

    /**
     * 重跑入口：用新 SQL 重新执行 EXPLAIN，UPDATE 已有 itemId 行。
     * 不走幂等缓存，不新建行；同时把 LLM 字段清空让上层重跑 LLM。
     *
     * @return 跑完的 inspection 结果（itemId 仍为传入的 id）
     */
    public SqlInspectionResult reinspect(long itemId, String newSql) {
        Map<String, Object> existing = itemDao.loadById(itemId);
        if (existing == null) {
            throw new IllegalArgumentException("item 不存在 id=" + itemId);
        }
        String env = (String) existing.get("env");
        SqlInspectionRequest req = new SqlInspectionRequest();
        req.setSql(newSql);
        req.setEnv(env);

        SqlInspectionResult result = inspect(req, false);
        itemDao.updateInspectionFields(itemId, result);
        result.setItemId(itemId);
        log.info("[dii-sqlinspect] 重跑 itemId={} env={} 新 sqlHash={} rating={} hasSeqScan={}",
                itemId, env, result.getSqlHash(),
                result.getOverallRatingLabel(), result.getExplainHasSeqScan());
        return result;
    }

    /**
     * 统一落库入口：不论 SELECT / UPDATE / DELETE / INSERT / UNKNOWN，都在
     * {@code dii_analysis_item} 留一条记录，确保 {@code total_sqls} 和表行数对得上。
     *
     * <p><b>LLM 触发条件（v3 口径）</b>：{@code overall_rating = POOR}（需整改候选：
     * 全表扫描 或 命中索引但 EXPLAIN 估算扫描行数≥1000）才送 LLM。
     * <ul>
     *   <li>需整改候选（overall_rating=POOR）→ 标 llm_pending=1，后续异步跑 LLM 解读；</li>
     *   <li>无需整改（EXCELLENT）→ 不跑 LLM（执行计划没有显著问题）；</li>
     *   <li>EXPLAIN 失败（overall_rating=null）/ NOT_APPLICABLE → 不跑 LLM。</li>
     * </ul>
     * 与 {@link DiiAnalysisItemDao#findPendingLlmIds} 的 {@code overall_rating='POOR'}
     * 过滤口径、{@code SqlLlmAnalyzeService} 兜底守卫口径三处字面保持一致。
     */
    private void persistResult(SqlInspectionResult result,
                               SqlPredicateAnalyzer.SqlKind kind) {
        try {
            DiiAnalysisItemDao.SqlSource source = DiiAnalysisItemDao.SqlSource.ofKind(
                    kind == null ? "UNKNOWN" : kind.name());
            long newId = itemDao.insertFromResult(result, source);
            result.setItemId(newId);

            // V16：刚入库即反查匹配 sql_hash 的白名单 application，继承 wl 字段
            // 失败不阻断主流程——审批工作流是辅助能力，挂掉只是按钮少了状态显示
            try {
                WhitelistApplicationService whitelistService = whitelistServiceProvider.getIfAvailable();
                if (whitelistService != null && newId > 0) {
                    whitelistService.inheritOnItemInsert(newId, result.getSqlHash());
                }
            } catch (Exception e) {
                log.warn("[dii-sqlinspect] 继承白名单失败 itemId={} hash={}: {}",
                        newId, result.getSqlHash(), e.getMessage());
            }

            // ── LLM 触发：仅"需整改候选"(overall_rating=POOR) 才送 ──
            //    与 DAO findPendingLlmIds 的 overall_rating='POOR' 过滤、
            //    SqlLlmAnalyzeService 兜底守卫三处字面一致。
            //    注意：命中索引但估算扫描行数≥1000 时 hasSeqScan=false 但 rating=POOR，
            //    必须按 rating 判，不能再用 hasSeqScan（否则会漏送）。
            boolean needFix = result.getOverallRating() == IndexRating.POOR;
            if (newId > 0 && needFix) {
                try {
                    itemDao.markLlmPending(newId);
                    log.info("[dii-sqlinspect] 标记 llm_pending itemId={} overall_rating=POOR hasSeqScan={}",
                            newId, result.getExplainHasSeqScan());
                } catch (Exception e) {
                    log.warn("[dii-sqlinspect] 标记 llm_pending 失败 itemId={}: {}", newId, e.getMessage());
                }
            } else if (newId > 0) {
                log.debug("[dii-sqlinspect] 跳过 LLM itemId={} rating={}（非需整改候选）hasSeqScan={}",
                        newId, result.getOverallRatingLabel(), result.getExplainHasSeqScan());
            }
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 落库失败 env={} sqlHash={}: {}",
                    result.getEnv(), result.getSqlHash(), e.getMessage(), e);
            result.getWarnings().add("落库失败：" + e.getMessage());
        }
    }

    /**
     * 跑 EXPLAIN + 收集涉及表的元数据，并由执行计划派生 {@code overall_rating}。
     *
     * <p>表元数据 / EXPLAIN 各自允许失败：EXPLAIN 失败仍可填表元数据，反之亦然。
     * 但 {@code overall_rating} 完全由 EXPLAIN 结果决定：
     * EXPLAIN 失败 → null（看板靠 explain_error 归"报错"档）；
     * 成功 → 全表扫描 / 命中索引但估算扫描行数≥1000 = POOR（需整改候选），
     * 否则 = EXCELLENT（无需整改）。
     */
    private void enrichWithExplainAndMetadata(SqlInspectionResult result,
                                              String normalizedSql,
                                              String env) {
        // 1. 尽力抽涉及表名（仅表名，不抽谓词），供表元数据采集 + EXPLAIN schemaHint
        Map<String, PredicateExtract> tableMap = analyzer.analyze(normalizedSql).predicatesByTable;
        // involved_tables 列仍需要：LLM 上下文 / 问题列表过滤 / 同表访问模式都依赖它
        result.setInvolvedTables(new java.util.ArrayList<>(tableMap.keySet()));

        // 2. 表元数据（失败不抛，仅影响 schemaHint 和 LLM 语料）
        Map<String, TableMetadata> tableMetadataMap = tableMetadataService.getAll(env,
                new java.util.ArrayList<>(tableMap.keySet()));

        // 找出 SQL 引用但数据库里不存在的表（业务侧漂移的典型信号）
        java.util.List<String> missingTables = new java.util.ArrayList<>();
        for (String t : tableMap.keySet()) {
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

        // 3. EXPLAIN（带 schema 提示，避开 GaussDB 分布式下 search_path 问题）
        //    ⚠️ 失败不抛异常：错误记到 explain_error + warnings，DBA 可筛可查。
        //    占位符必须先规范化成 ?（否则 GaussDB 会把 #d# 当列名报错）。
        String sqlForExplain = SqlPredicateAnalyzer.normalizePlaceholders(normalizedSql);
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
                result.getWarnings().add("EXPLAIN 未成功：" + errMsg);
                log.info("[dii-sqlinspect] EXPLAIN 失败 sqlHash={} env={} reason={}",
                        result.getSqlHash(), env, errMsg);
            }
        }

        // 4. 表元数据转 JSON 落库（喂 LLM 用）
        if (!tableMetadataMap.isEmpty()) {
            result.setTableStatsJson(buildTableStatsJson(tableMetadataMap));
            result.setColumnStatsJson(buildColumnStatsJson(tableMetadataMap));
            result.setTableDdlJson(buildTableDdlJson(tableMetadataMap));
        }

        // 5. 由执行计划派生 overall_rating（v3：全表扫描 / 命中索引但估算行数≥1000 → POOR）
        //    EXPLAIN 失败 → derive 返回 null → overall_rating=null，看板靠 explain_error 归档
        IndexRating rating = runtimeRatingDeriver.derive(explain, tableMetadataMap);
        result.setOverallRating(rating);
        if (rating == null && explain.isSuccess() && explain.isOneTimeFilterFalse()) {
            // EXPLAIN 成功但优化器把 WHERE 判为恒假短路（参数被当作 NULL）。
            // GaussDB 5 不支持 GENERIC_PLAN，无法忽略具体参数值——版本限制，非代码缺陷。
            result.getWarnings().add("EXPLAIN 计划被优化器按 NULL 参数短路（GaussDB 5 不支持 GENERIC_PLAN），" +
                    "overall_rating 无法派生。");
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

    /**
     * 把数据库里存的 overall_rating 字符串还原成 {@link IndexRating}。
     * 新管线里 overall_rating 可能为 null（EXPLAIN 失败），故还原失败/为空时返回 null，
     * 不再像旧逻辑那样兜底成 POOR（会污染看板报错档）。
     */
    private IndexRating parseRatingNullable(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return IndexRating.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
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
