package com.axonlink.service.impl;

import com.axonlink.service.BuildSyncStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构建同步状态服务实现。
 *
 * <p><b>本版改造</b>：从 HTTP 调用 sunline-benchmark 改为<b>直接读共享库 {@code benchmarkdb}</b>。
 * <ul>
 *   <li>sunline-benchmark 把全量拉取+编译记录写入 {@code build_operation_record}</li>
 *   <li>axon-link-server 跟它共用同一个 MySQL（{@code benchmarkdb}），
 *       通过 {@code diiResultJdbcTemplate} 直接读最新一条 TASK 记录即可</li>
 * </ul>
 * <p>好处：
 * <ol>
 *   <li>少一次 HTTP 跳转，省毫秒级延迟，避免 8080/9999 端口配置错乱（内网 base-url 经常改错）</li>
 *   <li>sunline-benchmark 暂时挂掉时，axon-link-server 仍能从 DB 看到上次构建状态</li>
 *   <li>无需在两边维护 endpoint 兼容性</li>
 * </ol>
 */
@Service
public class BuildSyncStatusServiceImpl implements BuildSyncStatusService {

    private static final Logger log = LoggerFactory.getLogger(BuildSyncStatusServiceImpl.class);
    private static final DateTimeFormatter REFRESH_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 取最新一条 TASK 类型记录（一次全量拉取+编译对应一条）。 */
    private static final String SQL_LATEST_TASK =
            "SELECT operation_id, version_no, status, async_build_status, " +
            "       result_message, error_message, start_time, end_time, update_time " +
            "  FROM build_operation_record " +
            " WHERE record_type = 'TASK' " +
            " ORDER BY id DESC LIMIT 1";

    private final JdbcTemplate jdbc;

    public BuildSyncStatusServiceImpl(@Qualifier("diiResultJdbcTemplate") JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    @Override
    public Map<String, Object> loadBuildSyncStatus() {
        Map<String, Object> result = baseResult();
        try {
            Map<String, Object> task = jdbc.queryForMap(SQL_LATEST_TASK);
            enrichResult(result, task);
            result.put("connected", true);
        } catch (EmptyResultDataAccessException e) {
            // 共享库里还没有任何 TASK 记录（首次部署）
            log.info("[BuildSyncStatus] build_operation_record 暂无 TASK 记录");
            result.put("statusText", "未构建");
            result.put("statusType", "idle");
            result.put("message", "尚未触发过全量拉取+编译");
        } catch (Exception e) {
            log.warn("[BuildSyncStatus] 查询 build_operation_record 失败：{}", e.getMessage());
            result.put("statusText", "查询失败");
            result.put("statusType", "error");
            result.put("message", "无法读取共享库 build_operation_record：" + e.getMessage());
        }
        result.put("refreshedAt", LocalDateTime.now().format(REFRESH_FMT));
        return result;
    }

    /** 把 DB 行映射进对外结构。 */
    private void enrichResult(Map<String, Object> result, Map<String, Object> task) {
        String operationId      = stringValue(task.get("operation_id"));
        String versionNo        = firstNonBlank(stringValue(task.get("version_no")), "--");
        String status           = stringValue(task.get("status"));
        String asyncBuildStatus = stringValue(task.get("async_build_status"));
        String resultMessage    = stringValue(task.get("result_message"));
        String errorMessage     = stringValue(task.get("error_message"));

        result.put("operationId", operationId == null ? "" : operationId);
        result.put("versionNo", versionNo);
        result.put("rawStatus", firstNonBlank(asyncBuildStatus, status, "RUNNING"));
        result.put("asyncBuildStatus", asyncBuildStatus == null ? "" : asyncBuildStatus);
        result.put("phase", "");
        result.put("statusText", resolveStatusText(asyncBuildStatus, status));
        result.put("statusType", resolveStatusType(asyncBuildStatus, status));
        result.put("message", resolveMessage(asyncBuildStatus, status, resultMessage, errorMessage));
        result.put("updatedAt", formatTime(task.get("update_time"), task.get("end_time"), task.get("start_time")));
    }

    private String resolveStatusText(String asyncBuildStatus, String status) {
        String n = trimToEmpty(asyncBuildStatus);
        if ("成功".equals(n)) return "成功";
        if (isFailureAsyncStatus(n)) return "失败";
        if ("SUCCESS".equalsIgnoreCase(status)) return "成功";
        if ("FAILED".equalsIgnoreCase(status))  return "失败";
        return "构建中";
    }

    private String resolveStatusType(String asyncBuildStatus, String status) {
        String n = trimToEmpty(asyncBuildStatus);
        if ("成功".equals(n)) return "success";
        if (isFailureAsyncStatus(n)) return "error";
        if ("SUCCESS".equalsIgnoreCase(status)) return "success";
        if ("FAILED".equalsIgnoreCase(status))  return "error";
        return "running";
    }

    private String resolveMessage(String asyncBuildStatus, String status,
                                  String resultMessage, String errorMessage) {
        String n = trimToEmpty(asyncBuildStatus);
        if ("成功".equals(n)) {
            return firstNonBlank(resultMessage, "异步构建完成");
        }
        if (isFailureAsyncStatus(n)) {
            return firstNonBlank(errorMessage, n);
        }
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return firstNonBlank(resultMessage, "构建完成");
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return firstNonBlank(errorMessage, "构建失败");
        }
        if (!n.isEmpty()) return "当前阶段：" + n;
        return "构建进行中";
    }

    /** asyncBuildStatus 包含中文"失败"或显式"触发失败"判定为失败。 */
    private boolean isFailureAsyncStatus(String s) {
        return s != null && !s.isEmpty()
                && ("触发失败".equals(s) || s.contains("失败"));
    }

    private String formatTime(Object... candidates) {
        for (Object o : candidates) {
            if (o instanceof Timestamp ts) return ts.toLocalDateTime().format(REFRESH_FMT);
            if (o instanceof LocalDateTime ldt) return ldt.format(REFRESH_FMT);
            if (o instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }

    private Map<String, Object> baseResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", "axon-link-server");
        result.put("branch", "Master");
        result.put("versionNo", "--");
        result.put("statusText", "构建中");
        result.put("statusType", "running");
        result.put("message", "正在等待异步构建完成");
        result.put("operationId", "");
        result.put("updatedAt", "");
        result.put("asyncBuildStatus", "");
        result.put("connected", false);
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
