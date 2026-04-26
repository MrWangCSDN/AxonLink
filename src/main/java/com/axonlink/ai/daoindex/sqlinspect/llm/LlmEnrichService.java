package com.axonlink.ai.daoindex.sqlinspect.llm;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 批量回填编排器。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>从 {@code dii_analysis_item} 找出 {@code llm_pending=1} 的 items（或指定 taskId/状态）</li>
 *   <li>逐条调 {@link SqlLlmAnalyzeService} 做 LLM 分析</li>
 *   <li>单条失败不阻塞：结果已落到 {@code llm_error} + {@code llm_status=FAILED}</li>
 *   <li>异步执行：{@link #enrichAsync} 立即返回，后台跑</li>
 * </ul>
 *
 * <h3>失败重试</h3>
 * <ul>
 *   <li>不自动重试（按用户需求）</li>
 *   <li>失败记录可通过 {@code POST /llm-enrich?onlyFailed=true} 手动重跑</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class LlmEnrichService {

    private static final Logger log = LoggerFactory.getLogger(LlmEnrichService.class);

    /** 一次扫描最多处理这么多条，避免无限跑。 */
    private static final int MAX_BATCH_SIZE = 5000;

    private final DiiAnalysisItemDao itemDao;
    private final SqlLlmAnalyzeService llmAnalyzer;

    public LlmEnrichService(DiiAnalysisItemDao itemDao, SqlLlmAnalyzeService llmAnalyzer) {
        this.itemDao = itemDao;
        this.llmAnalyzer = llmAnalyzer;
    }

    /** 同步查询 + 同步循环（调用方如需异步，请用 {@link #enrichAsync}）。 */
    public EnrichSummary enrich(String env, Long taskId, boolean onlyFailed, int maxItems) {
        long batchStart = System.currentTimeMillis();
        int effMax = Math.min(Math.max(maxItems, 1), MAX_BATCH_SIZE);
        List<Long> ids = itemDao.findPendingLlmIds(env, taskId, onlyFailed, effMax);
        int total = ids.size();
        log.info("[dii-llm-enrich] ══ 启动批量 LLM 回填 env={} taskId={} onlyFailed={} maxItems={} 候选={} ══",
                env, taskId, onlyFailed, effMax, total);

        EnrichSummary summary = new EnrichSummary();
        summary.totalCandidates = total;
        if (total == 0) {
            log.info("[dii-llm-enrich] ══ 无待处理 items，直接结束 ══");
            return summary;
        }

        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // 进度日志：每 10 条或每 30 秒 打一次
        long lastProgressAt = System.currentTimeMillis();
        int progressInterval = Math.max(10, Math.min(50, total / 20));

        int i = 0;
        for (Long id : ids) {
            i++;
            MDC.put("diiLlmItemId", String.valueOf(id));
            long itemStart = System.currentTimeMillis();
            try {
                SqlLlmResult result = llmAnalyzer.analyzeItem(id);
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
            }

            long now = System.currentTimeMillis();
            if (i % progressInterval == 0 || now - lastProgressAt >= 30_000) {
                long elapsed = now - batchStart;
                double avgPerItem = (double) elapsed / i;
                long etaMs = (long) (avgPerItem * (total - i));
                log.info("[dii-llm-enrich] 进度 {}/{} done={} failed={} 已耗时={}s 平均={}ms/item 预计剩余={}s",
                        i, total, done.get(), failed.get(),
                        elapsed / 1000, (long) avgPerItem, etaMs / 1000);
                lastProgressAt = now;
            }
        }

        summary.done = done.get();
        summary.failed = failed.get();
        long totalMs = System.currentTimeMillis() - batchStart;
        log.info("[dii-llm-enrich] ══ 完成 done={} failed={} 总={} 耗时={}s " +
                        "(env={}, taskId={}, onlyFailed={}) ══",
                summary.done, summary.failed, summary.totalCandidates, totalMs / 1000,
                env, taskId, onlyFailed);
        return summary;
    }

    /** 异步版本：立即返回，后台跑，不阻塞 HTTP 线程。用独立线程池 diiBatchExecutor。 */
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
