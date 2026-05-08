package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 批量回填编排器。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>从 {@code dii_analysis_item} 找出 {@code llm_pending=1} 的 items（或指定 taskId/状态）</li>
 *   <li>把每条 item 提交到 {@code diiLlmExecutor} 线程池并发跑 LLM 分析</li>
 *   <li>单条失败不阻塞：结果已落到 {@code llm_error} + {@code llm_status=FAILED}</li>
 *   <li>异步执行：{@link #enrichAsync} 立即返回，后台跑</li>
 * </ul>
 *
 * <h3>并发模型</h3>
 * <ul>
 *   <li>并发度由 {@code dao-index-analysis.concurrency.llm-parallel} 控制（默认 4）</li>
 *   <li>真正限流靠 {@link Executor} 自身的 corePoolSize（在 {@link com.axonlink.ai.daoindex.sqlinspect.batch.BatchAsyncConfig} 配置）</li>
 *   <li>所有 item 一次性 submit 到队列里，让线程池串行消费，省心</li>
 * </ul>
 *
 * <h3>失败重试</h3>
 * <ul>
 *   <li>批量循环本身不重试；单条 LLM 调用失败 / JSON 解析失败的有限重试在
 *       {@link SqlLlmAnalyzeService} 内部完成（见 {@code dao-index-analysis.llm.retry-attempts}）</li>
 *   <li>失败记录可通过 {@code POST /llm-enrich?onlyFailed=true} 手动重跑</li>
 * </ul>
 */
@Service
public class LlmEnrichService {

    private static final Logger log = LoggerFactory.getLogger(LlmEnrichService.class);

    /** 一次扫描最多处理这么多条，避免无限跑。 */
    private static final int MAX_BATCH_SIZE = 5000;

    /** 批量回填默认使用的模型（按需求：minimax）。 */
    private static final String BATCH_DEFAULT_MODEL = "minimax-2.7";

    private final DiiAnalysisItemDao itemDao;
    private final SqlLlmAnalyzeService llmAnalyzer;
    /** LLM 并发线程池，注入名字必须匹配 {@code BatchAsyncConfig#diiLlmExecutor} bean 名。 */
    private final Executor llmExecutor;
    private final DaoIndexAnalysisProperties props;

    public LlmEnrichService(DiiAnalysisItemDao itemDao,
                            SqlLlmAnalyzeService llmAnalyzer,
                            @Qualifier("diiLlmExecutor") Executor llmExecutor,
                            DaoIndexAnalysisProperties props) {
        this.itemDao = itemDao;
        this.llmAnalyzer = llmAnalyzer;
        this.llmExecutor = llmExecutor;
        this.props = props;
    }

    /**
     * 同步入口：查询 + 并发提交 + 等待全部完成（调用方如需异步，请用 {@link #enrichAsync}）。
     *
     * <p>注意：本方法本身是阻塞的，会等到所有提交的子任务跑完才返回。
     * 如果在 HTTP 线程里同步调用，会被 LLM 整批耗时拖住，所以 controller 那边要用 enrichAsync。
     */
    public EnrichSummary enrich(String env, Long taskId, boolean onlyFailed, int maxItems) {
        long batchStart = System.currentTimeMillis();
        int effMax = Math.min(Math.max(maxItems, 1), MAX_BATCH_SIZE);
        List<Long> ids = itemDao.findPendingLlmIds(env, taskId, onlyFailed, effMax);
        int total = ids.size();

        int parallel = Math.max(1, props.getConcurrency().getLlmParallel());
        log.info("[dii-llm-enrich] ══ 启动批量 LLM 回填 env={} taskId={} onlyFailed={} maxItems={} 候选={} 并发={} ══",
                env, taskId, onlyFailed, effMax, total, parallel);

        EnrichSummary summary = new EnrichSummary();
        summary.totalCandidates = total;
        if (total == 0) {
            log.info("[dii-llm-enrich] ══ 无待处理 items，直接结束 ══");
            return summary;
        }

        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicLong lastProgressAt = new AtomicLong(System.currentTimeMillis());
        // 进度日志间隔：至少 10 条 / 至多 50 条 / 否则按 5% 间隔
        int progressInterval = Math.max(10, Math.min(50, total / 20));

        // 一次性把所有 item 提交到线程池；线程池 corePoolSize=parallel，
        // 多余的会进入它的内部队列排队，由空闲线程顺序消费。
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);
        for (Long id : ids) {
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                // MDC 在线程池里默认不会跨线程传递，所以这里手动 put（每个子任务自带）
                MDC.put("diiLlmItemId", String.valueOf(id));
                try {
                    SqlLlmResult result = llmAnalyzer.analyzeItem(id, null, BATCH_DEFAULT_MODEL);
                    if (result != null && result.getError() == null) {
                        done.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    log.error("[dii-llm-enrich] 未预期异常 itemId={}: {}", id, t.getMessage(), t);
                } finally {
                    MDC.remove("diiLlmItemId");
                    int i = processed.incrementAndGet();
                    long now = System.currentTimeMillis();
                    long lastAt = lastProgressAt.get();
                    boolean shouldLog = (i % progressInterval == 0) || (now - lastAt >= 30_000);
                    // CAS 抢一次"该我打日志"，避免多线程同一时刻重复打
                    if (shouldLog && lastProgressAt.compareAndSet(lastAt, now)) {
                        long elapsed = now - batchStart;
                        // 并发场景下"平均"要按真实墙钟算：墙钟耗时 / 已完成数
                        double wallAvg = (double) elapsed / i;
                        // 剩余时间按 (剩余条数 × 单条墙钟耗时 / 并发) 估算
                        long etaMs = (long) (wallAvg * (total - i));
                        log.info("[dii-llm-enrich] 进度 {}/{} done={} failed={} 已耗时={}s 平均={}ms/item 预计剩余={}s",
                                i, total, done.get(), failed.get(),
                                elapsed / 1000, (long) wallAvg, etaMs / 1000);
                    }
                }
            }, llmExecutor);
            futures.add(f);
        }

        // 阻塞等待所有任务完成；exceptionally 已经在 try/catch 里吞掉，allOf 这里不会抛
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Throwable t) {
            // join 不应该抛（每个 future 内部都已经 catch 住了），兜底打个日志
            log.error("[dii-llm-enrich] allOf.join 异常（理论上不该发生）：{}", t.getMessage(), t);
        }

        summary.done = done.get();
        summary.failed = failed.get();
        long totalMs = System.currentTimeMillis() - batchStart;
        log.info("[dii-llm-enrich] ══ 完成 done={} failed={} 总={} 耗时={}s 并发={} " +
                        "(env={}, taskId={}, onlyFailed={}) ══",
                summary.done, summary.failed, summary.totalCandidates, totalMs / 1000, parallel,
                env, taskId, onlyFailed);
        return summary;
    }

    /**
     * 异步版本：立即返回，后台用 {@code diiBatchExecutor} 跑编排逻辑，
     * 编排逻辑内部再把每条 item submit 到 {@code diiLlmExecutor} 并发处理。
     */
    @Async("diiBatchExecutor")
    public void enrichAsync(String env, Long taskId, boolean onlyFailed, int maxItems) {
        enrich(env, taskId, onlyFailed, maxItems);
    }

    /** 对外的简单计数汇总。 */
    public static class EnrichSummary {
        public int totalCandidates;
        public int done;
        public int failed;

        public int getTotalCandidates() { return totalCandidates; }
        public int getDone() { return done; }
        public int getFailed() { return failed; }
    }
}
