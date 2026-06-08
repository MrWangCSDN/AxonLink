package com.axonlink.ai.daoindex.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.health.DaoIndexHealthIndicator;
import com.axonlink.ai.daoindex.health.DaoIndexHealthReport;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexMeta;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionRequest;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult;
import com.axonlink.ai.daoindex.sqlinspect.explain.ColumnSampleService;
import com.axonlink.ai.daoindex.sqlinspect.llm.LlmEnrichService;
import com.axonlink.ai.daoindex.sqlinspect.llm.SqlLlmAnalyzeService;
import com.axonlink.ai.daoindex.sqlinspect.llm.SqlLlmResult;
import com.axonlink.ai.daoindex.sqlinspect.meta.IndexMetaService;
import com.axonlink.ai.daoindex.sqlinspect.metadata.TableMetadataService;
import com.axonlink.ai.daoindex.sqlinspect.batch.BatchInspectionService;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlCandidate;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisTaskDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiDashboardDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao;
import com.axonlink.ai.daoindex.sqlinspect.scan.SqlSourceScanner;
import com.axonlink.ai.daoindex.sqlinspect.service.SqlInspectionService;
import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.LlmResult;
import com.axonlink.ai.provider.LlmClient;
import com.axonlink.common.R;
import com.axonlink.config.AiAnalysisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAO 索引巡检模块 HTTP 入口。
 *
 * <p>PR-1 暴露 {@code GET /health} 和 {@code GET /debug/llm-ping}，
 * 后续 PR 在此类追加 {@code /batch-analyze}、{@code /reanalyze} 等接口。
 */
@RestController
@RequestMapping("/api/ai/dao-index")
public class DaoIndexController {

    private static final Logger log = LoggerFactory.getLogger(DaoIndexController.class);

    private final DaoIndexHealthIndicator healthIndicator;
    private final ObjectProvider<LlmClient> llmClientProvider;
    private final ObjectProvider<AiAnalysisConfig> aiConfigProvider;
    private final SqlInspectionService sqlInspectionService;
    private final IndexMetaService indexMetaService;
    private final TargetDataSourceRegistry targetRegistry;
    private final DiiAnalysisItemDao itemDao;
    private final BatchInspectionService batchInspectionService;
    private final DiiAnalysisTaskDao taskDao;
    private final DiiDashboardDao dashboardDao;
    // V14 SQL 池：SQL 分析页 + 看板需要把池数据并入展示
    private final DiiSqlPoolDao poolDao;
    private final SqlSourceScanner scanner;
    private final ObjectProvider<ColumnSampleService> columnSampleServiceProvider;
    private final ObjectProvider<TableMetadataService> tableMetadataServiceProvider;
    private final ObjectProvider<SqlLlmAnalyzeService> llmAnalyzeServiceProvider;
    private final ObjectProvider<LlmEnrichService> llmEnrichServiceProvider;
    // 注入 DAO 索引巡检模块的总配置，主要用于读取批量触发口令（batch-trigger.token）
    private final DaoIndexAnalysisProperties props;

    public DaoIndexController(DaoIndexHealthIndicator healthIndicator,
                              ObjectProvider<LlmClient> llmClientProvider,
                              ObjectProvider<AiAnalysisConfig> aiConfigProvider,
                              SqlInspectionService sqlInspectionService,
                              IndexMetaService indexMetaService,
                              TargetDataSourceRegistry targetRegistry,
                              DiiAnalysisItemDao itemDao,
                              BatchInspectionService batchInspectionService,
                              DiiAnalysisTaskDao taskDao,
                              SqlSourceScanner scanner,
                              ObjectProvider<ColumnSampleService> columnSampleServiceProvider,
                              ObjectProvider<TableMetadataService> tableMetadataServiceProvider,
                              ObjectProvider<SqlLlmAnalyzeService> llmAnalyzeServiceProvider,
                              ObjectProvider<LlmEnrichService> llmEnrichServiceProvider,
                              DaoIndexAnalysisProperties props,
                              DiiDashboardDao dashboardDao,
                              DiiSqlPoolDao poolDao) {
        this.healthIndicator = healthIndicator;
        this.llmClientProvider = llmClientProvider;
        this.aiConfigProvider = aiConfigProvider;
        this.sqlInspectionService = sqlInspectionService;
        this.indexMetaService = indexMetaService;
        this.targetRegistry = targetRegistry;
        this.itemDao = itemDao;
        this.batchInspectionService = batchInspectionService;
        this.taskDao = taskDao;
        this.scanner = scanner;
        this.columnSampleServiceProvider = columnSampleServiceProvider;
        this.tableMetadataServiceProvider = tableMetadataServiceProvider;
        this.llmAnalyzeServiceProvider = llmAnalyzeServiceProvider;
        this.llmEnrichServiceProvider = llmEnrichServiceProvider;
        this.props = props;
        this.dashboardDao = dashboardDao;
        this.poolDao = poolDao;
    }

    /**
     * 概览仪表盘聚合接口（4 块数据合并返回，前端一次拿全）：
     *
     * <ul>
     *   <li>{@code latestTask} — 最新 DONE 任务元信息（taskNo / 时间 / 总数）</li>
     *   <li>{@code byDomain} — 按领域聚合的"巡检 SQL 数 / EXPLAIN 报错 / LLM 整改"</li>
     *   <li>{@code ratingByDomain} — 按领域聚合的"整改分布"两档（error_count 报错 / need_fix 待整改）</li>
     *   <li>{@code trend7d} — 最近 7 个 DONE 任务的四档评级趋势</li>
     *   <li>{@code elapsed7d} — 最近 7 个 DONE 任务的执行时长（秒）</li>
     * </ul>
     *
     * <p>无最新 DONE 任务时（env 下还没跑过批量），返回 {@code latestTask=null}，
     * 各 List 字段为空数组，前端展示空状态即可。
     *
     * <p>示例：{@code GET /api/ai/dao-index/dashboard?env=uat}
     */
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard(@RequestParam(required = false) String env) {
        String effEnv = (env == null || env.isBlank())
                ? targetRegistry.getDefaultEnv() : env;

        Map<String, Object> latest = dashboardDao.latestDoneTask(effEnv);
        Long latestId = latest == null ? null
                : ((Number) latest.get("id")).longValue();

        // elapsed 接口返回的是"最近 N 条 DESC"，前端要正序展示，这里翻转成 ASC
        java.util.List<Map<String, Object>> elapsed = new java.util.ArrayList<>(
                dashboardDao.elapsedRecentTasks(effEnv, 7));
        java.util.Collections.reverse(elapsed);

        // V14：item 表（task 维度）+ pool 表（env 维度）按 domain 合并后再返回
        // 合并语义：同 domain 各字段直接相加；pool 没有 task 约束，全部按 env 进入"最新任务"看板
        // v6：byDomain 用 need_fix(需整改)取代 llm_fix，并加 白名单申请中/已申请白名单 两列
        List<Map<String, Object>> byDomain = mergeByDomain(
                dashboardDao.aggregateByDomain(latestId),
                poolDao.aggregateByDomain(effEnv),
                "total", "explain_err", "need_fix", "wl_applying", "wl_approved");
        List<Map<String, Object>> ratingByDomain = mergeByDomain(
                dashboardDao.aggregateRatingByDomain(latestId),
                poolDao.aggregateRatingByDomain(effEnv),
                "error_count", "need_fix", "wl_applying", "wl_approved");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("env", effEnv);
        payload.put("latestTask", latest);
        payload.put("byDomain", byDomain);
        payload.put("ratingByDomain", ratingByDomain);
        payload.put("trend7d", dashboardDao.trendRecentTasks(effEnv, 7));
        payload.put("elapsed7d", elapsed);
        return R.ok(payload);
    }

