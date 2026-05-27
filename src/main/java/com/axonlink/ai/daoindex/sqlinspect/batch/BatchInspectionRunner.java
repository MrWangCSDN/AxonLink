package com.axonlink.ai.daoindex.sqlinspect.batch;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlCandidate;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionRequest;
import com.axonlink.ai.daoindex.sqlinspect.llm.LlmEnrichService;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisTaskDao;
import com.axonlink.ai.daoindex.sqlinspect.scan.SqlSourceScanner;
import com.axonlink.ai.daoindex.sqlinspect.service.SqlInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量巡检的<b>真正异步执行体</b>。
 *
 * <h3>为什么要单独拆这个 bean（关键设计）</h3>
 * <p>Spring 的 {@code @Async} 是靠<b>动态代理</b>实现的：只有「从外部 / 经过代理的 bean
 * 引用」去调用 {@code @Async} 方法，才会被切面拦截、丢到线程池里跑。
 * 如果在同一个类内部用 {@code this.xxx()} 自调用（self-invocation），
 * 调用根本不经过代理，{@code @Async} <b>完全失效</b>，方法会在调用方线程里同步跑完。
 *
 * <p>历史 bug 就是这样：{@code BatchInspectionService.startAsync} 内部
 * {@code this.runAsync(...)} 自调用，导致 422 条 EXPLAIN 循环在 HTTP 请求线程上
 * 同步跑完，手动触发接口卡几分钟。
 *
 * <p>解决办法：把扫描 + 循环搬到这个独立 {@code @Component} 里。
 * {@code BatchInspectionService} 注入本 bean 后调用 {@link #runAsync}，
 * 属于<b>跨 bean 调用</b>，必然经过 Spring 代理 → {@code @Async} 真正生效。
 *
 * <h3>为什么 scanAll 也要移到这里（异步内）</h3>
 * <p>{@code scanner.scanAll(0)} 会扫描所有项目源码抽 SQL，是个重活（数秒级）。
 * 原来它在 {@code startAsync} 的同步段执行，即便循环异步了，HTTP 线程仍要等扫描完
 * 才能拿到 taskId 返回。所以把 scanAll 一并移进本异步方法的最前面：
 * 建 task 记录（status=RUNNING、total=0）→ 立即返回 taskId →
 * 扫描 + 去重 + 回填 total_sqls + 422 循环全部在 {@code diiBatchExecutor} 线程跑。
 *
 * <h3>健壮性</h3>
 * <ul>
 *   <li>扫描阶段异常：catch 后 {@code taskDao.markFailed}，任务标记 FAILED，绝不静默吞</li>
 *   <li>单条 SQL 异常：独立 try-catch-Throwable，单条失败不中断整个任务</li>
 *   <li>EXPLAIN+链式 LLM 全程结束（无论正常/异常）：{@code runAsync} 的 {@code finally}
 *       里 {@code markDone}，任务态全程保持 RUNNING 到此才落终态，保证不会卡 RUNNING</li>
 * </ul>
 */
@Component
public class BatchInspectionRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchInspectionRunner.class);

    private final SqlSourceScanner scanner;
    private final DiiAnalysisTaskDao taskDao;
    private final DiiAnalysisItemDao itemDao;
    private final SqlInspectionService inspectionService;
    private final ObjectProvider<LlmEnrichService> llmEnrichServiceProvider;
    private final DaoIndexAnalysisProperties props;
    // V16+：池行巡检（item 完后调）
    private final ObjectProvider<PoolBatchInspector> poolBatchInspectorProvider;

    public BatchInspectionRunner(SqlSourceScanner scanner,
                                 DiiAnalysisTaskDao taskDao,
                                 DiiAnalysisItemDao itemDao,
                                 SqlInspectionService inspectionService,
                                 ObjectProvider<LlmEnrichService> llmEnrichServiceProvider,
                                 DaoIndexAnalysisProperties props,
                                 ObjectProvider<PoolBatchInspector> poolBatchInspectorProvider) {
        this.scanner = scanner;
        this.taskDao = taskDao;
        this.itemDao = itemDao;
        this.inspectionService = inspectionService;
        this.llmEnrichServiceProvider = llmEnrichServiceProvider;
        this.props = props;
        this.poolBatchInspectorProvider = poolBatchInspectorProvider;
    }

    /**
     * 异步执行核心：扫描 SQL → 按 hash 去重 → 回填 total_sqls → 逐条巡检。
     *
     * <p><b>必须由别的 bean（{@link BatchInspectionService}）跨 bean 调用</b>，
     * 这样才会经过 Spring 代理让 {@code @Async} 生效；切勿在本类内部自调用。
     *
     * <p>task 记录已经由调用方在同步段建好（status=RUNNING、total=0），
     * 这里只负责把重活跑完并维护进度/终态。
     *
     * @param taskId      已创建的任务 id
     * @param env         目标库 env
     */
    @Async("diiBatchExecutor")
    public void runAsync(long taskId, String env) {
        MDC.put("diiTaskId", String.valueOf(taskId));
        try {
            // ═════ 1. 扫描 SQL 候选（重活，已从 HTTP 线程移到这里）═════
            List<SqlCandidate> candidates;
            try {
                candidates = scanner.scanAll(0);
            } catch (Throwable t) {
                // 扫描阶段失败属于「任务级」故障：标记整个任务 FAILED，不静默吞
                log.error("[dii-batch] 扫描阶段异常 taskId={}：{}", taskId, t.getMessage(), t);
                taskDao.markFailed(taskId, "扫描阶段异常：" + t.getMessage());
                return;
            }

            // ═════ 2. 任务内按 sql_hash 去重（同 SQL 在多个文件被扫到多份时只算一次）═════
            Set<String> seenHashes = new HashSet<>();
            candidates.removeIf(c -> !seenHashes.add(sha256(c.getSql())));

            // ═════ 3. 回填真实候选总数（建 task 时填的是 0）═════
            taskDao.updateTotal(taskId, candidates.size());
            log.info("[dii-batch] 任务 id={} 异步启动，env={} 候选 SQL {} 条（已按 hash 去重）",
                    taskId, env, candidates.size());

            // ═════ 4. 逐条巡检（所有异常都被捕获，单条失败绝不中断整个任务）═════
            int[] c = {0, 0, 0};
            try {
                c = runLoop(taskId, env, candidates);

                // 增强 v5：批量 EXPLAIN 跑完，按开关同步链式触发 LLM 回填。
                // 本线程已是 diiBatchExecutor 异步线程（非 HTTP 线程），直接调同步 enrich()；
                // per-item 仍在 diiLlmExecutor 并发。LLM 整体失败只 log，不影响巡检任务终态。
                if (props.getBatch().isAutoLlmAfterBatch()) {
                    LlmEnrichService svc = llmEnrichServiceProvider.getIfAvailable();
                    if (svc != null) {
                        int maxItems = props.getSchedule().getDailyLlmMaxItems();
                        try {
                            log.info("[dii-batch] 任务 id={} EXPLAIN 完成，链式触发 LLM 回填 taskId 限定 maxItems={}",
                                    taskId, maxItems);
                            svc.enrich(env, taskId, false, maxItems);
                        } catch (Throwable t) {
                            log.error("[dii-batch] 任务 id={} 链式 LLM 回填异常（不影响巡检任务终态）：{}",
                                    taskId, t.getMessage(), t);
                        }
                    } else {
                        log.warn("[dii-batch] 任务 id={} LlmEnrichService 未装配，跳过链式 LLM（检查 ai.analysis 配置）",
                                taskId);
                    }
                } else {
                    log.info("[dii-batch] 任务 id={} auto-llm-after-batch=false，跳过链式 LLM（保持两步管线）", taskId);
                }

                // V16+：item 阶段（含链式 LLM）跑完后，串接池行巡检
                //   - 白名单池行：跳过
                //   - 非白名单 + 非 SeqScan：物理 DELETE
                //   - 非白名单 + SeqScan：保留 + 标 LLM PENDING（下面再 enrichPool）
                if (props.getBatch().isPoolInspectionEnabled()) {
                    PoolBatchInspector poolInspector = poolBatchInspectorProvider.getIfAvailable();
                    if (poolInspector != null) {
                        try {
                            int maxItems = props.getBatch().getPoolInspectionMaxItems();
                            log.info("[dii-batch] 任务 id={} 开始池行巡检 maxItems={}", taskId, maxItems);
                            poolInspector.run(env, maxItems);
                        } catch (Throwable t) {
                            log.error("[dii-batch] 任务 id={} 池行巡检异常（不影响巡检任务终态）：{}",
                                    taskId, t.getMessage(), t);
                        }
                    } else {
                        log.warn("[dii-batch] 任务 id={} PoolBatchInspector 未装配，跳过池行巡检", taskId);
                    }

                    // 池行 LLM 跟进（与 item 同套异步线程池，复用 LlmEnrichService.enrichPool）
                    if (props.getBatch().isAutoLlmAfterBatch()) {
                        LlmEnrichService svc = llmEnrichServiceProvider.getIfAvailable();
                        if (svc != null) {
                            try {
                                int maxItems = props.getSchedule().getDailyLlmMaxItems();
                                log.info("[dii-batch] 任务 id={} 池行 LLM 回填 maxItems={}", taskId, maxItems);
                                svc.enrichPool(env, maxItems);
                            } catch (Throwable t) {
                                log.error("[dii-batch] 任务 id={} 池行 LLM 回填异常：{}",
                                        taskId, t.getMessage(), t);
                            }
                        }
                    }
                }
            } finally {
                // 任务态在 EXPLAIN+LLM 全程保持 RUNNING，到这里才 markDone（=真全完）。
                // 放 finally 保证「任务绝不卡 RUNNING」：runLoop 或 enrich 抛异常也会 markDone。
                taskDao.markDone(taskId, c[0], c[1], c[2]);
                log.info("[dii-batch] 任务 id={} 完成 analyzed={} failed={} skipped={}",
                        taskId, c[0], c[1], c[2]);
            }
        } finally {
            MDC.remove("diiTaskId");
        }
    }

    /**
     * 422 条 EXPLAIN 主循环（逻辑原样从旧 {@code BatchInspectionService.runAsync} 搬来，未改判定）。
     */
    private int[] runLoop(long taskId, String env, List<SqlCandidate> candidates) {
        AtomicInteger analyzed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        long progressReportInterval = 20;  // 每 20 条写一次计数
        long lastReportAt = System.currentTimeMillis();

        for (int i = 0; i < candidates.size(); i++) {
            SqlCandidate cand = candidates.get(i);
            String sqlText = cand.getSql();
            String sqlHash = sha256(sqlText);

            // ═════ 单 SQL 隔离执行 ═════
            try {
                SqlInspectionRequest req = new SqlInspectionRequest();
                req.setSql(sqlText);
                req.setEnv(env);
                var result = inspectionService.inspectForBatch(req, taskId, cand);

                if (result.getReusedItemId() != null) {
                    skipped.incrementAndGet();
                } else {
                    analyzed.incrementAndGet();
                }
            } catch (Throwable t) {
                // 最后的防线：任何异常/Error 都在这里被捕获
                failed.incrementAndGet();
                log.error("[dii-batch] 单条 SQL 失败 taskId={} #{} sqlHash={} class={}: {}",
                        taskId, i + 1, sqlHash, cand.getClassFqn(), t.getMessage(), t);
                try {
                    itemDao.insertFailed(taskId, sqlHash, sqlText, env,
                            cand.getProjectName(), cand.getClassFqn(),
                            cand.getSourceFile(),
                            t.getClass().getSimpleName() + ": " + t.getMessage());
                } catch (Throwable inner) {
                    // 落库失败也吞掉，继续下一条
                    log.error("[dii-batch] 记录失败也失败了 taskId={}: {}", taskId, inner.getMessage());
                }
            }

            // ═════ 周期性更新进度 ═════
            long now = System.currentTimeMillis();
            if (i > 0 && (i % progressReportInterval == 0 || now - lastReportAt > 10_000)) {
                taskDao.updateCounters(taskId, analyzed.get(), failed.get(), skipped.get());
                lastReportAt = now;
            }
        }
        return new int[]{analyzed.get(), failed.get(), skipped.get()};
    }

    private String sha256(String s) {
        if (s == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
