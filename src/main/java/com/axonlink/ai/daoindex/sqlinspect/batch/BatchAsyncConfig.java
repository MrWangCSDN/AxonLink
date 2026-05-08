package com.axonlink.ai.daoindex.sqlinspect.batch;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * DAO 索引巡检模块专用的 {@code @Async} 线程池 + 开启定时任务。
 *
 * <p>独立线程池原因：避免批量巡检占用 Spring 默认 SimpleAsyncTaskExecutor（不复用线程、不限流），
 * 也避免占用 Tomcat HTTP 线程。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class BatchAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(BatchAsyncConfig.class);

    /**
     * 批量巡检专用线程池。
     *
     * <p>当前 {@code BatchInspectionService.runAsync} 里对 SQL 列表是**串行循环**，
     * 所以此池只要有 2~3 个线程就够（并发多个 batch 任务时有冗余）。
     * 后续如果要做 SQL 级并发，应该另建一个更大的池。
     */
    @Bean(name = "diiBatchExecutor")
    public Executor diiBatchExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(8);
        ex.setThreadNamePrefix("dii-batch-");
        ex.setKeepAliveSeconds(60);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(60);
        ex.initialize();
        log.info("[dii-batch] 批量巡检线程池初始化完成：core=2 max=4 queue=8");
        return ex;
    }

    /**
     * LLM 回填专用线程池（与 {@link #diiBatchExecutor} 物理隔离）。
     *
     * <p>原 {@code LlmEnrichService.enrich()} 是一个串行 for 循环，340 条 ×
     * 单条平均 145s = 13+ 小时。改成把每条 item 提交到本池并发跑后，理论上
     * 速度 = 串行速度 × {@code dao-index-analysis.concurrency.llm-parallel}。
     *
     * <p>为什么不复用 {@link #diiBatchExecutor}：
     * <ul>
     *   <li>{@code diiBatchExecutor} 上跑的是 {@code @Async("diiBatchExecutor")}
     *       的 {@code enrichAsync} 自身，如果它再往同一个池里提交 N 个子任务，
     *       池满了就互相阻塞，容易死锁；</li>
     *   <li>LLM 任务是高延迟 IO 等待（单条十几秒到分钟级），需要的线程数远超
     *       批量编排任务，二者性质不同。</li>
     * </ul>
     *
     * <p>队列开得很大（5000）：批量回填一次最多 {@code MAX_BATCH_SIZE=5000} 条，
     * 全部一次性 submit 不能因队列满被 reject。
     */
    @Bean(name = "diiLlmExecutor")
    public Executor diiLlmExecutor(DaoIndexAnalysisProperties props) {
        int parallel = Math.max(1, props.getConcurrency().getLlmParallel());
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(parallel);
        ex.setMaxPoolSize(parallel);
        ex.setQueueCapacity(5000);
        ex.setThreadNamePrefix("dii-llm-");
        ex.setKeepAliveSeconds(60);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(120);
        ex.initialize();
        log.info("[dii-llm] LLM 回填线程池初始化完成：core={} max={} queue=5000", parallel, parallel);
        return ex;
    }
}
