package com.axonlink.ai.daoindex.sqlinspect.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
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
}
