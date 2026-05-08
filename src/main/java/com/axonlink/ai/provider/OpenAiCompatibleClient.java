package com.axonlink.ai.provider;

import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.LlmResult;
import com.axonlink.config.AiAnalysisConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议的通用模型客户端基类。
 *
 * <p>抽出 Glm5 / MiniMax 等"OpenAI 兼容协议"下的所有 HTTP / SSE / 解析 / fallback 逻辑，
 * 子类（{@link Glm5Client} / {@link MiniMaxClient}）只需提供 {@link OpenAiCompatibleConfig} 即可。
 */
public abstract class OpenAiCompatibleClient implements LlmClient {

    protected final AiAnalysisConfig globalConfig;
    protected final ObjectMapper objectMapper;

    /**
     * HttpClient 实例。
     * <p>用 volatile 而不是 final，因为 JDK HttpClient 的内部 SelectorManager 守护线程
     * 在某些场景（线程中断、GC、JDK 17 早期 bug 等）会永久退出，整个实例随之报废，
     * 后续所有 send() 会立即抛 {@code IllegalStateException: selector manager closed}。
     * 检测到该异常后，{@link #resetHttpClientIfBroken} 会把该字段置 null，
     * 下次调用时由 {@link #getHttpClient()} 懒重建。
     */
    private volatile HttpClient httpClient;

    protected OpenAiCompatibleClient(AiAnalysisConfig globalConfig, ObjectMapper objectMapper) {
        this.globalConfig = globalConfig;
        this.objectMapper = objectMapper;
        this.httpClient = newHttpClient();
    }

