package com.axonlink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 监听 sunline-benchmark 共享库 {@code build_operation_record}，
 * 当看到一条<b>新</b>的 TASK 记录从 RUNNING 变成 SUCCESS（或 asyncBuildStatus=成功）时，
 * <b>自动触发</b>本服务的 Neo4j 异步图构建（phase0_bootstrap → phase1 → phase2 …）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>幂等</b>：用 {@code (operationId, status)} 作为去重 key，相同 operation 已触发过就不再触发。
 *       服务重启后从最新 TASK 当前状态作为基线，已成功的旧任务不会被误触发。</li>
 *   <li><b>低频轮询</b>：默认 30 秒一次，足够及时。也可改成更短，DB 查询本身极轻。</li>
 *   <li><b>开关</b>：{@code build-sync.auto-phase.enabled}（默认 true）。出问题可一键关。</li>
 *   <li><b>双跑保护</b>：Neo4jGraphBuilder.startBuildAsync 内部已防止并发触发，
 *       即使本类和手动触发同时发生也不会重复跑。</li>
 * </ul>
 */
@Component
@EnableScheduling
public class BuildSuccessAutoPhaseTrigger {

    private static final Logger log = LoggerFactory.getLogger(BuildSuccessAutoPhaseTrigger.class);

    /** 取最新 TASK 记录，看 operation_id + 状态。 */
    private static final String SQL_LATEST_TASK =
            "SELECT operation_id, version_no, status, async_build_status, " +
            "       update_time " +
            "  FROM build_operation_record " +
            " WHERE record_type = 'TASK' " +
            " ORDER BY id DESC LIMIT 1";

    private final JdbcTemplate jdbc;
    private final Neo4jGraphBuilder neo4jGraphBuilder;

    /**
     * 总开关。出问题（比如不想自动触发了）可改成 false。
     */
    @Value("${build-sync.auto-phase.enabled:true}")
    private boolean enabled;

    /**
     * 上一次"已成功并已触发过 Neo4j 重建"的 operationId。
     * 同一个 operationId 不会重复触发；服务重启后会用启动时看到的当前 op 作为基线。
     */
    private final AtomicReference<String> lastTriggeredOperationId = new AtomicReference<>();

    /**
     * 启动期是否已经基线化。在第一次轮询里：
     *  - 若当前 TASK 已是 SUCCESS：把它作为"已触发"基线（不重复触发，因为 Neo4j 大概率已经构建过）
     *  - 若当前 TASK 是 RUNNING / FAILED：基线为空，等下次状态变 SUCCESS 时再触发
     */
    private volatile boolean baselined = false;

    public BuildSuccessAutoPhaseTrigger(@Qualifier("diiResultJdbcTemplate") JdbcTemplate diiResultJdbcTemplate,
                                        Neo4jGraphBuilder neo4jGraphBuilder) {
        this.jdbc = diiResultJdbcTemplate;
        this.neo4jGraphBuilder = neo4jGraphBuilder;
    }

    /**
     * 每 30 秒看一次共享库。
     * <p>{@code fixedDelay=30000} 表示上一轮跑完后再等 30 秒，避免在慢查询时叠加。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void poll() {
        if (!enabled) return;
        try {
            Map<String, Object> task = queryLatestTask();
            if (task == null) {
                if (!baselined) {
                    log.info("[BuildAutoPhase] 启动基线化：DB 无 TASK 记录，等待首次构建");
                    baselined = true;
                }
                return;
            }

            String operationId      = stringValue(task.get("operation_id"));
            String status           = stringValue(task.get("status"));
            String asyncBuildStatus = stringValue(task.get("async_build_status"));
            boolean isSuccess = isBuildSuccess(status, asyncBuildStatus);

            // 启动期第一次：把当前状态作为基线，不主动触发
            if (!baselined) {
                if (isSuccess && operationId != null) {
                    lastTriggeredOperationId.set(operationId);
                    log.info("[BuildAutoPhase] 启动基线化：当前 TASK 已 SUCCESS opId={}，作为已触发基线",
                            operationId);
                } else {
                    log.info("[BuildAutoPhase] 启动基线化：当前 TASK status={} async={} opId={}，等待下次成功",
                            status, asyncBuildStatus, operationId);
                }
                baselined = true;
                return;
            }

            // 后续轮询：只在 SUCCESS 且 operationId 跟上次触发不同时拉起 phase
            if (!isSuccess || operationId == null) return;
            String prev = lastTriggeredOperationId.get();
            if (operationId.equals(prev)) return;   // 已触发过，跳过

            log.info("[BuildAutoPhase] 检测到新构建成功 opId={} version={} status={} async={}，自动触发 Neo4j 重建",
                    operationId, task.get("version_no"), status, asyncBuildStatus);

            // 抢占式 CAS，避免同一时刻两个 poll 同时触发
            if (!lastTriggeredOperationId.compareAndSet(prev, operationId)) {
                log.info("[BuildAutoPhase] 同 opId 已被并发触发，跳过 opId={}", operationId);
                return;
            }
            try {
                Map<String, Object> result = neo4jGraphBuilder.startBuildAsync(operationId, stringValue(task.get("version_no")));
                log.info("[BuildAutoPhase] Neo4j 异步重建已触发：accepted={} status={} message={}",
                        result.get("accepted"), result.get("asyncBuildStatus"), result.get("message"));
            } catch (Throwable t) {
                // 触发失败回滚 lastTriggeredOperationId，让下一轮再试
                lastTriggeredOperationId.compareAndSet(operationId, prev);
                log.warn("[BuildAutoPhase] Neo4j 触发异常，已回滚基线 opId={}: {}",
                        operationId, t.getMessage(), t);
            }
        } catch (Throwable t) {
            log.warn("[BuildAutoPhase] 轮询失败：{}", t.getMessage());
        }
    }

    private Map<String, Object> queryLatestTask() {
        try {
            return jdbc.queryForMap(SQL_LATEST_TASK);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private boolean isBuildSuccess(String status, String asyncBuildStatus) {
        // sunline-benchmark 把成功写成 status=SUCCESS 或 asyncBuildStatus=成功（中文）
        if ("SUCCESS".equalsIgnoreCase(status)) return true;
        if (asyncBuildStatus != null && "成功".equals(asyncBuildStatus.trim())) return true;
        return false;
    }

    private String stringValue(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 调试用：看当前已触发基线。 */
    public Map<String, Object> debugStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("baselined", baselined);
        m.put("lastTriggeredOperationId", lastTriggeredOperationId.get());
        return m;
    }
}
