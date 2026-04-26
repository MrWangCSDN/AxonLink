package com.axonlink.service;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向 sunline-benchmark 回写异步构建状态。
 */
@Service
public class BenchmarkAsyncBuildReporter {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkAsyncBuildReporter.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String benchmarkBaseUrl;
    private final Duration timeout;

    public BenchmarkAsyncBuildReporter(
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

    public void reportRunning(String operationId) {
        reportStatus(operationId, "异步构建中");
    }

    public void reportPhase(String operationId, String phase) {
        reportStatus(operationId, phase);
    }

    public void reportSuccess(String operationId) {
        reportStatus(operationId, "成功");
    }

    public void reportFailure(String operationId, String phaseOrReason) {
        if (isBlank(phaseOrReason)) {
            reportStatus(operationId, "失败:error");
            return;
        }
        if (phaseOrReason.startsWith("失败")) {
            reportStatus(operationId, phaseOrReason);
            return;
        }
        reportStatus(operationId, "失败:" + phaseOrReason);
    }

    public void reportStatus(String operationId, String status) {
        if (isBlank(operationId) || isBlank(status)) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("operationId", operationId);
            payload.put("status", status);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(benchmarkBaseUrl + "/api/build/async-build/status"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                log.warn("[BenchmarkAsyncBuildReporter] 回写失败: operationId={} status={} http={} body={}",
                        operationId, status, statusCode, response.body());
                return;
            }
            log.info("[BenchmarkAsyncBuildReporter] 回写成功: operationId={} status={}", operationId, status);
        } catch (Exception e) {
            log.warn("[BenchmarkAsyncBuildReporter] 回写异常: operationId={} status={} reason={}",
                    operationId, status, e.getMessage());
        }
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8080";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