    private HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, globalConfig.getRequestTimeoutSeconds())))
                .build();
    }

    /** 双重检查懒加载，并发安全。 */
    protected HttpClient getHttpClient() {
        HttpClient c = this.httpClient;
        if (c == null) {
            synchronized (this) {
                c = this.httpClient;
                if (c == null) {
                    c = newHttpClient();
                    this.httpClient = c;
                    org.slf4j.LoggerFactory.getLogger(getClass())
                            .info("[LLM] HttpClient 已重建（前一个实例的 SelectorManager 异常退出）");
                }
            }
        }
        return c;
    }

    /**
     * 如果异常是 "selector manager closed"，把当前 httpClient 置 null，
     * 下次调用 {@link #getHttpClient()} 时会自动新建。
     * <p>外层 {@link com.axonlink.ai.daoindex.sqlinspect.llm.SqlLlmAnalyzeService} 的重试机制
     * 会再调一次 analyze()，新调用拿到的就是全新的 HttpClient，从而自愈。
     */
    private void resetHttpClientIfBroken(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("selector manager closed")) {
                synchronized (this) {
                    this.httpClient = null;
                }
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("[LLM] 检测到 selector manager closed，已废弃当前 HttpClient，下次调用将重建");
                return;
            }
            cur = cur.getCause();
        }
    }

    /** 子类暴露自己的 provider 配置（base-url / api-key / model 等）。 */
    protected abstract OpenAiCompatibleConfig cfg();

    @Override
    public LlmResult analyze(AnalysisPrompt prompt, AnalysisMode mode, AnalysisRequest request) {
        if (!globalConfig.isEnabled() || isBlank(cfg().getBaseUrl())) {
            return fallback("模型 Provider 尚未启用，当前返回骨架结果。", prompt);
        }
        boolean useStream = cfg().isStream();
        try {
            String content = useStream ? doStreamRequestAndCollect(prompt) : doBlockingRequest(prompt);
            LlmResult result = new LlmResult();
            result.setModel(cfg().getModel());
            result.setSummary(extractSection(content, "总览", 400));
            result.setBusinessSummary(extractSection(content, "业务解读", 1200));
            result.setTechnicalSummary(extractSection(content, "技术检查", 1200));
            result.setRawText(content);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        } catch (IOException e) {
            resetHttpClientIfBroken(e);
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        } catch (IllegalStateException e) {
            // HttpClient.send() 在 SelectorManager 死掉后会直接抛 IllegalStateException，
            // 不走 IOException 路径。要在这里也兜一下检测。
            resetHttpClientIfBroken(e);
            throw e;
        }
    }

    @Override
    public void stream(AnalysisPrompt prompt, Consumer<String> onDelta) {
        if (!globalConfig.isEnabled() || isBlank(cfg().getBaseUrl())) {
            onDelta.accept("模型 Provider 尚未启用，当前无法生成流式解读。");
            return;
        }
        boolean useStream = cfg().isStream();
        try {
            if (useStream) {
                doStreamRequest(prompt, onDelta);
            } else {
                String content = doBlockingRequest(prompt);
                if (!isBlank(content)) onDelta.accept(content);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        } catch (IOException e) {
            resetHttpClientIfBroken(e);
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        } catch (IllegalStateException e) {
            resetHttpClientIfBroken(e);
            throw e;
        }
    }

    private String doBlockingRequest(AnalysisPrompt prompt) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatUrl()))
                .timeout(Duration.ofSeconds(globalConfig.getRequestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (!isBlank(cfg().getApiKey())) {
            builder.header("Authorization", "Bearer " + cfg().getApiKey());
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt, false)))
                .build();
        HttpResponse<String> response = getHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("模型请求失败，HTTP " + response.statusCode() + "，响应：" + response.body());
        }
        return extractContent(response.body());
    }

    private void doStreamRequest(AnalysisPrompt prompt, Consumer<String> onDelta) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatUrl()))
                .timeout(Duration.ofSeconds(globalConfig.getRequestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        if (!isBlank(cfg().getApiKey())) {
            builder.header("Authorization", "Bearer " + cfg().getApiKey());
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt, true)))
                .build();
        HttpResponse<InputStream> response = getHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = readAll(response.body());
            throw new IllegalStateException("模型流式请求失败，HTTP " + response.statusCode() + "，响应：" + errorBody);
        }
        consumeStream(response.body(), onDelta);
    }

    private String doStreamRequestAndCollect(AnalysisPrompt prompt) throws IOException, InterruptedException {
        StringBuilder buf = new StringBuilder();
        doStreamRequest(prompt, delta -> { if (delta != null) buf.append(delta); });
        return buf.toString();
    }

    private String buildRequestBody(AnalysisPrompt prompt, boolean streamEnabled) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg().getModel());
        body.put("stream", streamEnabled);
        body.put("temperature", cfg().getTemperature());
        body.put("max_tokens", cfg().getMaxTokens());
        // 关闭思考链时显式下发 enable_thinking=false（GLM-Z1 / DeepSeek-R1 兼容字段）
        // 上游服务不识别该字段一般会安全忽略，不影响调用
        if (!cfg().isEnableThinking()) {
            body.put("enable_thinking", false);
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.getSystemPrompt()),
                Map.of("role", "user", "content", prompt.getUserPrompt())
        ));
        return objectMapper.writeValueAsString(body);
    }

    private void consumeStream(InputStream inputStream, Consumer<String> onDelta) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue;
                String data = trimmed.substring("data:".length()).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                JsonNode root = objectMapper.readTree(data);
                String delta = extractStreamContent(root);
                if (!isBlank(delta)) onDelta.accept(delta);
            }
        }
    }

    private String extractStreamContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return "";
        JsonNode choice = choices.get(0);
        String content = normalizeContent(choice.path("delta").path("content"));
        if (!isBlank(content)) return content;
        content = normalizeContent(choice.path("message").path("content"));
        return isBlank(content) ? "" : content;
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("模型响应缺少 choices 字段，原文：" + truncateForError(responseBody));
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = normalizeContent(message.path("content"));
        if (isBlank(content)) content = normalizeContent(message.path("reasoning_content"));
        if (isBlank(content)) content = normalizeContent(message.path("text"));
        if (isBlank(content)) content = normalizeContent(choice.path("text"));
        if (isBlank(content)) {
            String finishReason = choice.path("finish_reason").asText("unknown");
            JsonNode usage = root.path("usage");
            String usageInfo = usage.isMissingNode() ? "n/a" :
                    String.format("prompt=%d completion=%d total=%d",
                            usage.path("prompt_tokens").asInt(0),
                            usage.path("completion_tokens").asInt(0),
                            usage.path("total_tokens").asInt(0));
            throw new IllegalStateException(
                    "模型响应未返回 message.content"
                    + " | finish_reason=" + finishReason
                    + " | usage=" + usageInfo
                    + " | 可能原因：max_tokens 被思考过程耗尽 / 模型返回格式异常"
                    + " | 响应片段：" + truncateForError(responseBody));
        }
        return sanitizeAssistantContent(content);
    }

    private static String truncateForError(String s) {
        if (s == null) return "null";
        return s.length() <= 500 ? s : s.substring(0, 500) + "...<truncated>";
    }

    private String normalizeContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) return "";
        if (contentNode.isTextual()) return contentNode.asText();
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentNode) {
                if (item == null || item.isNull()) continue;
                if (item.isTextual()) {
                    if (builder.length() > 0) builder.append('\n');
                    builder.append(item.asText());
                    continue;
                }
                JsonNode textNode = item.path("text");
                if (textNode.isTextual()) {
                    if (builder.length() > 0) builder.append('\n');
                    builder.append(textNode.asText());
                }
            }
            return builder.toString().trim();
        }
        return contentNode.toString();
    }

    private String resolveChatUrl() {
        String baseUrl = cfg().getBaseUrl();
        String chatPath = cfg().getChatPath();
        if (isBlank(chatPath)) chatPath = "/v1/chat/completions";
        if (baseUrl.endsWith("/") && chatPath.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + chatPath;
        }
        if (!baseUrl.endsWith("/") && !chatPath.startsWith("/")) {
            return baseUrl + "/" + chatPath;
        }
        return baseUrl + chatPath;
    }

    private String readAll(InputStream inputStream) throws IOException {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private LlmResult fallback(String reason, AnalysisPrompt prompt) {
        LlmResult result = new LlmResult();
        result.setModel(cfg().getModel());
        result.setSummary("AI 模型暂未返回正式解读结果：" + reason);
        result.setBusinessSummary("当前没有获取到正式的业务解读内容，请检查模型配置、接口连通性或重试。");
        result.setTechnicalSummary("当前没有获取到正式的技术检查内容，请检查模型配置、接口连通性或重试。");
        result.setRawText("## 解读失败说明\n当前没有从 AI 模型拿到正式解读结果。");
        return result;
    }

    private String sanitizeAssistantContent(String content) {
        if (isBlank(content)) return "";
        String normalized = content.replace("\r\n", "\n").trim();
        normalized = normalized.replaceAll("(?is)<think>.*?</think>", "").trim();
        int markerIndex = normalized.indexOf("Thinking Process:");
        if (markerIndex == 0) {
            int answerStart = findAnswerStart(normalized);
            if (answerStart > 0) normalized = normalized.substring(answerStart).trim();
        }
        return normalized;
    }

    private int findAnswerStart(String content) {
        int best = Integer.MAX_VALUE;
        for (String marker : List.of("## ", "# ", "第一步：", "## 第一步", "## 总览", "## 业务解读", "## 结构拆解")) {
            int idx = content.indexOf(marker);
            if (idx > 0 && idx < best) best = idx;
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private String extractSection(String content, String label, int fallbackLimit) {
        String marker = "## " + label;
        int start = content.indexOf(marker);
        if (start < 0) return truncate(content, fallbackLimit);
        int next = content.indexOf("## ", start + marker.length());
        if (next < 0) return content.substring(start).trim();
        return content.substring(start, next).trim();
    }

    private String truncate(String content, int limit) {
        if (content == null) return "";
        return content.length() <= limit ? content : content.substring(0, limit) + "...";
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
