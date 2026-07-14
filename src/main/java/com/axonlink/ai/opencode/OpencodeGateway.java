package com.axonlink.ai.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * opencode serve REST/SSE 瘦客户端（深度分析路径）。
 *
 * <p>只封装用到的端点：POST /session、POST /session/{id}/prompt_async、GET /event、
 * GET /config（健康检查）、DELETE /session/{id}。
 * 「详细设计文档生成」功能落地时在此补同步阻塞方法（POST /session/{id}/message），两功能共用本类。
 */
@Component
public class OpencodeGateway {

    private static final Logger log = LoggerFactory.getLogger(OpencodeGateway.class);

    private final OpencodeProperties props;
    private final OpencodeProtocol protocol;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpencodeGateway(OpencodeProperties props, OpencodeProtocol protocol, ObjectMapper objectMapper) {
        this.props = props;
        this.protocol = protocol;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                .build();
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .header("Content-Type", "application/json");
        if (props.getPassword() != null && !props.getPassword().isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        return b;
    }

    /** 创建会话，返回 sessionId。 */
    public String createSession() throws IOException, InterruptedException {
        HttpResponse<String> resp = httpClient.send(
                request("/session").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("opencode 创建 session 失败: HTTP " + resp.statusCode());
        }
        String id = objectMapper.readTree(resp.body()).path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new IOException("opencode 创建 session 响应缺少 id: " + resp.body());
        }
        return id;
    }

    /** 删除会话；失败仅记日志（会话泄漏由定期清理兜底）。 */
    public void deleteSession(String sessionId) {
        try {
            httpClient.send(request("/session/" + sessionId).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("[opencode] 删除 session {} 失败（忽略）：{}", sessionId, e.getMessage());
        }
    }

    /** 健康检查：GET /config 返回 2xx 即认为可用。 */
    public boolean isHealthy() {
        try {
            HttpResponse<Void> resp = httpClient.send(
                    request("/config").GET().timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 发送 prompt 并消费本 session 的事件流，直到终止事件或整体超时。
     *
     * <p>顺序：先建立 /event 长连接（避免漏掉早期事件），再异步发 prompt_async，
     * 当前线程逐行读事件；结束/超时后关闭流释放连接。
     *
     * @param sink 事件回调（仅本 session 的 TEXT/TOOL/DONE/ERROR；OTHER 与他人 session 已过滤）
     */
    public void streamPrompt(String sessionId, String promptJson,
                             Consumer<OpencodeEvent> sink, Duration overallTimeout) throws IOException {
        long deadline = System.nanoTime() + overallTimeout.toNanos();
        HttpResponse<InputStream> eventResp;
        try {
            eventResp = httpClient.send(request("/event").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("订阅 opencode 事件流被中断", e);
        }
        if (eventResp.statusCode() / 100 != 2) {
            throw new IOException("订阅 opencode 事件流失败: HTTP " + eventResp.statusCode());
        }

        // 异步发 prompt：事件流已就绪后再发，响应体不在此消费（结果经事件流回来）
        CompletableFuture<HttpResponse<String>> promptFuture = httpClient.sendAsync(
                request("/session/" + sessionId + "/prompt_async")
                        .POST(HttpRequest.BodyPublishers.ofString(promptJson)).build(),
                HttpResponse.BodyHandlers.ofString());
        promptFuture.whenComplete((r, t) -> {
            if (t != null) {
                log.warn("[opencode] prompt 请求异常：{}", t.getMessage());
            } else if (r.statusCode() / 100 != 2) {
                log.warn("[opencode] prompt 返回 HTTP {}: {}", r.statusCode(), r.body());
            }
        });

        try (InputStream in = eventResp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("深度分析超时（" + overallTimeout.toSeconds() + "s）");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                OpencodeEvent event = protocol.parseEvent(line.substring(5).trim());
                if (event.getKind() == OpencodeEvent.Kind.OTHER
                        || !sessionId.equals(event.getSessionId())) {
                    continue;
                }
                sink.accept(event);
                if (event.isTerminal()) {
                    return;
                }
            }
            throw new IOException("opencode 事件流提前结束（未收到终止事件）");
        }
    }
}
