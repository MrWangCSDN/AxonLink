package com.axonlink.ai.daoindex.sqlinspect.batch;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.llm.LlmEnrichService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日凌晨 1 点定时触发全量 DAO 索引巡检。
 *
 * <p>可通过配置 {@code dao-index-analysis.schedule.daily-cron} 覆盖，比如改成 2 点。
 */
@Component
public class BatchInspectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchInspectionScheduler.class);

    private final BatchInspectionService batchService;
    private final DaoIndexAnalysisProperties props;
    private final ObjectProvider<LlmEnrichService> llmEnrichServiceProvider;

    public BatchInspectionScheduler(BatchInspectionService batchService,
                                    DaoIndexAnalysisProperties props,
                                    ObjectProvider<LlmEnrichService> llmEnrichServiceProvider) {
        this.batchService = batchService;
        this.props = props;
        this.llmEnrichServiceProvider = llmEnrichServiceProvider;
    }

    /**
     * 每天凌晨 1 点触发一次默认 env 的全量巡检。
     * <p>cron 表达式：{@code 秒 分 时 日 月 周}，这里是"每天 01:00:00"。
     * <p>通过 {@code dao-index-analysis.schedule.daily-cron} 可以改，默认 {@code 0 0 1 * * ?}。
     */
    @Scheduled(cron = "${dao-index-analysis.schedule.daily-cron:0 0 1 * * ?}")
    public void runDailyBatch() {
        if (!props.getSchedule().isEnabled()) {
            log.debug("[dii-scheduler] schedule.enabled=false，跳过每日批量巡检");
            return;
        }
        String env = props.getDefaultEnv();
        log.info("[dii-scheduler] 触发每日批量巡检，env={}", env);
        try {
            long taskId = batchService.startAsync(env, "SCHEDULED", "scheduler");
            log.info("[dii-scheduler] 已调度 taskId={}", taskId);
        } catch (Throwable t) {
            // 调度器自身不允许抛异常，否则 Spring 可能停掉后续触发
            log.error("[dii-scheduler] 调度失败：{}", t.getMessage(), t);
        }
    }

    /**
     * 每日 02:00 触发 LLM 批量回填。
     *
     * <p>在 01:00 规则批量之后跑，此时 llm_pending=1 的 items 已经就位。
     * 拉取数量由 {@code dao-index-analysis.schedule.daily-llm-max-items} 控制，默认 2000。
     */
    @Scheduled(cron = "${dao-index-analysis.schedule.daily-llm-cron:0 0 2 * * ?}")
    public void runDailyLlmEnrich() {
        if (!props.getSchedule().isEnabled()) {
            log.debug("[dii-scheduler] schedule.enabled=false，跳过每日 LLM 回填");
            return;
        }
        LlmEnrichService svc = llmEnrichServiceProvider.getIfAvailable();
        if (svc == null) {
            log.info("[dii-scheduler] LlmEnrichService 未装配，跳过 LLM 回填（检查 ai.analysis.enabled）");
            return;
        }
        String env = props.getDefaultEnv();
        // 之前用 Integer.getInteger 读 System property，从来没生效；改为从 properties bean 读
        int maxItems = props.getSchedule().getDailyLlmMaxItems();
        log.info("[dii-scheduler] 触发每日 LLM 回填 env={} maxItems={}", env, maxItems);
        try {
            svc.enrichAsync(env, null, false, maxItems);
        } catch (Throwable t) {
            log.error("[dii-scheduler] LLM 回填调度失败：{}", t.getMessage(), t);
        }
    }
}
