package com.axonlink.ai.daoindex.sqlinspect.batch;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlCandidate;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionRequest;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisTaskDao;
import com.axonlink.ai.daoindex.sqlinspect.scan.SqlSourceScanner;
import com.axonlink.ai.daoindex.sqlinspect.service.SqlInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量巡检编排器。
 *
 * <h3>核心特性</h3>
 * <ol>
 *   <li><b>失败隔离</b>：每条 SQL 独立 try-catch-Throwable，单条失败不中断批量</li>
 *   <li><b>幂等</b>：task 内按 sql_hash 去重；service 层 5 分钟窗口复用</li>
 *   <li><b>异步</b>：{@link #startAsync} 立即返回 taskId，后台 worker 跑</li>
 *   <li><b>进度</b>：每 20 条或每 10 秒写一次 {@code dii_analysis_task} 计数</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class BatchInspectionService {

    private static final Logger log = LoggerFactory.getLogger(BatchInspectionService.class);

    private final SqlSourceScanner scanner;
    private final DiiAnalysisTaskDao taskDao;
    private final DiiAnalysisItemDao itemDao;
    private final SqlInspectionService inspectionService;
    private final DaoIndexAnalysisProperties props;

    public BatchInspectionService(SqlSourceScanner scanner,
                                  DiiAnalysisTaskDao taskDao,
                                  DiiAnalysisItemDao itemDao,
                                  SqlInspectionService inspectionService,
                                  DaoIndexAnalysisProperties props) {
        this.scanner = scanner;
        this.taskDao = taskDao;
        this.itemDao = itemDao;
        this.inspectionService = inspectionService;
        this.props = props;
    }

    /**
     * 同步启动：创建 task 记录，扫描 SQL 列表，返回 taskId；真正的分析工作交给 {@link #runAsync} 异步执行。
     * 调用方立即拿到 taskId，用 {@code GET /batch-tasks/{id}} 轮询进度。
     *
     * @param env         目标库 env（不能为空）
     * @param triggerType MANUAL / SCHEDULED
     * @param owner       发起者标识（手动触发用 req.remoteUser，定时用 "scheduler"）
     * @return 新建的 taskId，扫描阶段失败返回 -1
     */
    public long startAsync(String env, String triggerType, String owner) {
        if (env == null || env.isBlank()) {
            throw new IllegalArgumentException("env 不能为空");
        }
        // 1. 扫 SQL 候选
        List<SqlCandidate> candidates;
        try {
            candidates = scanner.scanAll(0);
        } catch (Throwable t) {
            log.error("[dii-batch] 扫描阶段异常：{}", t.getMessage(), t);
            long failedTaskId = taskDao.startTask(newTaskNo(env), env, 0, triggerType, owner);
            taskDao.markFailed(failedTaskId, "扫描阶段异常：" + t.getMessage());
            return failedTaskId;
        }

        // 2. 任务内按 sql_hash 去重（防止同 SQL 在多个文件里被扫到多份）
        Set<String> seenHashes = new HashSet<>();
        candidates.removeIf(c -> !seenHashes.add(sha256(c.getSql())));

        // 3. 建 task 记录
        String taskNo = newTaskNo(env);
        long taskId = taskDao.startTask(taskNo, env, candidates.size(), triggerType, owner);
        log.info("[dii-batch] 任务 id={} 启动，env={} 候选 SQL {} 条（已按 hash 去重），trigger={}",
                taskId, env, candidates.size(), triggerType);

        // 4. 触发后台跑
        runAsync(taskId, env, candidates);
        return taskId;
    }

    /**
     * 异步执行核心循环。**所有异常都被捕获**，单条失败绝不中断整个任务。
     */
    @Async("diiBatchExecutor")
    public void runAsync(long taskId, String env, List<SqlCandidate> candidates) {
        MDC.put("diiTaskId", String.valueOf(taskId));
        AtomicInteger analyzed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        long progressReportInterval = 20;  // 每 20 条写一次计数
        long lastReportAt = System.currentTimeMillis();

        try {
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
        } finally {
            // 不管怎样，最后都标记 DONE（failedSqls > 0 就体现在计数里）
            taskDao.markDone(taskId, analyzed.get(), failed.get(), skipped.get());
            log.info("[dii-batch] 任务 id={} 完成 analyzed={} failed={} skipped={}",
                    taskId, analyzed.get(), failed.get(), skipped.get());
            MDC.remove("diiTaskId");
        }
    }

    private String newTaskNo(String env) {
        return "DII-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + env;
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