    /**
     * 把两份 {@code [{domain, ...counts}]} 列表按 domain 合并相加。
     *
     * <p>用于看板：item（task 维度）+ pool（env 维度）两路聚合按业务领域合并，
     * 同 domain 的指定 count 字段直接相加。结果按字段排序保留稳定性。
     *
     * @param countCols 需要相加的字段名（如 "total", "explain_err"）
     */
    private static List<Map<String, Object>> mergeByDomain(
            List<Map<String, Object>> a,
            List<Map<String, Object>> b,
            String... countCols) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (List<Map<String, Object>> src : List.of(a == null ? List.<Map<String, Object>>of() : a,
                                                     b == null ? List.<Map<String, Object>>of() : b)) {
            for (Map<String, Object> row : src) {
                String domain = String.valueOf(row.get("domain"));
                Map<String, Object> agg = merged.computeIfAbsent(domain, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("domain", k);
                    for (String c : countCols) m.put(c, 0L);
                    return m;
                });
                for (String c : countCols) {
                    long prev = ((Number) agg.getOrDefault(c, 0L)).longValue();
                    Object v = row.get(c);
                    long add = v instanceof Number n ? n.longValue() : 0L;
                    agg.put(c, prev + add);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    @GetMapping("/health")
    public R<DaoIndexHealthReport> health() {
        try {
            return R.ok(healthIndicator.check());
        } catch (Exception e) {
            return R.fail("DAO 索引巡检自检异常：" + e.getMessage());
        }
    }

    /**
     * LLM 连通性探针。
     *
     * <p>发送一条最小 prompt，验证内网模型是否可达、协议是否兼容。
     * 返回模型原始回包，方便排查 URL / API Key / 协议路径等配置问题。
     *
     * <p>示例：{@code GET /api/ai/dao-index/debug/llm-ping?msg=你好}
     */
    @GetMapping("/debug/llm-ping")
    public R<Map<String, Object>> llmPing(
            @RequestParam(defaultValue = "你好，请用一句话介绍你自己。") String msg) {

        LlmClient client = llmClientProvider.getIfAvailable();
        Map<String, Object> result = new LinkedHashMap<>();

        if (client == null) {
            result.put("ok", false);
            result.put("error", "LlmClient Bean 不存在，请确认 ai.analysis.enabled=true 且配置正确");
            return R.ok(result);
        }

        // 打印当前使用的目标地址，便于在日志里一眼看到实际请求 URL
        AiAnalysisConfig cfg = aiConfigProvider.getIfAvailable();
        String baseUrl = cfg == null ? "(AiAnalysisConfig 未装配)" : cfg.getGlm5().getBaseUrl();
        String chatPath = cfg == null ? "" : cfg.getGlm5().getChatPath();
        String model    = cfg == null ? "" : cfg.getGlm5().getModel();
        log.info("[llm-ping] 开始探针：baseUrl={} chatPath={} model={}", baseUrl, chatPath, model);
        result.put("baseUrl", baseUrl);
        result.put("chatPath", chatPath);
        result.put("configuredModel", model);

        long start = System.currentTimeMillis();
        try {
            AnalysisPrompt prompt = new AnalysisPrompt();
            prompt.setSystemPrompt("你是一个代码解读助手，请简洁回答。");
            prompt.setUserPrompt(msg);
            prompt.setPromptVersion("ping-v1");

            LlmResult llmResult = client.analyze(prompt, AnalysisMode.FULL, null);

            result.put("ok", true);
            result.put("model", llmResult.getModel());
            result.put("rawText", llmResult.getRawText());
            result.put("elapsedMs", System.currentTimeMillis() - start);
            log.info("[llm-ping] 探针成功，耗时 {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 打完整堆栈，方便后台排查
            log.error("[llm-ping] 探针失败，baseUrl={} chatPath={}", baseUrl, chatPath, e);

            // 回包里输出异常链，避免出现 error:"调用模型失败：null" 这种无信息提示
            result.put("ok", false);
            result.put("error", buildErrorChain(e));
            result.put("elapsedMs", System.currentTimeMillis() - start);
        }
        return R.ok(result);
    }

    /**
     * 诊断接口：一次性查清楚"为什么查不到索引"。
     *
     * <p>返回：
     * <ul>
     *   <li>current_database() / current_schema() / current_user（确认连上的是哪个库）</li>
     *   <li>search_path（确认 schema 搜索路径）</li>
     *   <li>该表出现在哪些 schema 下（跨 schema 定位）</li>
     *   <li>该表的列（验证表本身是否可读）</li>
     *   <li>该表索引数量（绕过 IndexMetaService 直接查）</li>
     *   <li>pg_index 是否可读（权限验证）</li>
     * </ul>
     *
     * <p>示例：{@code GET /api/ai/dao-index/debug/probe-table?env=uat&table=kagl_acctg}
     */
    @GetMapping("/debug/probe-table")
    public R<Map<String, Object>> probeTable(@RequestParam(required = false) String env,
                                             @RequestParam String table) {
        Map<String, Object> r = new LinkedHashMap<>();
        String effEnv = (env == null || env.isBlank()) ? targetRegistry.getDefaultEnv() : env;
        r.put("env", effEnv);
        r.put("queryTable", table);

        DataSource ds;
        try {
            ds = targetRegistry.getByEnv(effEnv);
        } catch (Exception e) {
            r.put("error", "env 不存在：" + e.getMessage());
            return R.ok(r);
        }

        try (Connection conn = ds.getConnection()) {
            // 1. 基础上下文
            r.put("currentDatabase", single(conn, "SELECT current_database()"));
            r.put("currentSchema",   single(conn, "SELECT current_schema()"));
            r.put("currentUser",     single(conn, "SELECT current_user"));
            r.put("searchPath",      single(conn, "SHOW search_path"));

            // 2. 表出现在哪些 schema（不区分大小写）
            List<Map<String, Object>> schemasWithTable = queryRows(conn,
                    "SELECT n.nspname AS schema_name, c.relname AS table_name, c.relkind AS kind " +
                    "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE lower(c.relname) = lower(?) " +
                    "  AND c.relkind IN ('r','p','m','v') " +
                    "ORDER BY n.nspname",
                    table);
            r.put("tableFoundInSchemas", schemasWithTable);

            // 3. 表的列（最多 10 列）
            List<Map<String, Object>> columns = queryRows(conn,
                    "SELECT n.nspname AS schema_name, a.attname AS column_name, " +
                    "       format_type(a.atttypid, a.atttypmod) AS data_type, a.attnum " +
                    "FROM pg_attribute a " +
                    "JOIN pg_class c ON c.oid = a.attrelid " +
                    "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE lower(c.relname) = lower(?) AND a.attnum > 0 AND NOT a.attisdropped " +
                    "ORDER BY n.nspname, a.attnum LIMIT 10",
                    table);
            r.put("tableColumnsPreview", columns);

            // 4. 直接查 pg_index（不经过 IndexMetaService）
            List<Map<String, Object>> rawIndexes = queryRows(conn,
                    "SELECT n.nspname AS schema_name, i.relname AS index_name, " +
                    "       ix.indisprimary, ix.indisunique, ix.indnatts " +
                    "FROM pg_index ix " +
                    "JOIN pg_class t ON t.oid = ix.indrelid " +
                    "JOIN pg_class i ON i.oid = ix.indexrelid " +
                    "JOIN pg_namespace n ON n.oid = t.relnamespace " +
                    "WHERE lower(t.relname) = lower(?) " +
                    "ORDER BY n.nspname, i.relname",
                    table);
            r.put("rawIndexesFromPgIndex", rawIndexes);

            // 5. 测试 unnest WITH ORDINALITY 是否支持（GaussDB 部分定制版可能不支持）
            try {
                List<Map<String, Object>> unnestTest = queryRows(conn,
                        "SELECT x AS v, ord FROM unnest(ARRAY[10,20,30]) WITH ORDINALITY AS t(x, ord) ORDER BY ord");
                r.put("unnestWithOrdinalitySupported", true);
                r.put("unnestTestRows", unnestTest);
            } catch (Exception e) {
                r.put("unnestWithOrdinalitySupported", false);
                r.put("unnestError", e.getMessage());
            }

        } catch (Exception e) {
            log.error("[dii-probe] 失败 env={} table={}", effEnv, table, e);
            r.put("fatal", buildErrorChain(e));
        }
        return R.ok(r);
    }

    private static Object single(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getObject(1);
            return null;
        }
    }

    private static List<Map<String, Object>> queryRows(Connection conn, String sql, Object... params) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= cols; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * 查目标表的所有索引及列顺序（替代 psql 手工查询）。
     *
     * <p>示例：{@code GET /api/ai/dao-index/debug/indexes?env=dev&table=kapb_txn_log}
     *
     * <p>响应：该表上全部索引列表，每个索引含 indexName / columns（按定义顺序）/
     * unique / primary / indexType，方便你对照决定用哪几个字段来测"良/优"。
     */
    @GetMapping("/debug/indexes")
    public R<List<IndexMeta>> listIndexes(@RequestParam(required = false) String env,
                                          @RequestParam String table,
                                          @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        try {
            return R.ok(indexMetaService.getIndexes(env, table, refresh));
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 查询索引元数据失败 env={} table={}", env, table, e);
            return R.fail("查询索引失败：" + buildErrorChain(e));
        }
    }

    /**
     * 主动失效索引元数据缓存。用于在 DDL 变更或权限修复后立即让系统看到新结果。
     *
     * <p>不传 table 则清空全部缓存；传 table 只清这一张表。
     * <p>示例：
     * <pre>
     * POST /api/ai/dao-index/debug/indexes/invalidate                              （清全部）
     * POST /api/ai/dao-index/debug/indexes/invalidate?env=uat&table=foo            （清一张）
     * </pre>
     */
    @PostMapping("/debug/indexes/invalidate")
    public R<Map<String, Object>> invalidateIndexes(@RequestParam(required = false) String env,
                                                    @RequestParam(required = false) String table) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (table == null || table.isBlank()) {
            indexMetaService.invalidateAll();
            resp.put("invalidated", "ALL");
        } else {
            indexMetaService.invalidate(env, table);
            resp.put("invalidated", (env == null ? "default" : env) + ":" + table);
        }
        return R.ok(resp);
    }

    // ══════════════════════════════════════════════════════════════════
    //                    LLM 分析接口（Phase 2c）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 单条 SQL 的 LLM 分析（手动触发）。
     *
     * <p>同步调用——会等 LLM 返回（5~30s）。适合逐条调试，不建议批量用。
     * 批量请用 {@code POST /llm-enrich}。
     *
     * <p>成功 / 失败都会落库到 {@code dii_analysis_item.llm_*}，failed 记录在 {@code llm_error}。
     *
     * <p>示例：{@code POST /api/ai/dao-index/debug/llm-analyze/1234}
     */
    /**
     * 手动重跑请求体（前端"重新执行 AI 分析"模态框）。
     * sql 非空时按用户改过的 SQL 重新构造 prompt；model 决定模型路由。
     * body 整个为空时退化到旧调用语义。
     */
    public static class LlmAnalyzeRequest {
        public String sql;
        public String model;
    }

    /**
     * 异步触发单条 LLM 分析（"重新分析"按钮专用，前端轮询查状态）。
     *
     * <p>同步操作：把 llm_status 立刻打成 PENDING，前端首次 GET item 即可看到蒙版稳定显示。
     * <p>异步操作：把 LLM 分析任务交给 {@code diiBatchExecutor} 线程池，本接口立即返回 202。
     * <p>前端轮询 {@code GET /debug/analysis-items/{id}}，看到 llm_status ≠ PENDING 即停止轮询。
     */
    @PostMapping("/debug/llm-analyze/{itemId}/async")
    public R<Map<String, Object>> llmAnalyzeOneAsync(
            @PathVariable long itemId,
            @RequestBody(required = false) LlmAnalyzeRequest body) {
        String overrideSql = body == null ? null : body.sql;
        String modelKey    = body == null ? null : body.model;
        log.info("[dii-llm-api] POST /debug/llm-analyze/{}/async 异步触发 model={} overrideSql={}",
                itemId, modelKey, overrideSql == null ? "no" : "yes");
        SqlLlmAnalyzeService svc = llmAnalyzeServiceProvider.getIfAvailable();
        if (svc == null) {
            return R.fail("SqlLlmAnalyzeService 未装配（确认 ai.analysis.enabled=true 且 LlmClient 可用）");
        }
        // 1. 同步打 PENDING：保证前端首次轮询就看到蒙版
        int updated = itemDao.forceMarkLlmPending(itemId);
        if (updated == 0) {
            return R.fail("item 不存在 id=" + itemId);
        }
        // 2. 异步交线程池跑 LLM（必须通过代理 bean 调用，所以走 svc 而不是 this）
        svc.analyzeItemAsync(itemId, overrideSql, modelKey);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", true);
        data.put("itemId", itemId);
        data.put("status", "PENDING");
        data.put("hint", "请轮询 GET /debug/analysis-items/" + itemId + " 查看 llm_status");
        return R.ok(data);
    }

    /**
     * V16+：池行重新分析 LLM 异步接口（前端"重新分析"按钮在 nsql 行的入口）。
     *
     * <p>与 {@link #llmAnalyzeOneAsync} 同款异步语义，只是读写池表而非 item 表。
     * 前端 it.source==='nsql' 时调本接口；'odb' 时调上面那个。
     */
    @PostMapping("/debug/llm-analyze-pool/{poolId}/async")
    public R<Map<String, Object>> llmAnalyzePoolRowAsync(
            @PathVariable long poolId,
            @RequestBody(required = false) LlmAnalyzeRequest body) {
        String modelKey = body == null ? null : body.model;
        log.info("[dii-llm-api] POST /debug/llm-analyze-pool/{}/async 异步触发 model={}", poolId, modelKey);
        SqlLlmAnalyzeService svc = llmAnalyzeServiceProvider.getIfAvailable();
        if (svc == null) {
            return R.fail("SqlLlmAnalyzeService 未装配（确认 ai.analysis.enabled=true 且 LlmClient 可用）");
        }
        // 1. 同步打 PENDING 让前端首次轮询即蒙版稳定（同时清旧 llm_* 字段）
        int updated = poolDao.forceMarkLlmPending(poolId);
        if (updated == 0) {
            return R.fail("池行不存在 id=" + poolId);
        }
        // 2. 异步调 LLM；analyzePoolRow 会自动忽略 overrideSql（池行不支持改 SQL 重跑）
        svc.analyzePoolRowAsync(poolId, modelKey);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", true);
        data.put("poolId", poolId);
        data.put("status", "PENDING");
        data.put("hint", "请轮询 GET /sql-pool/" + poolId + " 查看 llm_status");
        return R.ok(data);
    }

    @PostMapping("/debug/llm-analyze/{itemId}")
    public R<SqlLlmResult> llmAnalyzeOne(
            @PathVariable long itemId,
            @RequestBody(required = false) LlmAnalyzeRequest body) {
        String overrideSql = body == null ? null : body.sql;
        String modelKey    = body == null ? null : body.model;
        log.info("[dii-llm-api] POST /debug/llm-analyze/{} 手动触发 model={} overrideSql={}",
                itemId, modelKey, overrideSql == null ? "no" : "yes");
        SqlLlmAnalyzeService svc = llmAnalyzeServiceProvider.getIfAvailable();
        if (svc == null) {
            log.warn("[dii-llm-api] SqlLlmAnalyzeService 未装配");
            return R.fail("SqlLlmAnalyzeService 未装配（确认 ai.analysis.enabled=true 且 LlmClient 可用）");
        }
        try {
            SqlLlmResult r = svc.analyzeItem(itemId, overrideSql, modelKey);
            log.info("[dii-llm-api] 手动分析完成 itemId={} error={} suggestions={}",
                    itemId, r.getError(),
                    r.getSuggestions() == null ? 0 : r.getSuggestions().size());
            return R.ok(r);
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[dii-llm-api] 单条分析 失败 itemId={}", itemId, e);
            return R.fail("LLM 分析失败：" + buildErrorChain(e));
        }
    }

    /**
     * 预览将要发给 LLM 的 Prompt，不真调 LLM（token 消耗 0）。
     * 用于调试语料是否齐全 / prompt 是否清晰。
     *
     * <p>示例：{@code POST /api/ai/dao-index/debug/llm-preview-prompt/1234}
     */
    @PostMapping("/debug/llm-preview-prompt/{itemId}")
    public R<Map<String, Object>> llmPreviewPrompt(@PathVariable long itemId) {
        SqlLlmAnalyzeService svc = llmAnalyzeServiceProvider.getIfAvailable();
        if (svc == null) {
            return R.fail("SqlLlmAnalyzeService 未装配");
        }
        try {
            com.axonlink.ai.dto.AnalysisPrompt prompt = svc.previewPrompt(itemId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("promptVersion", prompt.getPromptVersion());
            out.put("systemPromptLength", prompt.getSystemPrompt() == null ? 0 : prompt.getSystemPrompt().length());
            out.put("userPromptLength", prompt.getUserPrompt() == null ? 0 : prompt.getUserPrompt().length());
            out.put("systemPrompt", prompt.getSystemPrompt());
            out.put("userPrompt", prompt.getUserPrompt());
            return R.ok(out);
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[dii-llm] preview-prompt 失败 itemId={}", itemId, e);
            return R.fail("预览失败：" + buildErrorChain(e));
        }
    }

    /**
     * 批量 LLM 回填。异步跑，立即返回 "拉取到的候选数量"。
     *
     * <p>参数：
     * <ul>
     *   <li>{@code env} 可选：过滤环境</li>
     *   <li>{@code taskId} 可选：只处理某次 batch 任务的 items</li>
     *   <li>{@code onlyFailed} 默认 false：true 时只跑之前 FAILED 的 items（手动重试）</li>
     *   <li>{@code maxItems} 默认 1000：本次处理上限</li>
     * </ul>
     *
     * <p>示例：
     * <ul>
     *   <li>跑所有待处理：{@code POST /llm-enrich}</li>
     *   <li>跑某任务：{@code POST /llm-enrich?taskId=42}</li>
     *   <li>重试失败：{@code POST /llm-enrich?onlyFailed=true}</li>
     * </ul>
     */
    @PostMapping("/llm-enrich")
    public R<Map<String, Object>> llmEnrich(@RequestParam(required = false) String env,
                                            @RequestParam(required = false) Long taskId,
                                            @RequestParam(defaultValue = "false") boolean onlyFailed,
                                            @RequestParam(defaultValue = "1000") int maxItems) {
        log.info("[dii-llm-api] POST /llm-enrich env={} taskId={} onlyFailed={} maxItems={}",
                env, taskId, onlyFailed, maxItems);
        LlmEnrichService svc = llmEnrichServiceProvider.getIfAvailable();
        if (svc == null) {
            log.warn("[dii-llm-api] LlmEnrichService 未装配");
            return R.fail("LlmEnrichService 未装配");
        }

        long candidates = itemDao.findPendingLlmIds(env, taskId, onlyFailed, maxItems).size();
        log.info("[dii-llm-api] llm-enrich 候选 {} 条", candidates);
        if (candidates == 0) {
            return R.ok(Map.of("candidates", 0, "status", "NO_PENDING"));
        }
        svc.enrichAsync(env, taskId, onlyFailed, maxItems);
        log.info("[dii-llm-api] llm-enrich 已提交异步执行");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidates", candidates);
        out.put("env", env);
        out.put("taskId", taskId);
        out.put("onlyFailed", onlyFailed);
        out.put("maxItems", maxItems);
        out.put("status", "RUNNING_ASYNC");
        out.put("tip", "跑完后用 GET /debug/analysis-items?env=xxx&taskId=xxx 查看结果");
        return R.ok(out);
    }

    /**
     * 表维度的 LLM 建议聚合视图。
     *
     * <p>把某张表涉及的所有 SQL 的 LLM 建议展平 / 按 DDL 去重 / 按推荐次数排序。
     *
     * <p>DBA 打开这个视图就能看到"这张表要做的所有 DDL 动作"，一次性准备执行脚本。
     *
     * <p>示例：{@code GET /api/ai/dao-index/debug/table-advice-rollup?env=uat&table=kagl_acctg_rung}
     */
    @GetMapping("/debug/table-advice-rollup")
    public R<Map<String, Object>> tableAdviceRollup(@RequestParam String env,
                                                    @RequestParam String table) {
        List<Map<String, Object>> rows = itemDao.tableAdviceRollup(env, table);
        // 按 DDL 去重聚合
        Map<String, AdviceAgg> byDdl = new LinkedHashMap<>();
        List<Map<String, Object>> perSqlAdvice = new ArrayList<>();

        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Map<String, Object> row : rows) {
            String suggestionsJson = (String) row.get("llm_suggestions_json");
            if (suggestionsJson == null || suggestionsJson.isBlank()) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode arr = om.readTree(suggestionsJson);
                if (!arr.isArray()) continue;
                for (com.fasterxml.jackson.databind.JsonNode s : arr) {
                    String scope = s.path("scope").asText("");
                    String type = s.path("type").asText("");
                    String ddl = s.path("ddl").asText(null);
                    // 只聚合表级 DDL 建议
                    if (!"TABLE".equals(scope) || ddl == null || ddl.isBlank()) continue;
                    AdviceAgg agg = byDdl.computeIfAbsent(ddl, k -> {
                        AdviceAgg a = new AdviceAgg();
                        a.ddl = ddl;
                        a.type = type;
                        a.reasonSamples = new ArrayList<>();
                        a.affectedItemIds = new ArrayList<>();
                        return a;
                    });
                    agg.recommendedBy++;
                    Object itemIdObj = row.get("id");
                    if (itemIdObj instanceof Number) agg.affectedItemIds.add(((Number) itemIdObj).longValue());
                    String reason = s.path("reason").asText(null);
                    if (reason != null && agg.reasonSamples.size() < 3
                            && !agg.reasonSamples.contains(reason)) {
                        agg.reasonSamples.add(reason);
                    }
                    if (s.has("exceedsLengthLimit") && s.get("exceedsLengthLimit").asBoolean(false)) {
                        agg.lengthRisk = true;
                    }
                }
            } catch (Exception e) {
                log.debug("[dii-llm] 聚合 item id={} 失败: {}", row.get("id"), e.getMessage());
            }
            // 顺便汇总每条 SQL 自己的摘要
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("itemId", row.get("id"));
            brief.put("sqlHash", row.get("sql_hash"));
            brief.put("classFqn", row.get("class_fqn"));
            brief.put("overallRating", row.get("overall_rating"));
            brief.put("runtimeRating", row.get("runtime_rating"));
            brief.put("llmSummary", row.get("llm_summary"));
            perSqlAdvice.add(brief);
        }

        // 按推荐次数排序
        List<AdviceAgg> sorted = new ArrayList<>(byDdl.values());
        sorted.sort((a, b) -> b.recommendedBy - a.recommendedBy);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("env", env);
        payload.put("table", table);
        payload.put("totalSqls", rows.size());
        payload.put("aggregatedDdlAdvice", sorted);
        payload.put("perSqlAdvice", perSqlAdvice);
        return R.ok(payload);
    }

