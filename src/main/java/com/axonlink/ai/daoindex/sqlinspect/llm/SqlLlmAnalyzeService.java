package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableRating;
import com.axonlink.ai.daoindex.sqlinspect.metadata.TableMetadataService;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.LlmResult;
import com.axonlink.ai.provider.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
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
    private final ObjectMapper objectMapper;

    public SqlLlmAnalyzeService(DiiAnalysisItemDao itemDao,
                                TableMetadataService tableMetadataService,
                                SameTableAccessPatternCollector patternCollector,
                                SqlLlmPromptBuilder promptBuilder,
                                ObjectProvider<LlmClient> llmClientProvider,
                                ObjectMapper objectMapper) {
        this.itemDao = itemDao;
        this.tableMetadataService = tableMetadataService;
        this.patternCollector = patternCollector;
        this.promptBuilder = promptBuilder;
        this.llmClientProvider = llmClientProvider;
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

    /**
     * 真正调 LLM 分析单条 item。
     * 失败不抛：失败信息记入 {@link SqlLlmResult#getError} 并落库 FAILED，可手动重跑。
     */
    public SqlLlmResult analyzeItem(long itemId) {
        long entryMs = System.currentTimeMillis();
        log.info("[dii-llm][itemId={}] ── 开始 LLM 分析 ──", itemId);

        SqlLlmResult result = new SqlLlmResult();

        // ① 加载上下文
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

        // ── 兜底：runtime_rating 必须是 POOR 或 GOOD 才跑 LLM ──
        com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating rr =
                ctx.inspectionResult == null ? null : ctx.inspectionResult.getRuntimeRating();
        boolean allowLlm = (rr == com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating.POOR
                         || rr == com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating.GOOD);
        if (!allowLlm) {
            String reason = "runtime_rating=" + rr + "（非 POOR/GOOD），跳过 LLM";
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

        // ③ 调 LLM
        LlmClient llm = llmClientProvider.getIfAvailable();
        if (llm == null) {
            log.error("[dii-llm][itemId={}] ✖ LlmClient 未装配，检查 ai.analysis.enabled=true", itemId);
            result.setError("LlmClient 未装配，确认 ai.analysis.enabled=true");
            itemDao.updateLlmStatusOnly(itemId, "FAILED", result.getError());
            return result;
        }
        log.info("[dii-llm][itemId={}] ③ 调用 LLM...", itemId);
        long llmStart = System.currentTimeMillis();
        LlmResult raw;
        try {
            raw = llm.analyze(prompt, AnalysisMode.FULL, null);
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - llmStart;
            log.warn("[dii-llm][itemId={}] ✖ LLM 调用异常 elapsed={}ms {}: {}",
                    itemId, elapsed, t.getClass().getSimpleName(), t.getMessage());
            result.setError("LLM 调用异常：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            result.setElapsedMs(elapsed);
            saveFailed(itemId, result);
            return result;
        }
        long llmElapsed = System.currentTimeMillis() - llmStart;
        result.setElapsedMs(llmElapsed);
        result.setModel(raw == null ? null : raw.getModel());
        int rawLen = (raw == null || raw.getRawText() == null) ? 0 : raw.getRawText().length();
        log.info("[dii-llm][itemId={}] ④ LLM 返回 model={} rawLen={} elapsed={}ms",
                itemId, result.getModel(), rawLen, llmElapsed);

        if (raw == null || raw.getRawText() == null || raw.getRawText().isBlank()) {
            log.warn("[dii-llm][itemId={}] ✖ LLM 返回为空", itemId);
            result.setError("LLM 返回为空");
            saveFailed(itemId, result);
            return result;
        }

        // ⑤ 解析 JSON
        try {
            SqlLlmResult parsed = parseLlmJson(raw.getRawText());
            parsed.setElapsedMs(result.getElapsedMs());
            parsed.setModel(result.getModel());
            parsed.setPromptVersion(result.getPromptVersion());
            result = parsed;
            log.info("[dii-llm][itemId={}] ⑤ JSON 解析成功 findings={} suggestions={} confidence={}",
                    itemId,
                    result.getFindings() == null ? 0 : result.getFindings().size(),
                    result.getSuggestions() == null ? 0 : result.getSuggestions().size(),
                    result.getConfidence());
        } catch (Exception e) {
            log.warn("[dii-llm][itemId={}] ✖ JSON 解析失败：{} | rawHead={}",
                    itemId, e.getMessage(), truncate(raw.getRawText(), 300));
            result.setError("LLM 返回 JSON 解析失败：" + e.getMessage()
                    + " | rawHead=" + truncate(raw.getRawText(), 300));
            saveFailed(itemId, result);
            return result;
        }

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
     * LLM 返回一般是 JSON 文本，但可能包裹在 markdown 代码块里 / 带前后闲聊。
     * 这里尽量"宽容提取"：剥掉 ```json / ```、取第一个 {...} 平衡括号块。
     */
    private SqlLlmResult parseLlmJson(String raw) throws Exception {
        String cleaned = stripMarkdown(raw);
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
        if (end < 0) throw new IllegalStateException("JSON 括号不平衡");
        String jsonText = cleaned.substring(braceStart, end + 1);
        return objectMapper.readValue(jsonText, SqlLlmResult.class);
    }

    private String stripMarkdown(String s) {
        if (s == null) return "";
        return s.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("```", "").trim();
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
