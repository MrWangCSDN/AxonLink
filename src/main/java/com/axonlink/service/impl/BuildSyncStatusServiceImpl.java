package com.axonlink.service.impl;

import com.axonlink.service.BuildSyncStatusService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建同步状态服务实现。
 */
@Service
public class BuildSyncStatusServiceImpl implements BuildSyncStatusService {

    private static final Logger log = LoggerFactory.getLogger(BuildSyncStatusServiceImpl.class);
    private static final DateTimeFormatter REFRESH_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String benchmarkBaseUrl;
    private final Duration timeout;

    public BuildSyncStatusServiceImpl(
            ObjectMapper objectMapper,
            @Value("${benchmark.build-status.base-url:http://127.0.0.1:8080}") String benchmarkBaseUrl,
            @Value("${benchmark.build-status.timeout-ms:5000}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.benchmarkBaseUrl = trimTrailingSlash(benchmarkBaseUrl);
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 1000));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public Map<String, Object> loadBuildSyncStatus() {
        Map<String, Object> result = baseResult();
        try {
            Map<String, Object> buildStatus = fetchMapData("/api/build/all/status");
            Map<String, Object> latestTask = fetchLatestTask();
            enrichResult(result, buildStatus, latestTask);
        } catch (Exception e) {
            log.warn("[BuildSyncStatus] load failed: {}", e.getMessage());
            result.put("statusText", "状态未知");
            result.put("statusType", "unknown");
            result.put("message", "构建状态服务暂不可用");
            result.put("connected", false);
        }
        result.put("refreshedAt", LocalDateTime.now().format(REFRESH_FMT));
        return result;
    }

    private void enrichResult(Map<String, Object> result, Map<String, Object> buildStatus, Map<String, Object> latestTask) {
        String snapshotStatus = stringValue(buildStatus == null ? null : buildStatus.get("status"));
        String phase = stringValue(buildStatus == null ? null : buildStatus.get("phase"));
        String operationId = firstNonBlank(
                stringValue(buildStatus == null ? null : buildStatus.get("operationId")),
                stringValue(latestTask == null ? null : latestTask.get("operationId")));
        String versionNo = firstNonBlank(
                stringValue(latestTask == null ? null : latestTask.get("versionNo")),
                "--");
        String taskStatus = stringValue(latestTask == null ? null : latestTask.get("status"));
        String taskMessage = stringValue(latestTask == null ? null : latestTask.get("resultMessage"));

        result.put("operationId", operationId);
        result.put("versionNo", versionNo);
        result.put("rawStatus", firstNonBlank(snapshotStatus, taskStatus, "IDLE"));
        result.put("phase", phase == null ? "" : phase);
        result.put("message", resolveMessage(snapshotStatus, phase, taskMessage, buildStatus));
        result.put("statusText", resolveStatusText(snapshotStatus, phase, taskStatus, taskMessage));
        result.put("statusType", resolveStatusType(snapshotStatus, phase, taskStatus, taskMessage));
        result.put("connected", true);
        result.put("updatedAt", firstNonBlank(
                stringValue(latestTask == null ? null : latestTask.get("updateTime")),
                stringValue(latestTask == null ? null : latestTask.get("endTime")),
                stringValue(latestTask == null ? null : latestTask.get("startTime")),
                ""));
    }

    private String resolveStatusText(String snapshotStatus, String phase, String taskStatus, String taskMessage) {
        if ("RUNNING".equalsIgnoreCase(snapshotStatus)) {
            if ("PULLING".equalsIgnoreCase(phase)) {
                return "拉取中";
            }
            if ("BUILDING".equalsIgnoreCase(phase)) {
                return "编译中";
            }
            return "执行中";
        }

        if ("SUCCESS".equalsIgnoreCase(taskStatus)) {
            return "成功";
        }
        if ("FAILED".equalsIgnoreCase(taskStatus)) {
            if (taskMessage != null && taskMessage.contains("拉取阶段失败")) {
                return "拉取失败";
            }
            if (taskMessage != null && taskMessage.contains("编译失败")) {
                return "编译失败";
            }
            return "执行失败";
        }
        if ("RUNNING".equalsIgnoreCase(taskStatus)) {
            if ("PULLING".equalsIgnoreCase(phase)) {
                return "拉取中";
            }
            if ("BUILDING".equalsIgnoreCase(phase)) {
                return "编译中";
            }
            return "执行中";
        }
        return "未执行";
    }

    private String resolveStatusType(String snapshotStatus, String phase, String taskStatus, String taskMessage) {
        if ("RUNNING".equalsIgnoreCase(snapshotStatus)) {
            return "running";
        }
        if ("SUCCESS".equalsIgnoreCase(taskStatus)) {
            return "success";
        }
        if ("FAILED".equalsIgnoreCase(taskStatus)) {
            if (taskMessage != null && taskMessage.contains("拉取阶段失败")) {
                return "error";
            }
            if (taskMessage != null && taskMessage.contains("编译失败")) {
                return "error";
            }
            return "error";
        }
        if ("RUNNING".equalsIgnoreCase(taskStatus)) {
            return "running";
        }
        if ("IDLE".equalsIgnoreCase(snapshotStatus) || taskStatus == null || taskStatus.isBlank()) {
            return "idle";
        }
        return "unknown";
    }

    private String resolveMessage(String snapshotStatus, String phase, String taskMessage, Map<String, Object> buildStatus) {
        if ("RUNNING".equalsIgnoreCase(snapshotStatus)) {
            if ("PULLING".equalsIgnoreCase(phase)) {
                return "最近一次任务正在拉取代码";
            }
            if ("BUILDING".equalsIgnoreCase(phase)) {
                return "最近一次任务正在按批次编译";
            }
            return "最近一次任务正在执行";
        }
        return firstNonBlank(
                taskMessage,
                stringValue(buildStatus == null ? null : buildStatus.get("finishMessage")),
                "");
    }

    private Map<String, Object> fetchMapData(String path) throws Exception {
        Object data = fetchData(path);
        if (data instanceof Map<?, ?> mapData) {
            return objectMapper.convertValue(mapData, new TypeReference<Map<String, Object>>() {});
        }
        return Map.of();
    }

    private Map<String, Object> fetchLatestTask() throws Exception {
        Object data = fetchData("/api/build/records/recent");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(list.get(0), new TypeReference<Map<String, Object>>() {});
    }

    private Object fetchData(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(benchmarkBaseUrl + path))
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + path);
        }

        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        Object code = body.get("code");
        if (!(code instanceof Number) || ((Number) code).intValue() != 200) {
            throw new IllegalStateException("业务返回失败: " + firstNonBlank(stringValue(body.get("message")), path));
        }
        return body.get("data");
    }

    private Map<String, Object> baseResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", "axon-link-server");
        result.put("branch", "Master");
        result.put("versionNo", "--");
        result.put("statusText", "未执行");
        result.put("statusType", "idle");
        result.put("message", "");
        result.put("operationId", "");
        result.put("updatedAt", "");
        result.put("connected", false);
        return result;
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8080";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
