package com.axonlink.ai.daoindex.sqlinspect.batch;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisTaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 批量巡检编排器。
 *
 * <h3>核心特性</h3>
 * <ol>
 *   <li><b>失败隔离</b>：每条 SQL 独立 try-catch-Throwable，单条失败不中断批量</li>
 *   <li><b>幂等</b>：task 内按 sql_hash 去重；service 层 5 分钟窗口复用</li>
 *   <li><b>异步</b>：{@link #startAsync} 亚秒级建 task 记录后立即返回 taskId，
 *       扫描 + 422 循环全部在 {@link BatchInspectionRunner} 的异步线程跑</li>
 *   <li><b>进度</b>：每 20 条或每 10 秒写一次 {@code dii_analysis_task} 计数</li>
 * </ol>
 *
 * <h3>异步是怎么真正生效的（历史 bug 修复说明）</h3>
 * <p>旧实现里 {@code startAsync} 内部 {@code this.runAsync(...)} 自调用，
 * Spring {@code @Async} 靠代理实现、self-invocation 绕过代理 → 异步失效，
 * 重活在 HTTP 请求线程同步跑完，手动触发卡几分钟。
 * <p>现在把扫描 + 循环搬进独立 bean {@link BatchInspectionRunner}，
 * 本类<b>跨 bean</b>调用 {@code runner.runAsync(...)} → 必经 Spring 代理 →
 * {@code @Async("diiBatchExecutor")} 真正生效。本类内部不再有任何 {@code @Async} 自调用。
 */
@Service
public class BatchInspectionService {

    private static final Logger log = LoggerFactory.getLogger(BatchInspectionService.class);

    private final DiiAnalysisTaskDao taskDao;
    private final BatchInspectionRunner runner;

    public BatchInspectionService(DiiAnalysisTaskDao taskDao,
                                  BatchInspectionRunner runner) {
        this.taskDao = taskDao;
        this.runner = runner;
    }

    /**
     * 同步启动：<b>只做亚秒级的轻活</b>——创建 task 记录（status=RUNNING）并返回 taskId，
     * 随即把重活（扫描 SQL + 422 条 EXPLAIN 循环）交给 {@link BatchInspectionRunner}
     * 在 {@code diiBatchExecutor} 线程异步执行。调用方立即拿到 taskId，
     * 任务记录也立刻出现在列表里（RUNNING），用 {@code GET /batch-tasks/{id}} 轮询进度。
     *
     * <p><b>关键</b>：{@code scanner.scanAll} 这个重活<b>不再</b>在本同步段执行
     * （否则 HTTP 线程仍要等扫描完才能返回），已移进异步方法内部最前面。
     * 因此建 task 时 {@code total_sqls} 暂填 0，异步扫描+去重完成后再回填真实总数。
     *
     * @param env         目标库 env（不能为空）
     * @param triggerType MANUAL / SCHEDULED
     * @param owner       发起者标识（手动触发用 req.remoteUser，定时用 "scheduler"）
     * @return 新建的 taskId（同步段只建记录，不会因扫描失败而失败；扫描失败由异步线程标记任务 FAILED）
     */
    public long startAsync(String env, String triggerType, String owner) {
        if (env == null || env.isBlank()) {
            throw new IllegalArgumentException("env 不能为空");
        }

        // 1. 立即建 task 记录（status=RUNNING，total_sqls 先填 0，扫描完异步回填）
        //    —— 这一步轻量，保证调用方亚秒级拿到 taskId、列表立刻有记录
        String taskNo = newTaskNo(env);
        long taskId = taskDao.startTask(taskNo, env, 0, triggerType, owner);
        log.info("[dii-batch] 任务 id={} 已建记录，env={} trigger={}，重活转入异步线程",
                taskId, env, triggerType);

        // 2. 跨 bean 调用 → 必经 Spring 代理 → @Async 真正生效 →
        //    scanAll + 去重 + 422 循环全部在 diiBatchExecutor 线程跑，不阻塞当前线程
        runner.runAsync(taskId, env);
        return taskId;
    }

    private String newTaskNo(String env) {
        return "DII-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + env;
    }
}
