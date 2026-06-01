package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableRating;
import com.axonlink.ai.daoindex.sqlinspect.metadata.TableMetadataService;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.service.SqlInspectionService;
import org.springframework.beans.factory.annotation.Autowired;
import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.LlmResult;
import com.axonlink.ai.provider.LlmClient;
import com.axonlink.ai.provider.LlmClientRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单条 SQL 的 LLM 分析编排器。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>从 {@code dii_analysis_item} 加载 itemId 对应的记录，重建必要的 {@link SqlInspectionResult}</li>
 *   <li>调 {@link TableMetadataService} 重新拿涉及表的元数据（带缓存）</li>
 *   <li>调 {@link SameTableAccessPatternCollector} 汇总同表其他 SQL 的访问模式</li>
 *   <li>{@link SqlLlmPromptBuilder} 组装 Prompt</li>
 *   <li>{@link LlmClient#analyze} 调 LLM</li>
 *   <li>解析 JSON 返回 → {@link SqlLlmResult}</li>
 *   <li>{@link IndexSizeEstimator} 对 CREATE_INDEX 建议做 key length 兜底校验（加 riskWarning）</li>
 *   <li>落库到 {@code dii_analysis_item.llm_*} 字段</li>
 * </ol>
 *
 * <h3>失败策略</h3>
 * LLM 调用失败或 JSON 解析失败：落 {@code llm_status=FAILED} + {@code llm_error} 便于手动重跑，
 * 不抛异常给上层（避免批量层被单条拖垮）。
 */
@Service
public class SqlLlmAnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(SqlLlmAnalyzeService.class);

    /** 从 CREATE INDEX DDL 里抽表名。 */
    private static final Pattern CREATE_IDX_ON_TABLE = Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+\\S+\\s+ON\\s+(\\S+)",
            Pattern.CASE_INSENSITIVE);

    private final DiiAnalysisItemDao itemDao;
    private final TableMetadataService tableMetadataService;
    private final SameTableAccessPatternCollector patternCollector;
    private final SqlLlmPromptBuilder promptBuilder;
    private final ObjectProvider<LlmClient> llmClientProvider;
    private final LlmClientRouter llmRouter;
    private final ObjectMapper objectMapper;

    /** LLM 调用 / JSON 解析失败时的重试次数（不含首次）。默认 2 = 最多 3 次尝试。 */
    @Value("${dao-index-analysis.llm.retry-attempts:2}")
    private int llmRetryAttempts;
    /**
     * 普通错误（JSON 解析失败、空响应等）的重试退避基数。
     * 线性递增：第 1 次重试等 backoff，第 2 次等 2*backoff…
     * <p>从 500ms 调到 2000ms：500ms 几乎等于"立刻重试"，对真错误（模型抽风）作用不大，
     * 而对偶发瞬时故障也来不及恢复。
     */
    @Value("${dao-index-analysis.llm.retry-backoff-ms:2000}")
    private long llmRetryBackoffMs;
    /**
     * 网关过载（HTTP 502/503/504）专用退避基数，远大于普通退避。
     * <p>过载场景下立刻重试只会加剧雪崩；这里默认 15s × attempt 让服务端先喘口气。
     */
    @Value("${dao-index-analysis.llm.overload-backoff-ms:15000}")
    private long llmOverloadBackoffMs;
    /** 重跑场景下用，懒加载避免与 SqlInspectionService 循环依赖 */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<SqlInspectionService> inspectionServiceProvider;
    /** V16+：池 LLM 分析所需，懒加载避免循环依赖 */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<
            com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao> poolDaoProvider;

    public SqlLlmAnalyzeService(DiiAnalysisItemDao itemDao,
                                TableMetadataService tableMetadataService,
                                SameTableAccessPatternCollector patternCollector,
                                SqlLlmPromptBuilder promptBuilder,
                                ObjectProvider<LlmClient> llmClientProvider,
                                LlmClientRouter llmRouter,
                                ObjectMapper objectMapper) {
        this.itemDao = itemDao;
        this.tableMetadataService = tableMetadataService;
        this.patternCollector = patternCollector;
        this.promptBuilder = promptBuilder;
        this.llmClientProvider = llmClientProvider;
        this.llmRouter = llmRouter;
        this.objectMapper = objectMapper;
    }

    /**
     * 构造完整 prompt 但不调 LLM，用于调试（POST /debug/llm-preview-prompt/{itemId}）。
     */
    public AnalysisPrompt previewPrompt(long itemId) {
        SqlLlmPromptBuilder.Context ctx = loadContext(itemId);
        if (ctx == null) {
            throw new IllegalArgumentException("item 不存在 id=" + itemId);
        }
        return promptBuilder.build(ctx);
    }

    /** 兼容老调用：默认模型，无 SQL 覆写。 */
    public SqlLlmResult analyzeItem(long itemId) {
        return analyzeItem(itemId, null, null);
    }

    /**
     * 异步触发单条 LLM 分析（前端"重新分析"按钮专用）。
     *
     * <p>调用方必须在调本方法之前先调 {@link DiiAnalysisItemDao#forceMarkLlmPending(long)}
     * 把状态打成 PENDING，让前端首次轮询就能看到蒙版稳定显示。
     *
     * <p>本方法 {@code @Async} 立即返回；真正的 LLM 调用在 {@code diiBatchExecutor} 线程池里跑。
     * 内部直接复用同步版的 {@link #analyzeItem}，所有错误处理 / DB 落库都已经在那里完成。
     *
     * <p>⚠️ 必须由其它 bean（如 Controller）调用，不能在本类内部 {@code this.analyzeItemAsync(...)}：
     * Spring AOP 代理对自身调用不生效，会退化为同步执行。
     */
    @Async("diiBatchExecutor")
    public void analyzeItemAsync(long itemId, String overrideSql, String modelKey) {
        try {
            log.info("[dii-llm][itemId={}] ── 异步 LLM 分析任务启动 ──", itemId);
            analyzeItem(itemId, overrideSql, modelKey);
        } catch (Throwable t) {
            // analyzeItem 内部已经把所有失败路径都落库 FAILED 了，这里是防御兜底
            log.error("[dii-llm][itemId={}] ✖ 异步任务未捕获异常：{}",
                    itemId, t.getMessage(), t);
            try {
                itemDao.updateLlmStatusOnly(itemId, "FAILED",
                        "异步任务未捕获异常：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Exception ignore) { /* 落库失败也不影响线程池 */ }
        }
    }

    /**
     * 异步触发池行单条 LLM 分析（前端"重新分析"按钮 nsql 路径）。
     * <p>与 {@link #analyzeItemAsync} 同款语义，只是读写池表而非 item 表。
     * <p>⚠️ 必须由其它 bean 调用，不能 this. 自调用（@Async AOP 失效）。
     */
    @Async("diiBatchExecutor")
    public void analyzePoolRowAsync(long poolId, String modelKey) {
        try {
            log.info("[dii-llm][poolId={}] ── 异步池行 LLM 分析任务启动 ──", poolId);
            analyzePoolRow(poolId, modelKey);
        } catch (Throwable t) {
            log.error("[dii-llm][poolId={}] ✖ 异步任务未捕获异常：{}",
                    poolId, t.getMessage(), t);
            try {
                com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao poolDao =
                        poolDaoProvider.getIfAvailable();
                if (poolDao != null) {
                    poolDao.updateLlmStatusOnly(poolId, "FAILED",
                            "异步任务未捕获异常：" + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            } catch (Exception ignore) {}
        }
    }

    /**
     * 真正调 LLM 分析单条 item。
     * 失败不抛：失败信息记入 {@link SqlLlmResult#getError} 并落库 FAILED，可手动重跑。
     *
     * @param itemId       要分析的 item id
     * @param overrideSql  若非空，构造 prompt 时用该 SQL（前端"重新执行"模态框允许用户改 SQL 再分析）
     * @param modelKey     模型路由 key：{@code glm-4.7} / {@code minimax-2.7}；为空回退到默认 GLM
     */
    public SqlLlmResult analyzeItem(long itemId, String overrideSql, String modelKey) {
        long entryMs = System.currentTimeMillis();
        log.info("[dii-llm][itemId={}] ── 开始 LLM 分析 model={} overrideSql={} ──",
                itemId, modelKey, overrideSql == null ? "no" : "yes(" + overrideSql.length() + " chars)");

        SqlLlmResult result = new SqlLlmResult();

        // ⓪ 用户改了 SQL：先重跑规则引擎 + EXPLAIN，把新结果 UPDATE 进库；
        //    然后用更新后的上下文继续走 LLM 流程，所有结果都落到同一 itemId 行
        if (overrideSql != null && !overrideSql.isBlank()) {
            SqlInspectionService inspectionService = inspectionServiceProvider.getIfAvailable();
            if (inspectionService == null) {
                log.error("[dii-llm][itemId={}] ✖ SqlInspectionService 未装配，无法重跑规则引擎", itemId);
                result.setError("SqlInspectionService 未装配，无法重跑规则引擎");
                itemDao.updateLlmStatusOnly(itemId, "FAILED", result.getError());
                return result;
            }
            try {
                SqlInspectionResult reinspectResult = inspectionService.reinspect(itemId, overrideSql);
                log.info("[dii-llm][itemId={}] ⓪ 重跑 EXPLAIN 完成 rating={} hasSeqScan={}",
                        itemId, reinspectResult.getOverallRatingLabel(),
                        reinspectResult.getExplainHasSeqScan());
            } catch (Throwable t) {
                log.error("[dii-llm][itemId={}] ✖ 重跑规则引擎/EXPLAIN 失败: {}", itemId, t.getMessage(), t);
                result.setError("重跑规则引擎/EXPLAIN 失败：" + t.getMessage());
                itemDao.updateLlmStatusOnly(itemId, "FAILED", result.getError());
                return result;
            }
        }

        // ① 加载上下文（如果走了 ⓪ 重跑，loadContext 读到的是 UPDATE 后的最新行）
        SqlLlmPromptBuilder.Context ctx = loadContext(itemId);
        if (ctx == null) {
            log.warn("[dii-llm][itemId={}] ✖ 上下文加载失败：item 不存在", itemId);
            result.setError("item 不存在：id=" + itemId);
            itemDao.updateLlmStatusOnly(itemId, "FAILED", result.getError());
            return result;
        }

        int tableCount = ctx.tableMetadataMap == null ? 0 : ctx.tableMetadataMap.size();
        int patternCount = ctx.sameTablePatterns == null ? 0 : ctx.sameTablePatterns.size();
        log.info("[dii-llm][itemId={}] ① 上下文加载完成 env={} sqlHash={} 涉及表={} 访问模式={}",
                itemId,
                ctx.inspectionResult == null ? null : ctx.inspectionResult.getEnv(),
                ctx.inspectionResult == null ? null : ctx.inspectionResult.getSqlHash(),
                tableCount, patternCount);

        // ── 兜底：仅"需整改候选"(overall_rating=POOR) 才跑 LLM ──
        //    v3 口径：与 SqlInspectionService 标 llm_pending、DAO findPendingLlmIds 字面一致。
        //    overall_rating 由 reconstructResult 从 DB 列 overall_rating 经 IndexRating.valueOf
        //    回填（见 reconstructResult），故这里能可靠拿到；命中索引但估算行数≥1000 时
        //    hasSeqScan=false 但 rating=POOR，必须按 rating 判，不能再用 hasSeqScan。
        boolean needFix = ctx.inspectionResult != null
                && ctx.inspectionResult.getOverallRating() == IndexRating.POOR;
        if (!needFix) {
            String reason = "overall_rating != POOR（非需整改候选），跳过 LLM";
            log.info("[dii-llm][itemId={}] ✖ {}", itemId, reason);
            result.setError(reason);
            itemDao.updateLlmStatusOnly(itemId, "SKIPPED", reason);
            return result;
        }

        // ② 构造 Prompt
        AnalysisPrompt prompt = promptBuilder.build(ctx);
        result.setPromptVersion(prompt.getPromptVersion());
        int sysLen = prompt.getSystemPrompt() == null ? 0 : prompt.getSystemPrompt().length();
        int userLen = prompt.getUserPrompt() == null ? 0 : prompt.getUserPrompt().length();
        log.info("[dii-llm][itemId={}] ② Prompt 构造完成 version={} systemLen={} userLen={} 估算 tokens≈{}",
                itemId, prompt.getPromptVersion(), sysLen, userLen, (sysLen + userLen) / 4);

        // ③ 调 LLM —— 通过 router 按 modelKey 选择具体客户端
        LlmClient llm = llmRouter.route(modelKey);
        if (llm == null) {
            log.error("[dii-llm][itemId={}] ✖ LlmClient 未装配，检查 ai.analysis.enabled=true", itemId);
            result.setError("LlmClient 未装配，确认 ai.analysis.enabled=true");
            itemDao.updateLlmStatusOnly(itemId, "FAILED", result.getError());
            return result;
        }
        // ③+④+⑤：调 LLM → 校验非空 → 解析 JSON。三步任一失败都触发重试，
        //         最多 (1 + llmRetryAttempts) 次。整个块通过后 result 被替换为 parsed。
        int maxAttempts = Math.max(1, 1 + llmRetryAttempts);
        long llmTotalElapsed = 0;
        SqlLlmResult parsedOk = null;
        String lastErr = null;
        String lastRawHead = null;
        String lastModel = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String tag = attempt == 1 ? "首次" : ("重试#" + (attempt - 1));
            log.info("[dii-llm][itemId={}] ③ 调用 LLM model={} 路由到 {} ({})",
                    itemId, modelKey, llm.getClass().getSimpleName(), tag);

            long llmStart = System.currentTimeMillis();
            LlmResult raw;
            try {
                raw = llm.analyze(prompt, AnalysisMode.FULL, null);
            } catch (Throwable t) {
                long elapsed = System.currentTimeMillis() - llmStart;
                llmTotalElapsed += elapsed;
                lastErr = "LLM 调用异常：" + t.getClass().getSimpleName() + ": " + t.getMessage();
                boolean overloaded = isGatewayOverload(t);
                log.warn("[dii-llm][itemId={}] ✖ {} 调用异常 elapsed={}ms overloaded={} {}: {}",
                        itemId, tag, elapsed, overloaded, t.getClass().getSimpleName(), t.getMessage());
                if (attempt < maxAttempts) { sleepBackoff(attempt, overloaded); continue; }
                break;
            }
            long llmElapsed = System.currentTimeMillis() - llmStart;
            llmTotalElapsed += llmElapsed;
            lastModel = raw == null ? null : raw.getModel();
            int rawLen = (raw == null || raw.getRawText() == null) ? 0 : raw.getRawText().length();
            log.info("[dii-llm][itemId={}] ④ {} 返回 model={} rawLen={} elapsed={}ms",
                    itemId, tag, lastModel, rawLen, llmElapsed);

            if (raw == null || raw.getRawText() == null || raw.getRawText().isBlank()) {
                lastErr = "LLM 返回为空";
                log.warn("[dii-llm][itemId={}] ✖ {} 返回为空", itemId, tag);
                if (attempt < maxAttempts) { sleepBackoff(attempt, false); continue; }
                break;
            }

            try {
                SqlLlmResult parsed = parseLlmJson(raw.getRawText());
                log.info("[dii-llm][itemId={}] ⑤ {} JSON 解析成功 findings={} suggestions={} confidence={}",
                        itemId, tag,
                        parsed.getFindings() == null ? 0 : parsed.getFindings().size(),
                        parsed.getSuggestions() == null ? 0 : parsed.getSuggestions().size(),
                        parsed.getConfidence());
                parsedOk = parsed;
                break;
            } catch (Exception e) {
                lastRawHead = truncate(raw.getRawText(), 300);
                lastErr = "LLM 返回 JSON 解析失败：" + e.getMessage();
                log.warn("[dii-llm][itemId={}] ✖ {} JSON 解析失败：{} | rawHead={}",
                        itemId, tag, e.getMessage(), lastRawHead);
                if (attempt < maxAttempts) { sleepBackoff(attempt, false); continue; }
            }
        }

        result.setElapsedMs(llmTotalElapsed);
        result.setModel(lastModel);
        if (parsedOk == null) {
            result.setError(lastErr + (lastRawHead == null ? "" : " | rawHead=" + lastRawHead));
            log.warn("[dii-llm][itemId={}] ✖ 全部 {} 次尝试均失败，最后错误：{}",
                    itemId, maxAttempts, lastErr);
            saveFailed(itemId, result);
            return result;
        }
        parsedOk.setElapsedMs(result.getElapsedMs());
        parsedOk.setModel(result.getModel());
        parsedOk.setPromptVersion(result.getPromptVersion());
        result = parsedOk;
        long llmElapsed = llmTotalElapsed;

        // ⑥ DDL 长度兜底
        int beforeGuard = countCreateIndexSuggestions(result);
        applyKeyLengthGuard(result, ctx.tableMetadataMap);
        int overLimit = countOverLimitSuggestions(result);
        if (beforeGuard > 0) {
            log.info("[dii-llm][itemId={}] ⑥ DDL 长度校验 createIndex={} overLimit={}",
                    itemId, beforeGuard, overLimit);
        }

        // ⑦ 落库
        saveDone(itemId, result);
        long totalMs = System.currentTimeMillis() - entryMs;
        log.info("[dii-llm][itemId={}] ✔ 全流程完成 suggestions={}(TABLE={}/SQL={}) " +
                        "findings={} confidence={} llmElapsed={}ms totalElapsed={}ms",
                itemId,
                result.getSuggestions() == null ? 0 : result.getSuggestions().size(),
                countSuggestionsByScope(result, "TABLE"),
                countSuggestionsByScope(result, "SQL"),
                result.getFindings() == null ? 0 : result.getFindings().size(),
                result.getConfidence(),
                llmElapsed,
                totalMs);
        return result;
    }

    /** 统计 CREATE_INDEX 建议数量，辅助日志。 */
    private int countCreateIndexSuggestions(SqlLlmResult r) {
        if (r == null || r.getSuggestions() == null) return 0;
        int n = 0;
        for (LlmSuggestion s : r.getSuggestions()) {
            if (s != null && "CREATE_INDEX".equals(s.getType())) n++;
        }
        return n;
    }

    private int countOverLimitSuggestions(SqlLlmResult r) {
        if (r == null || r.getSuggestions() == null) return 0;
        int n = 0;
        for (LlmSuggestion s : r.getSuggestions()) {
            if (s != null && Boolean.TRUE.equals(s.getExceedsLengthLimit())) n++;
        }
        return n;
    }

    private int countSuggestionsByScope(SqlLlmResult r, String scope) {
        if (r == null || r.getSuggestions() == null) return 0;
        int n = 0;
        for (LlmSuggestion s : r.getSuggestions()) {
            if (s != null && scope.equals(s.getScope())) n++;
        }
        return n;
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private SqlLlmPromptBuilder.Context loadContext(long itemId) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> row = itemDao.loadById(itemId);
        if (row == null) {
            log.debug("[dii-llm][itemId={}] loadContext: item 不存在", itemId);
            return null;
        }
        log.debug("[dii-llm][itemId={}] loadContext: 读 item 耗时 {}ms", itemId, System.currentTimeMillis() - t0);

        SqlInspectionResult ir = reconstructResult(row);
        List<String> tables = parseInvolvedTables(row);
        log.debug("[dii-llm][itemId={}] loadContext: 涉及表 {}", itemId, tables);

        long t1 = System.currentTimeMillis();
        Map<String, TableMetadata> tableMetadataMap = tableMetadataService.getAll(ir.getEnv(), tables);
        log.debug("[dii-llm][itemId={}] loadContext: 查表元数据 {} 张 耗时 {}ms",
                itemId, tableMetadataMap.size(), System.currentTimeMillis() - t1);

        long t2 = System.currentTimeMillis();
        Map<String, SameTableAccessPattern> patterns = new LinkedHashMap<>();
        for (String t : tables) {
            try {
                SameTableAccessPattern p = patternCollector.collect(ir.getEnv(), t, itemId);
                patterns.put(t.toLowerCase(Locale.ROOT), p);
                log.debug("[dii-llm][itemId={}] 同表模式 table={} totalSqls={} buckets={}",
                        itemId, t, p.getTotalSqlCount(),
                        p.getByPredicate() == null ? 0 : p.getByPredicate().size());
            } catch (Exception e) {
                log.warn("[dii-llm][itemId={}] 同表访问模式收集失败 table={}: {}",
                        itemId, t, e.getMessage());
            }
        }
        log.debug("[dii-llm][itemId={}] loadContext: 同表模式总耗时 {}ms",
                itemId, System.currentTimeMillis() - t2);

        SqlLlmPromptBuilder.Context ctx = new SqlLlmPromptBuilder.Context();
        ctx.inspectionResult = ir;
        ctx.tableMetadataMap = tableMetadataMap;
        ctx.sameTablePatterns = patterns;
        return ctx;
    }

    /** 从 DB row 反序列化成 SqlInspectionResult。只重建 Prompt 需要的字段。 */
    private SqlInspectionResult reconstructResult(Map<String, Object> row) {
        SqlInspectionResult ir = new SqlInspectionResult();
        Object idVal = row.get("id");
        if (idVal instanceof Number) ir.setItemId(((Number) idVal).longValue());
        ir.setSql(str(row.get("sql_text")));
        ir.setEnv(str(row.get("env")));
        ir.setSqlHash(str(row.get("sql_hash")));
        String rating = str(row.get("overall_rating"));
        if (rating != null) {
            try { ir.setOverallRating(IndexRating.valueOf(rating)); } catch (Exception ignore) {}
        }
        String runtimeRating = str(row.get("runtime_rating"));
        if (runtimeRating != null) {
            try { ir.setRuntimeRating(IndexRating.valueOf(runtimeRating)); } catch (Exception ignore) {}
        }
        ir.setExplainError(str(row.get("explain_error")));
        ir.setExplainPlanJson(str(row.get("explain_plan")));
        Object cost = row.get("explain_top_cost");
        if (cost instanceof Number) ir.setExplainTopCost(((Number) cost).doubleValue());
        Object rows = row.get("explain_est_rows");
        if (rows instanceof Number) ir.setExplainEstRows(((Number) rows).longValue());
        Object sq = row.get("explain_has_seq_scan");
        if (sq instanceof Number) ir.setExplainHasSeqScan(((Number) sq).intValue() == 1);
        Object dis = row.get("disagreement");
        ir.setDisagreement(dis instanceof Number && ((Number) dis).intValue() == 1);
        ir.setDisagreementReason(str(row.get("disagreement_reason")));

        // 反序列化 table_ratings_json
        String trJson = str(row.get("table_ratings_json"));
        if (trJson != null && !trJson.isBlank()) {
            try {
                CollectionType type = objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, TableRating.class);
                List<TableRating> trs = objectMapper.readValue(trJson, type);
                ir.setTableRatings(trs);
            } catch (Exception e) {
                log.warn("[dii-llm] 反序列化 table_ratings_json 失败 itemId={}: {}", ir.getItemId(), e.getMessage());
            }
        }
        return ir;
    }

    private List<String> parseInvolvedTables(Map<String, Object> row) {
        String csv = str(row.get("involved_tables"));
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * LLM 返回一般是 JSON 文本，但可能包裹在 markdown 代码块 / &lt;think&gt; 思考块里，
     * 或被 max_tokens 截断、混入非法转义字符。这里做三层兜底：
     * <ol>
     *   <li>剥壳：去掉 ```json / ``` markdown 围栏 + &lt;think&gt;...&lt;/think&gt; 思考块</li>
     *   <li>补齐：找第一个 {，按括号 depth 取平衡块；若被截断则尝试补 " 和 } 强行收尾</li>
     *   <li>消毒：把 Jackson 不认的非法转义（{@code \*} {@code \(} 等）降级为裸字符</li>
     * </ol>
     */
    private SqlLlmResult parseLlmJson(String raw) throws Exception {
        String cleaned = stripWrappers(raw);
        int braceStart = cleaned.indexOf('{');
        if (braceStart < 0) throw new IllegalStateException("未找到 JSON 开始 {");
        int depth = 0;
        int end = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = braceStart; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }

        String jsonText;
        if (end >= 0) {
            jsonText = cleaned.substring(braceStart, end + 1);
        } else {
            // 截断兜底：补 " 关闭未闭合字符串，再按当前 depth 追加 }
            StringBuilder sb = new StringBuilder(cleaned.substring(braceStart));
            if (inString) sb.append('"');
            int needClose = depth;
            for (int i = 0; i < needClose; i++) sb.append('}');
            jsonText = sb.toString();
            log.warn("[dii-llm] LLM 响应疑似截断，已自动补齐 {} 个 '}}' 兜底解析", needClose);
        }

        // 清洗 Jackson 不认的非法 \X 转义（保留 \" \\ \/ \b \f \n \r \t 和 unicode 转义）
        jsonText = sanitizeInvalidEscapes(jsonText);

        return objectMapper.readValue(jsonText, SqlLlmResult.class);
    }

    /**
     * 去掉 markdown 围栏 + DeepSeek-R1 / GLM-Z1 风格的 &lt;think&gt; 思考块。
     * 即使 think 块没闭合也兼容（一些模型会把 think 输出截断）。
     */
    private String stripWrappers(String s) {
        if (s == null) return "";
        // 闭合的 <think>...</think>
        s = s.replaceAll("(?is)<think>.*?</think>", "");
        // 未闭合的 <think>...EOF（直接吞到末尾）
        s = s.replaceAll("(?is)<think>.*", "");
        // markdown 代码块围栏（json / sql / 无标记都剥）
        s = s.replaceAll("(?s)```(?:json|sql)?\\s*", "").replaceAll("```", "");
        return s.trim();
    }

    /**
     * Jackson 严格模式下，字符串内的 {@code \*} {@code \(} 等无效转义会直接抛
     * "Unrecognized character escape"。LLM 偶尔会写 {@code SELECT \*} 这种，
     * 这里把所有非法 \X 转义降级为裸字符 X，保留 JSON 本身合法的 9 种转义。
     */
    private String sanitizeInvalidEscapes(String json) {
        StringBuilder out = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char nx = json.charAt(i + 1);
                // JSON 合法转义白名单：\" \\ \/ \b \f \n \r \t 以及 \\uXXXX
                if ("\"\\/bfnrtu".indexOf(nx) >= 0) {
                    out.append(c).append(nx);
                    i++;
                } else {
                    // 非法转义：丢掉反斜杠，保留原字符
                    out.append(nx);
                    i++;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 对 LLM 给出的 CREATE_INDEX DDL 做本地 key length 校验：超 200 自动加 riskWarning。
     */
    private void applyKeyLengthGuard(SqlLlmResult r, Map<String, TableMetadata> tmMap) {
        if (r == null || r.getSuggestions() == null || tmMap == null) return;
        for (LlmSuggestion s : r.getSuggestions()) {
            if (s == null || !"CREATE_INDEX".equals(s.getType())) continue;
            if (s.getDdl() == null || s.getDdl().isBlank()) continue;
            String tableName = extractTableName(s.getDdl());
            if (tableName == null) continue;
            TableMetadata md = tmMap.get(tableName.toLowerCase(Locale.ROOT));
            if (md == null) continue;
            IndexSizeEstimator.Estimate est = IndexSizeEstimator.estimateFromCreateIndex(s.getDdl(), md);
            s.setEstimatedKeyLength(est.keyLength);
            s.setExceedsLengthLimit(est.exceedsLimit);
            if (est.exceedsLimit) {
                String old = s.getRiskWarning() == null ? "" : (s.getRiskWarning() + " | ");
                s.setRiskWarning(old + "⚠️ " + est.reason + "，不建议执行");
            }
        }
    }

    /** 从 CREATE INDEX ... ON <table> ... 抽表名（去 schema 前缀、去引号）。 */
    private String extractTableName(String ddl) {
        Matcher m = CREATE_IDX_ON_TABLE.matcher(ddl);
        if (!m.find()) return null;
        String raw = m.group(1).replaceAll("[\"`]", "").replaceAll("\\(.*$", "");
        int dot = raw.lastIndexOf('.');
        String t = dot < 0 ? raw : raw.substring(dot + 1);
        return t.isEmpty() ? null : t;
    }

    private void saveFailed(long itemId, SqlLlmResult r) {
        try {
            itemDao.updateLlmStatusOnly(itemId, "FAILED", r.getError());
            log.warn("[dii-llm] itemId={} FAILED: {}", itemId, r.getError());
        } catch (Exception e) {
            log.error("[dii-llm] saveFailed 失败 itemId={}: {}", itemId, e.getMessage(), e);
        }
    }

    private void saveDone(long itemId, SqlLlmResult r) {
        try {
            String findingsJson = objectMapper.writeValueAsString(
                    r.getFindings() == null ? Collections.emptyList() : r.getFindings());
            String suggestionsJson = objectMapper.writeValueAsString(
                    r.getSuggestions() == null ? Collections.emptyList() : r.getSuggestions());
            itemDao.updateLlmFull(itemId,
                    "DONE",
                    r.getSummary(),
                    findingsJson,
                    suggestionsJson,
                    r.getConfidence(),
                    r.getFixVerdict(),
                    r.getModel(),
                    r.getPromptVersion(),
                    r.getElapsedMs() == null ? 0L : r.getElapsedMs(),
                    null /* error */);
        } catch (Exception e) {
            log.error("[dii-llm] saveDone 失败 itemId={}: {}", itemId, e.getMessage(), e);
            // 降级：至少把状态记成 FAILED，避免永远显示 PENDING
            try {
                itemDao.updateLlmStatusOnly(itemId, "FAILED", "落库序列化失败：" + e.getMessage());
            } catch (Exception ignore) {}
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // V16+：池行 LLM 分析（与 analyzeItem 同套 prompt 与 LLM 客户端，仅读写表不同）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 池行 LLM 分析。读 dii_sql_pool 行、构造 Context、调 LLM、写回池表。
     *
     * <p>与 {@link #analyzeItem(long, String, String)} 同套 prompt 模板 + LLM 客户端 +
     * JSON 解析逻辑；只是读 / 写换成 {@link com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao}。
     *
     * <p><b>不支持 overrideSql 重跑</b>（v1 简化；池行编辑场景由 DBA 通过 SQL 池页直接改）。
     */
    public SqlLlmResult analyzePoolRow(long poolId, String modelKey) {
        long entryMs = System.currentTimeMillis();
        log.info("[dii-llm][poolId={}] ── 开始池行 LLM 分析 model={} ──", poolId, modelKey);
        SqlLlmResult result = new SqlLlmResult();
        com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao poolDao =
                poolDaoProvider.getIfAvailable();
        if (poolDao == null) {
            result.setError("DiiSqlPoolDao 未装配");
            return result;
        }

        // ① 加载上下文
        SqlLlmPromptBuilder.Context ctx = loadContextFromPool(poolId, poolDao);
        if (ctx == null) {
            log.warn("[dii-llm][poolId={}] ✖ 池行不存在", poolId);
            result.setError("池行不存在：poolId=" + poolId);
            poolDao.updateLlmStatusOnly(poolId, "FAILED", result.getError());
            return result;
        }

        // 兜底：仅 overall_rating=POOR 才跑（与 item 同口径）
        boolean needFix = ctx.inspectionResult != null
                && ctx.inspectionResult.getOverallRating() == IndexRating.POOR;
        if (!needFix) {
            String reason = "overall_rating != POOR（非需整改候选），跳过 LLM";
            log.info("[dii-llm][poolId={}] ✖ {}", poolId, reason);
            result.setError(reason);
            poolDao.updateLlmStatusOnly(poolId, "SKIPPED", reason);
            return result;
        }

        // ② Prompt
        AnalysisPrompt prompt = promptBuilder.build(ctx);
        result.setPromptVersion(prompt.getPromptVersion());

        // ③/④/⑤ LLM 调用 + 重试 + JSON 解析（流程与 analyzeItem 一致）
        LlmClient llm = llmRouter.route(modelKey);
        if (llm == null) {
            result.setError("LlmClient 未装配");
            poolDao.updateLlmStatusOnly(poolId, "FAILED", result.getError());
            return result;
        }
        int maxAttempts = Math.max(1, 1 + llmRetryAttempts);
        long llmTotalElapsed = 0;
        SqlLlmResult parsedOk = null;
        String lastErr = null;
        String lastModel = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long llmStart = System.currentTimeMillis();
            LlmResult raw;
            try {
                raw = llm.analyze(prompt, AnalysisMode.FULL, null);
            } catch (Throwable t) {
                long elapsed = System.currentTimeMillis() - llmStart;
                llmTotalElapsed += elapsed;
                lastErr = "LLM 调用异常：" + t.getClass().getSimpleName() + ": " + t.getMessage();
                boolean overloaded = isGatewayOverload(t);
                log.warn("[dii-llm][poolId={}] ✖ 调用异常 attempt={}: {}", poolId, attempt, t.getMessage());
                if (attempt < maxAttempts) { sleepBackoff(attempt, overloaded); continue; }
                break;
            }
            long llmElapsed = System.currentTimeMillis() - llmStart;
            llmTotalElapsed += llmElapsed;
            lastModel = raw == null ? null : raw.getModel();
            if (raw == null || raw.getRawText() == null || raw.getRawText().isBlank()) {
                lastErr = "LLM 返回为空";
                if (attempt < maxAttempts) { sleepBackoff(attempt, false); continue; }
                break;
            }
            try {
                parsedOk = parseLlmJson(raw.getRawText());
                break;
            } catch (Exception e) {
                lastErr = "JSON 解析失败：" + e.getMessage();
                if (attempt < maxAttempts) { sleepBackoff(attempt, false); continue; }
            }
        }

        result.setElapsedMs(llmTotalElapsed);
        result.setModel(lastModel);
        if (parsedOk == null) {
            result.setError(lastErr);
            poolDao.updateLlmStatusOnly(poolId, "FAILED", lastErr);
            return result;
        }
        parsedOk.setElapsedMs(result.getElapsedMs());
        parsedOk.setModel(result.getModel());
        parsedOk.setPromptVersion(result.getPromptVersion());
        result = parsedOk;
        applyKeyLengthGuard(result, ctx.tableMetadataMap);

        // ⑦ 落库（pool）
        try {
            String findingsJson = objectMapper.writeValueAsString(
                    result.getFindings() == null ? Collections.emptyList() : result.getFindings());
            String suggestionsJson = objectMapper.writeValueAsString(
                    result.getSuggestions() == null ? Collections.emptyList() : result.getSuggestions());
            poolDao.updateLlmFull(poolId, "DONE",
                    result.getSummary(), findingsJson, suggestionsJson,
                    result.getConfidence(), result.getFixVerdict(),
                    result.getModel(), result.getPromptVersion(),
                    result.getElapsedMs() == null ? 0L : result.getElapsedMs(),
                    null);
        } catch (Exception e) {
            log.error("[dii-llm][poolId={}] saveDone 失败: {}", poolId, e.getMessage(), e);
            try {
                poolDao.updateLlmStatusOnly(poolId, "FAILED", "落库序列化失败：" + e.getMessage());
            } catch (Exception ignore) {}
        }
        long totalMs = System.currentTimeMillis() - entryMs;
        log.info("[dii-llm][poolId={}] ✔ 完成 elapsed={}ms", poolId, totalMs);
        return result;
    }

    /** 池行加载上下文：构造与 item 同 shape 的 Context。 */
    private SqlLlmPromptBuilder.Context loadContextFromPool(
            long poolId,
            com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao poolDao) {
        Map<String, Object> row = poolDao.loadInspectionContext(poolId);
        if (row == null) return null;

        SqlInspectionResult ir = new SqlInspectionResult();
        Object idVal = row.get("id");
        if (idVal instanceof Number) ir.setItemId(((Number) idVal).longValue());
        ir.setSql(str(row.get("sql_text")));
        ir.setEnv(str(row.get("env")));
        ir.setSqlHash(str(row.get("sql_hash")));
        String rating = str(row.get("overall_rating"));
        if (rating != null) {
            try { ir.setOverallRating(IndexRating.valueOf(rating)); } catch (Exception ignore) {}
        }
        ir.setExplainError(str(row.get("explain_error")));
        ir.setExplainPlanJson(str(row.get("explain_plan")));
        Object cost = row.get("explain_top_cost");
        if (cost instanceof Number) ir.setExplainTopCost(((Number) cost).doubleValue());
        Object rows = row.get("explain_est_rows");
        if (rows instanceof Number) ir.setExplainEstRows(((Number) rows).longValue());
        Object sq = row.get("explain_has_seq_scan");
        if (sq instanceof Number) ir.setExplainHasSeqScan(((Number) sq).intValue() == 1);

        List<String> tables = new ArrayList<>();
        String csv = str(row.get("involved_tables"));
        if (csv != null && !csv.isBlank()) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) tables.add(t);
            }
        }
        Map<String, TableMetadata> tableMetadataMap = tableMetadataService.getAll(ir.getEnv(), tables);
        // 同表访问模式：池行没挂 task，且 collector 接口要 itemId——传 poolId 也行，
        // 仅用于排除自身。本期不依赖该信号，传空 map 即可（promptBuilder 已条件化）。
        Map<String, com.axonlink.ai.daoindex.sqlinspect.llm.SameTableAccessPattern> patterns =
                new LinkedHashMap<>();

        SqlLlmPromptBuilder.Context ctx = new SqlLlmPromptBuilder.Context();
        ctx.inspectionResult = ir;
        ctx.tableMetadataMap = tableMetadataMap;
        ctx.sameTablePatterns = patterns;
        return ctx;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 重试退避：第 N 次重试睡 N * backoff 毫秒（线性退避），中断时复位 interrupt 标志。 */
    /**
     * 重试前的退避睡眠。
     * <p>普通错误：{@link #llmRetryBackoffMs} × attempt（默认 2s 起）。
     * <p>网关过载（502/503/504）：{@link #llmOverloadBackoffMs} × attempt（默认 15s 起），
     * 给上游 MiniMax / 网关足够时间恢复，避免雪崩。
     */
    private void sleepBackoff(int attempt, boolean overloaded) {
        long base = overloaded ? llmOverloadBackoffMs : llmRetryBackoffMs;
        long ms = Math.max(0, base) * attempt;
        if (ms <= 0) return;
        log.info("[dii-llm] retry backoff sleeping {}ms (overloaded={})", ms, overloaded);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断异常是否为"网关过载"类。
     * <p>当前 {@link com.axonlink.ai.provider.OpenAiCompatibleClient} 在 HTTP 非 2xx 时会抛
     * {@code IllegalStateException("模型请求失败，HTTP 502/503/504...")}，所以这里
     * 直接用消息文本匹配，不需要把状态码穿透到 dto。
     */
    private boolean isGatewayOverload(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("HTTP 502") || msg.contains("HTTP 503") || msg.contains("HTTP 504"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