    /** 仅用于 rollup 返回结构。 */
    public static class AdviceAgg {
        public String type;
        public String ddl;
        public int recommendedBy;
        public boolean lengthRisk;
        public List<String> reasonSamples;
        public List<Long> affectedItemIds;

        public String getType() { return type; }
        public String getDdl() { return ddl; }
        public int getRecommendedBy() { return recommendedBy; }
        public boolean isLengthRisk() { return lengthRisk; }
        public List<String> getReasonSamples() { return reasonSamples; }
        public List<Long> getAffectedItemIds() { return affectedItemIds; }
    }

    /**
     * 手动清空 DDL / 采样相关缓存。
     *
     * <p>DBA 修复 DDL（新建表、补字段、改类型）后不想等 5 分钟短 TTL 自愈，
     * 可以调这个接口立刻清缓存，下一次 EXPLAIN 就能拿到新 schema。
     *
     * <p>清的是 {@link TableMetadataService} + {@link ColumnSampleService} 两个服务的内存缓存，
     * 不影响落库数据。
     *
     * <p>示例：{@code POST /api/ai/dao-index/debug/invalidate-cache}
     */
    @PostMapping("/debug/invalidate-cache")
    public R<Map<String, Object>> invalidateCache() {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            TableMetadataService tms = tableMetadataServiceProvider.getIfAvailable();
            if (tms != null) {
                tms.invalidateAll();
                r.put("tableMetadataCache", "cleared");
            }
            ColumnSampleService css = columnSampleServiceProvider.getIfAvailable();
            if (css != null) {
                css.invalidateAll();
                r.put("columnSampleCache", "cleared");
            }
            r.put("indexMetaCache", "cleared");
            indexMetaService.invalidateAll();
            return R.ok(r);
        } catch (Exception e) {
            log.error("[dii] 清缓存失败", e);
            return R.fail("清缓存失败：" + buildErrorChain(e));
        }
    }

    /**
     * 扫描预览：不跑 batch，只调用扫描器，返回抽到的 SQL 候选数量和前 N 条样本。
     * 用于定位"为什么 total_sqls=0"这类配置问题。
     *
     * <p>示例：{@code GET /api/ai/dao-index/debug/scan-preview?sample=5}
     */
    @GetMapping("/debug/scan-preview")
    public R<Map<String, Object>> scanPreview(@RequestParam(defaultValue = "5") int sample) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            long start = System.currentTimeMillis();
            java.util.List<SqlCandidate> all = scanner.scanAll(0);
            r.put("total", all.size());
            r.put("elapsedMs", System.currentTimeMillis() - start);

            // 按 project / class 汇总
            Map<String, Integer> byProject = new LinkedHashMap<>();
            Map<String, Integer> byClass = new LinkedHashMap<>();
            for (SqlCandidate c : all) {
                byProject.merge(c.getProjectName() == null ? "(unknown)" : c.getProjectName(), 1, Integer::sum);
                byClass.merge(c.getClassFqn() == null ? "(unknown)" : c.getClassFqn(), 1, Integer::sum);
            }
            r.put("byProject", byProject);
            r.put("byClassTop10", byClass.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10).collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, Map.Entry::getValue,
                            (x, y) -> x, LinkedHashMap::new)));

            // 前 N 条样本
            int take = Math.min(Math.max(sample, 0), 50);
            java.util.List<Map<String, Object>> samples = new java.util.ArrayList<>();
            for (int i = 0; i < take && i < all.size(); i++) {
                SqlCandidate c = all.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("classFqn", c.getClassFqn());
                row.put("sourceFile", c.getSourceFile());
                row.put("sql", c.getSql() == null || c.getSql().length() <= 200
                        ? c.getSql()
                        : c.getSql().substring(0, 200) + "...");
                samples.add(row);
            }
            r.put("samples", samples);
            return R.ok(r);
        } catch (Exception e) {
            log.error("[dii-scan-preview] 失败", e);
            r.put("error", buildErrorChain(e));
            return R.ok(r);
        }
    }

    /**
     * 手动触发一次批量巡检（写入 dii_analysis_task + 异步执行）。
     *
     * <p>受口令保护：请求头 {@code X-DII-Trigger-Token} 必须匹配
     * {@code dao-index-analysis.batch-trigger.token} 配置；
     * 配置为空（含全空白）时跳过校验（仅开发环境）。
     *
     * <p>错误返回真正的 HTTP 401，方便浏览器 / 网关 / 监控统一识别。
     *
     * <p>示例：
     * <pre>{@code
     * curl -X POST 'http://host/api/ai/dao-index/batch-analyze?env=uat' \
     *      -H 'X-DII-Trigger-Token: sunline300348'
     * }</pre>
     *
     * <p>单条 SQL 的分析失败不会中断批量；失败信息落到 {@code dii_analysis_item.status='FAILED'}。
     */
    @PostMapping("/batch-analyze")
    public ResponseEntity<R<Map<String, Object>>> triggerBatch(
            @RequestParam(required = false) String env,
            @RequestParam(required = false, defaultValue = "manual") String owner,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            jakarta.servlet.http.HttpServletRequest request) {
        // ① 口令校验：配置为空（含全空白）= 跳过；非空 + 不匹配 → 真 HTTP 401
        String expected = props.getBatchTrigger().getToken();
        if (expected != null && !expected.trim().isEmpty()) {
            if (token == null || !expected.equals(token)) {
                // 审计日志：防爆破 / 排查配置漂移
                log.warn("[dii-batch] 触发口令校验失败 remoteAddr={} env={} owner={} hasToken={}",
                        request.getRemoteAddr(), env, owner, token != null);
                R<Map<String, Object>> body = R.fail("口令错误");
                body.setCode(401);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
            }
        }
        // ② 走原有 startAsync 流程
        try {
            String effEnv = (env == null || env.isBlank())
                    ? targetRegistry.getDefaultEnv() : env;
            long taskId = batchInspectionService.startAsync(effEnv, "MANUAL", owner);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("env", effEnv);
            payload.put("status", "RUNNING");
            payload.put("pollUrl", "/api/ai/dao-index/batch-tasks/" + taskId);
            return ResponseEntity.ok(R.ok(payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
        } catch (IllegalStateException e) {
            // 「一天一次·覆盖式」：当天该 env 已有 RUNNING 巡检在跑 → 预期内的业务拒绝，
            // 不是系统故障：用 WARN（不打 ERROR 堆栈，避免噪声），返回非 200（HTTP 409 +
            // body.code=500 + 中文 message），前端按 code!==200 显示「触发失败：…」。
            log.warn("[dii-batch] 触发被拒绝 env={} owner={}：{}", env, owner, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(R.<Map<String, Object>>fail(e.getMessage()));
        } catch (Exception e) {
            log.error("[dii-batch] 触发批量失败", e);
            return ResponseEntity.internalServerError()
                    .body(R.<Map<String, Object>>fail("触发批量失败：" + buildErrorChain(e)));
        }
    }

    /**
     * 查任务进度。
     * <p>示例：{@code GET /api/ai/dao-index/batch-tasks/7}
     */
    @GetMapping("/batch-tasks/{taskId}")
    public R<Map<String, Object>> getBatchTask(@PathVariable long taskId) {
        Map<String, Object> row = taskDao.findById(taskId);
        if (row == null) return R.fail("未找到 taskId=" + taskId);
        return R.ok(row);
    }

    /**
     * 列出最近的 N 条批量巡检任务，每行带回 5 项 dii_analysis_item 聚合统计。
     *
     * <p>支持按 env 与 status 过滤；前端拉"最新一条 DONE 任务"用：
     * {@code GET /batch-tasks?env=uat&status=DONE&limit=1}（兼容老调用）。
     *
     * <p>响应包装为 {@code {total, items}}，其中 total 用相同过滤条件单独
     * SELECT COUNT(*)，给前端分页器用。
     *
     * <p>status 支持特殊值 {@code RUNNING_OR_PENDING}，DAO 翻译为 IN 子句。
     */
    @GetMapping("/batch-tasks")
    public R<Map<String, Object>> listBatchTasks(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status) {
        List<Map<String, Object>> items = taskDao.list(limit, offset, env, status);
        long total = taskDao.countAll(env, status);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("items", items);
        return R.ok(payload);
    }

    /**
     * 查单条分析历史详情。
     *
     * <p>示例：{@code GET /api/ai/dao-index/debug/analysis-items/123}
     */
    @GetMapping("/debug/analysis-items/{itemId}")
    public R<Map<String, Object>> getAnalysisItem(@PathVariable long itemId) {
        try {
            Map<String, Object> row = itemDao.loadById(itemId);
            if (row == null) {
                return R.fail("未找到 itemId=" + itemId);
            }
            return R.ok(row);
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 查单条失败 itemId={}", itemId, e);
            return R.fail("查询失败：" + buildErrorChain(e));
        }
    }

    /**
     * 按条件分页查历史列表。
     *
     * <p>示例：
     * <ul>
     *   <li>{@code GET /api/ai/dao-index/debug/analysis-items?env=uat}</li>
     *   <li>{@code GET /api/ai/dao-index/debug/analysis-items?env=uat&rating=POOR}</li>
     *   <li>{@code GET /api/ai/dao-index/debug/analysis-items?table=kagl_acctg_rung}</li>
     *   <li>{@code GET /api/ai/dao-index/debug/analysis-items?taskId=7&limit=100}</li>
     * </ul>
     *
     * <p>响应：{@code {total: N, items: [...]}}。单次 limit 最大 500。
     */
    @GetMapping("/debug/analysis-items")
    public R<Map<String, Object>> listAnalysisItems(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String rating,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        try {
            long total = itemDao.count(env, rating, table, taskId);
            java.util.List<Map<String, Object>> items = itemDao.search(env, rating, table, taskId, limit, offset);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("total", total);
            payload.put("limit", limit);
            payload.put("offset", offset);
            payload.put("items", items);
            return R.ok(payload);
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 查列表失败", e);
            return R.fail("查询失败：" + buildErrorChain(e));
        }
    }

    /**
     * "SQL 分析 - 问题列表"视图接口（V11）。
     *
     * <p>只返回下面两类"真正有料"的分析行：
     * <ol>
     *   <li>EXPLAIN 数据库直接报错（{@code explain_error} 非空）</li>
     *   <li>LLM 已有终态结论（{@code llm_status ∈ {DONE, FAILED}}）</li>
     * </ol>
     *
     * <p>相比 {@code /debug/analysis-items}，额外带上以下前端列表/建议列展示用的长字段：
     * {@code sql_text}, {@code explain_error}, {@code llm_status}, {@code llm_error},
     * {@code llm_summary}, {@code llm_findings_json}, {@code llm_suggestions_json}。
     *
     * <p>示例：
     * {@code GET /api/ai/dao-index/debug/analysis-items-issues?env=uat&taskId=7&limit=50}
     *
     * <p>响应结构与 {@code /debug/analysis-items} 一致：{@code {total, limit, offset, items: [...]}}。
     */
    @GetMapping("/debug/analysis-items-issues")
    public R<Map<String, Object>> listAnalysisItemIssues(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false, defaultValue = "plain") String whitelistScope,
            @RequestParam(required = false) String wlStatus,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        try {
            // V14：合并展示
            //   - item（来源 'odb'）：按 task 过滤；保持既有行为
            //   - pool（来源 'nsql'）：按 env 过滤（池不挂任务），首页拉取时一并返回
            // 分页策略：item 行优先，pool 行追加在后；total = 两者之和。
            // 前端 SQL 分析页本就分批拉到全量再合并展示，分页"按 id DESC + 各自源排序"够用。
            long itemTotal = itemDao.countIssuesOnly(env, taskId, whitelistScope, wlStatus);
            long poolTotal = poolDao.countAsIssues(env, whitelistScope, wlStatus);
            long total = itemTotal + poolTotal;

            java.util.List<Map<String, Object>> merged = new java.util.ArrayList<>();
            int itemTake = Math.min(limit, (int) Math.max(0, itemTotal - offset));
            if (offset < itemTotal && itemTake > 0) {
                java.util.List<Map<String, Object>> items =
                        itemDao.searchIssuesOnly(env, taskId, whitelistScope, wlStatus, itemTake, offset);
                for (Map<String, Object> row : items) {
                    row.put("source", "odb");
                }
                merged.addAll(items);
            }
            // 剩余配额由池补足
            int remain = limit - merged.size();
            if (remain > 0) {
                int poolOffset = (int) Math.max(0, offset - itemTotal);
                java.util.List<Map<String, Object>> poolRows =
                        poolDao.searchAsIssues(env, whitelistScope, wlStatus, remain, poolOffset);
                // searchAsIssues 已经把 source='nsql' 写进 SELECT 列；防御性兜底
                for (Map<String, Object> row : poolRows) {
                    row.putIfAbsent("source", "nsql");
                }
                merged.addAll(poolRows);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("total", total);
            payload.put("itemTotal", itemTotal);
            payload.put("poolTotal", poolTotal);
            payload.put("limit", limit);
            payload.put("offset", offset);
            payload.put("items", merged);
            return R.ok(payload);
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 查问题列表失败 env={} taskId={}", env, taskId, e);
            return R.fail("查询失败：" + buildErrorChain(e));
        }
    }

    /**
     * "SQL 分析 - 问题列表" 4 个 KPI 统计：总数 / DB 报错 / AI 完成 / AI 失败。
     *
     * <p>一条 SQL 同时统计 4 项，前端不再需要全量拉取 items 后本地 group。
     *
     * <p>返回字段：{@code total / explainError / llmFindings / llmError}（均为 long）。
     */
    @GetMapping("/debug/analysis-items-issues/stats")
    public R<Map<String, Long>> getIssuesStats(@RequestParam(required = false) String env,
                                               @RequestParam(required = false) Long taskId,
                                               @RequestParam(required = false, defaultValue = "plain") String whitelistScope,
                                               @RequestParam(required = false) String wlStatus) {
        try {
            // V14：item 与 pool 各自 KPI 直接相加（同语义字段：total / explainError / llmFindings / llmPending / llmError）
            Map<String, Long> itemStats = itemDao.getIssuesStats(env, taskId, whitelistScope, wlStatus);
            Map<String, Long> poolStats = poolDao.getIssuesStats(env, whitelistScope, wlStatus);
            Map<String, Long> merged = new LinkedHashMap<>();
            for (String k : new String[]{"total", "explainError", "llmFindings", "llmPending", "llmError"}) {
                long sum = itemStats.getOrDefault(k, 0L) + poolStats.getOrDefault(k, 0L);
                merged.put(k, sum);
            }
            // 便于前端展示 "33 ( odb 22 + nsql 11 )" 这类细拆，再附带源拆分字段
            merged.put("itemTotal", itemStats.getOrDefault("total", 0L));
            merged.put("poolTotal", poolStats.getOrDefault("total", 0L));
            return R.ok(merged);
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 查 issues stats 失败 env={} taskId={}", env, taskId, e);
            return R.fail("查询失败：" + buildErrorChain(e));
        }
    }

    /**
     * "SQL 分析 - 问题列表"全量 Excel 导出接口。
     *
     * <p>导出 {@link DiiAnalysisItemDao#searchIssuesOnly} 在指定 env+taskId 下的全部行
     * （内部分批 500 条循环拉取，避免单次内存爆炸）。
     *
     * <p>下载文件名：{@code sql-analysis-issues-<task_no>-<timestamp>.xlsx}
     *
     * <p>示例：{@code GET /api/ai/dao-index/debug/analysis-items-issues/export?env=uat&taskId=7}
     */
    @GetMapping("/debug/analysis-items-issues/export")
    public ResponseEntity<byte[]> exportAnalysisItemIssues(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) Long taskId) {
        try {
            // 1. 全量拉取（分批累加，单次 500 条）
            List<Map<String, Object>> all = collectAllIssues(env, taskId);

            // 2. 取 task_no（用于文件名 + 表格内列）
            String taskNo = "";
            if (taskId != null) {
                Map<String, Object> task = taskDao.findById(taskId);
                if (task != null && task.get("task_no") != null) {
                    taskNo = String.valueOf(task.get("task_no"));
                }
            }

            // 3. 构建 workbook
            byte[] xlsx = buildIssuesWorkbook(all, taskNo);

            // 4. 组装 HTTP 响应（attachment 下载）
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String namePart = (taskNo == null || taskNo.isEmpty())
                    ? (env == null ? "all" : env)
                    : taskNo;
            String filename = "sql-analysis-issues-" + namePart + "-" + ts + ".xlsx";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(filename, StandardCharsets.UTF_8)
                    .build());
            return new ResponseEntity<>(xlsx, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("[dii-sqlinspect] 导出失败 env={} taskId={}", env, taskId, e);
            String msg = "导出失败：" + buildErrorChain(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(msg.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 分页累加拉取所有"有问题"的行；最多 100k 兜底，避免极端情况。
     *
     * <p>使用 keyset 分页（{@code id < :afterId}）而不是 OFFSET：
     * 当导出过程中有新行写入或 {@code llm_status} 由 PENDING 变 DONE/FAILED 时，
     * OFFSET 会出现重复 / 漏行；keyset 以"上一批最小 id"为游标向下翻，
     * 不受新数据影响，结果稳定。
     */
    private List<Map<String, Object>> collectAllIssues(String env, Long taskId) {
        List<Map<String, Object>> all = new ArrayList<>();
        int batchSize = 500;
        Long afterId = null; // 首批不带游标；之后取上一批最后一行的 id（即最小 id）
        while (true) {
            List<Map<String, Object>> batch = itemDao.searchIssuesOnlyAfter(env, taskId, afterId, "plain", null, batchSize);
            if (batch == null || batch.isEmpty()) break;
            all.addAll(batch);
            if (batch.size() < batchSize) break;
            // 由于 ORDER BY id DESC，本批末尾就是最小 id，作为下一批游标
            Object lastIdObj = batch.get(batch.size() - 1).get("id");
            if (lastIdObj == null) break;
            afterId = ((Number) lastIdObj).longValue();
            if (all.size() >= 100_000) {
                log.warn("[dii-sqlinspect] 导出超过 10w 行，已截断 env={} taskId={}", env, taskId);
                break;
            }
        }

        // V2：导出要与页面展示口径一致——页面是 item + pool 合并展示，导出也要带上 pool（nsql）行。
        // 池行 searchAsIssues 已投影成与 item 同字段集（含 source='nsql' / named_sql），直接追加。
        // 池不挂任务，按 env 拉（与列表合并接口同口径）；offset 分页（池量小，足够）。
        try {
            int poolOffset = 0;
            while (all.size() < 100_000) {
                List<Map<String, Object>> poolBatch = poolDao.searchAsIssues(env, "plain", null, batchSize, poolOffset);
                if (poolBatch == null || poolBatch.isEmpty()) break;
                all.addAll(poolBatch);
                if (poolBatch.size() < batchSize) break;
                poolOffset += batchSize;
            }
        } catch (Exception e) {
            log.warn("[dii-sqlinspect] 导出追加池行失败（不影响 item 导出）env={}: {}", env, e.getMessage());
        }
        return all;
    }

    /** project_name → 业务领域中文名（与前端 DAO_DOMAIN_OPTIONS 对齐）。 */
    private static String mapDomainLabel(String projectName) {
        if (projectName == null) return "其他";
        String p = projectName.toLowerCase();
        if (p.contains("dept-bcc")) return "存款";
        if (p.contains("loan-bcc")) return "贷款";
        if (p.contains("comm-bcc")) return "公共";
        if (p.contains("sett-bcc")) return "结算";
        return "其他";
    }

    /** 把 llm_findings_json 解析后拼成可读多行字符串。 */
    private static String formatFindings(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return "";
        try {
            List<Map<String, Object>> arr = mapper.readValue(json, new TypeReference<>() {});
            if (arr == null || arr.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                Map<String, Object> f = arr.get(i);
                if (i > 0) sb.append("\n");
                sb.append("[").append(asString(f.get("severity"), "LOW")).append("] ");
                sb.append(asString(f.get("type"), ""));
                String desc = asString(f.get("description"), "");
                if (!desc.isEmpty()) sb.append(" - ").append(desc);
            }
            return sb.toString();
        } catch (Exception e) {
            return json; // 解析失败返回原 JSON
        }
    }

    /** 把 llm_suggestions_json 解析后拼成可读多行字符串。 */
    private static String formatSuggestions(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return "";
        try {
            List<Map<String, Object>> arr = mapper.readValue(json, new TypeReference<>() {});
            if (arr == null || arr.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                Map<String, Object> s = arr.get(i);
                if (i > 0) sb.append("\n\n");
                String scope = asString(s.get("scope"), "SQL");
                sb.append("【").append("TABLE".equalsIgnoreCase(scope) ? "表级" : "SQL 级").append("】");
                sb.append(asString(s.get("type"), "")).append("\n");
                String reason = asString(s.get("reason"), "");
                if (!reason.isEmpty()) sb.append("理由：").append(reason).append("\n");
                String ddl = asString(s.get("ddl"), "");
                if (!ddl.isEmpty()) sb.append("DDL：").append(ddl).append("\n");
                String newSql = asString(s.get("newSql"), "");
                if (!newSql.isEmpty()) sb.append("新 SQL：").append(newSql);
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return json;
        }
    }

    private static String asString(Object obj, String fallback) {
        return obj == null ? fallback : String.valueOf(obj);
    }

    /** 构建 Excel workbook（19 列）。 */
    private byte[] buildIssuesWorkbook(List<Map<String, Object>> rows, String taskNo) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("SQL 分析问题列表");

            // ── 表头样式：深灰底白字加粗居中 ──
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // ── 数据样式：自动换行 + 顶端对齐 ──
            CellStyle wrapStyle = wb.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

            // ── 列定义（V2：加「来源」odb/nsql + 「命名SQL」列，与页面展示对齐）──
            String[] headers = {
                    "序号", "来源", "任务编号", "创建时间", "领域", "工程",
                    "命名SQL", "表名", "SQL 类型", "SQL 全文", "评级",
                    "EXPLAIN 错误", "AI 状态", "AI 摘要",
                    "AI 错误", "AI 发现的问题", "AI 修改建议",
                    "AI 模型", "AI 提示词版本", "AI 耗时(ms)", "AI 执行时间"
            };
            // POI 单位 = 1/256 字符宽
            int[] widths = {
                    6, 8, 30, 20, 8, 18,
                    32, 28, 10, 60, 8,
                    50, 12, 50,
                    35, 60, 60,
                    22, 16, 12, 22
            };

            Row hr = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            // 冻结表头 + 启用筛选
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, Math.max(0, rows.size()), 0, headers.length - 1));

            // ── 数据行 ──
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                Row er = sheet.createRow(i + 1);
                int col = 0;

                setNum(er, col++, i + 1);
                // V2：来源 odb/nsql（池行 task_no 用池自身标记，不套当前 taskNo）
                String src = asString(row.get("source"), "odb");
                setText(er, col++, src);
                setText(er, col++, "nsql".equals(src) ? "（SQL 池导入）" : taskNo);
                setText(er, col++, asString(row.get("created_at"), ""));
                setText(er, col++, mapDomainLabel(asString(row.get("project_name"), "")));
                setText(er, col++, asString(row.get("project_name"), ""));
                // V2：命名SQL（nsql 行有值；odb 行该列为空）——池 DAO 把 named_sql 映到 class_fqn
                setText(er, col++, "nsql".equals(src) ? asString(row.get("class_fqn"), "") : "");
                setText(er, col++, asString(row.get("involved_tables"), ""));
                setText(er, col++, asString(row.get("sql_kind"), ""));
                setWrap(er, col++, asString(row.get("sql_text"), ""), wrapStyle);
                setText(er, col++, asString(row.get("overall_rating"), ""));
                setWrap(er, col++, asString(row.get("explain_error"), ""), wrapStyle);
                setText(er, col++, asString(row.get("llm_status"), ""));
                setWrap(er, col++, asString(row.get("llm_summary"), ""), wrapStyle);
                setWrap(er, col++, asString(row.get("llm_error"), ""), wrapStyle);
                setWrap(er, col++, formatFindings(asString(row.get("llm_findings_json"), ""), mapper), wrapStyle);
                setWrap(er, col++, formatSuggestions(asString(row.get("llm_suggestions_json"), ""), mapper), wrapStyle);
                setText(er, col++, asString(row.get("llm_model"), ""));
                setText(er, col++, asString(row.get("llm_prompt_version"), ""));
                setText(er, col++, asString(row.get("llm_elapsed_ms"), ""));
                setText(er, col++, asString(row.get("llm_called_at"), ""));
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void setNum(Row r, int col, double v) {
        Cell c = r.createCell(col);
        c.setCellValue(v);
    }

    private static void setText(Row r, int col, String v) {
        Cell c = r.createCell(col);
        c.setCellValue(v == null ? "" : v);
    }

    private static void setWrap(Row r, int col, String v, CellStyle style) {
        Cell c = r.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    /**
     * 单条 SQL 的索引命中评级接口（Phase 2a.1）。
     *
     * <p>请求体：
     * <pre>{
     *   "sql": "SELECT * FROM t_acct_log WHERE acct_no = ?",
     *   "env": "dev"
     * }</pre>
     *
     * <p>返回：每张表的规则引擎评级（差/良/优）+ 命中的索引名 + 全部候选索引。
     * 本阶段暂不调用 LLM、不落库、不跑 EXPLAIN。
     */
    @PostMapping("/debug/analyze-sql")
    public R<SqlInspectionResult> analyzeSql(@RequestBody SqlInspectionRequest request) {
        try {
            return R.ok(sqlInspectionService.inspect(request));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[sqlinspect] 分析失败 env={} sql={}",
                    request == null ? null : request.getEnv(),
                    request == null ? null : request.getSql(), e);
            return R.fail("SQL 分析失败：" + buildErrorChain(e));
        }
    }

    /** 把异常链展开成可读字符串，包含每层的类名 + message。 */
    private String buildErrorChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 6) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName())
              .append(": ")
              .append(cur.getMessage() == null ? "(no message)" : cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }
}
