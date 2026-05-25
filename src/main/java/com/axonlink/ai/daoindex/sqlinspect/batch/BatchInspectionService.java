package com.axonlink.ai.daoindex.sqlinspect.batch;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisTaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
 *   <li><b>一天一次·覆盖式</b>：每次触发先清掉「当天该 env」的旧任务再建新任务，
 *       保证每 env 每天恰好 1 条 {@code dii_analysis_task} + 1 份
 *       {@code dii_analysis_item}。当天已有 RUNNING 任务则<b>拒绝</b>本次触发
 *       （删正在跑的任务会让其异步线程写孤儿数据）。详见 {@link #startAsync}。</li>
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
    private final DiiAnalysisItemDao itemDao;
    private final BatchInspectionRunner runner;

    public BatchInspectionService(DiiAnalysisTaskDao taskDao,
                                  DiiAnalysisItemDao itemDao,
                                  BatchInspectionRunner runner) {
        this.taskDao = taskDao;
        this.itemDao = itemDao;
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
     * <p><b>一天一次·覆盖式</b>：建新 task 之前，先查「当天该 env」的旧任务：
     * <ul>
     *   <li>存在 RUNNING → 抛 {@link IllegalStateException} 拒绝本次触发
     *       （删正在跑的任务会让其异步线程继续写孤儿 item 数据）；</li>
     *   <li>否则（旧任务都 DONE/FAILED 或当天无任务）→ 逐条删旧 item + 旧 task，
     *       实现「覆盖」，保证每 env 每天恰好 1 条 task + 1 份 item。</li>
     * </ul>
     * 这几条 SELECT/DELETE 都是轻活，留在同步段是为了让「返回的 taskId 即当天
     * 唯一任务」「一天一条记录」在触发返回那一刻就成立；scanAll + EXPLAIN 等重活
     * 仍在 {@link BatchInspectionRunner} 异步线程，不破坏亚秒级返回。
     *
     * @param env         目标库 env（不能为空）
     * @param triggerType MANUAL / SCHEDULED
     * @param owner       发起者标识（手动触发用 req.remoteUser，定时用 "scheduler"）
     * @return 新建的 taskId（同步段只建记录，不会因扫描失败而失败；扫描失败由异步线程标记任务 FAILED）
     * @throws IllegalStateException 当天该 env 已有 RUNNING 巡检任务（业务拒绝，非系统故障）
     */
    public long startAsync(String env, String triggerType, String owner) {
        if (env == null || env.isBlank()) {
            throw new IllegalArgumentException("env 不能为空");
        }

        // 0. 「一天一次·覆盖式」：建新任务前先处理当天该 env 的旧任务
        //    findTodayTasks 返回每行含 id(long) + status(String) 两列
        List<Map<String, Object>> todayTasks = taskDao.findTodayTasks(env);
        // 0a. 当天有 RUNNING → 拒绝。删一个正在跑的任务会让它的异步线程
        //     继续往已删 task 写 item，产生「无主明细」（孤儿数据）。
        boolean anyRunning = todayTasks.stream()
                .anyMatch(t -> "RUNNING".equals(String.valueOf(t.get("status"))));
        if (anyRunning) {
            // 预期内的业务拒绝，不是系统故障：上层控制器据此返回非 200 + 中文提示，
            // 且只打 WARN 不打 ERROR 堆栈（详见 DaoIndexController.triggerBatch）。
            throw new IllegalStateException("当天巡检正在执行中，请等待完成后再触发");
        }
        // 0b. 当天旧任务都已结束（DONE/FAILED）或本来就没有 → 覆盖：
        //     先删旧 item（避免孤儿明细），再删旧 task 行。
        for (Map<String, Object> t : todayTasks) {
            long oldTaskId = ((Number) t.get("id")).longValue();
            String oldStatus = String.valueOf(t.get("status"));
            int delItems = itemDao.deleteByTaskId(oldTaskId);   // 先删明细
            taskDao.deleteById(oldTaskId);                       // 再删任务行
            log.info("[dii-batch] 覆盖当天旧任务 id={} status={}，清理 item {} 条",
                    oldTaskId, oldStatus, delItems);
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
