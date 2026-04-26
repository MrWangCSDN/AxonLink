package com.axonlink.ai.provider;

import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.LlmResult;
import com.axonlink.config.AiAnalysisConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

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
 * 兼容 OpenAI 协议的模型客户端。
 *
 * <p>当前默认复用 OpenAI 兼容聊天协议，
 * 既可对接内网 GLM5，也可对接阿里云 DashScope 等兼容接口。
 */
@Service
public class Glm5Client implements LlmClient {

    private final AiAnalysisConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public Glm5Client(AiAnalysisConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, config.getRequestTimeoutSeconds())))
                .build();
    }

    @Override
    public LlmResult analyze(AnalysisPrompt prompt, AnalysisMode mode, AnalysisRequest request) {
        if (!config.isEnabled() || isBlank(config.getGlm5().getBaseUrl())) {
            return fallback("模型 Provider 尚未启用，当前返回骨架结果。", prompt);
        }

        // analyze() 要返回完整 LlmResult：
        //   - stream=false：一次拿整段 JSON；
        //   - stream=true： 走 SSE，内部累积 delta，最终汇总成 LlmResult。
        // 底层模式由 yml 里 ai.analysis.glm5.stream 决定。
        boolean useStream = config.getGlm5().isStream();
        try {
            String content = useStream
                    ? doStreamRequestAndCollect(prompt)
                    : doBlockingRequest(prompt);

            LlmResult result = new LlmResult();
            result.setModel(config.getGlm5().getModel());
            result.setSummary(extractSection(content, "总览", 400));
            result.setBusinessSummary(extractSection(content, "业务解读", 1200));
            result.setTechnicalSummary(extractSection(content, "技术检查", 1200));
            result.setRawText(content);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void stream(AnalysisPrompt prompt, Consumer<String> onDelta) {
        if (!config.isEnabled() || isBlank(config.getGlm5().getBaseUrl())) {
            onDelta.accept("模型 Provider 尚未启用，当前无法生成流式解读。");
            return;
        }

        // stream() 始终"逐 delta 回调"，但底层协议由配置决定：
        //   - stream=true： SSE，按 token/chunk 实时触发 onDelta（前端可见逐字出现效果）；
        //   - stream=false：非流式一次拿整段，触发一次 onDelta 把整段给调用方。
        boolean useStream = config.getGlm5().isStream();
        try {
            if (useStream) {
                doStreamRequest(prompt, onDelta);
            } else {
                String content = doBlockingRequest(prompt);
                if (!isBlank(content)) {
                    onDelta.accept(content);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        }
    }

    /** 非流式：一次拿整段 JSON，返回 assistant.content。 */
    private String doBlockingRequest(AnalysisPrompt prompt) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatUrl()))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (!isBlank(config.getGlm5().getApiKey())) {
            builder.header("Authorization", "Bearer " + config.getGlm5().getApiKey());
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt, false)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "模型请求失败，HTTP " + response.statusCode() + "，响应：" + response.body());
        }
        return extractContent(response.body());
    }

    /** 流式：SSE 逐 delta 触发 onDelta。 */
    private void doStreamRequest(AnalysisPrompt prompt, Consumer<String> onDelta) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatUrl()))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        if (!isBlank(config.getGlm5().getApiKey())) {
            builder.header("Authorization", "Bearer " + config.getGlm5().getApiKey());
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt, true)))
                .build();

        HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = readAll(response.body());
            throw new IllegalStateException(
                    "模型流式请求失败，HTTP " + response.statusCode() + "，响应：" + errorBody);
        }
        consumeStream(response.body(), onDelta);
    }

    /** 流式：用于 analyze() 内部累积，拿到整段文本后返回。 */
    private String doStreamRequestAndCollect(AnalysisPrompt prompt) throws IOException, InterruptedException {
        StringBuilder buf = new StringBuilder();
        doStreamRequest(prompt, delta -> {
            if (delta != null) {
                buf.append(delta);
            }
        });
        return buf.toString();
    }

    private String buildRequestBody(AnalysisPrompt prompt, boolean streamEnabled) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getGlm5().getModel());
        body.put("stream", streamEnabled);
        body.put("temperature", config.getGlm5().getTemperature());
        body.put("max_tokens", config.getGlm5().getMaxTokens());
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
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode root = objectMapper.readTree(data);
                String delta = extractStreamContent(root);
                if (!isBlank(delta)) {
                    onDelta.accept(delta);
                }
            }
        }
    }

    private String extractStreamContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode choice = choices.get(0);
        String content = normalizeContent(choice.path("delta").path("content"));
        if (!isBlank(content)) {
            return content;
        }
        content = normalizeContent(choice.path("message").path("content"));
        if (!isBlank(content)) {
            return content;
        }
        return "";
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("模型响应缺少 choices 字段，原文：" + truncateForError(responseBody));
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");

        // 优先读标准 content 字段
        String content = normalizeContent(message.path("content"));

        // 兼容 glm 系列：如果 content 空，尝试 reasoning_content（思考过程字段）
        if (isBlank(content)) {
            content = normalizeContent(message.path("reasoning_content"));
        }
        // 再兼容：部分网关把内容放在 text 或 output 字段
        if (isBlank(content)) {
            content = normalizeContent(message.path("text"));
        }
        if (isBlank(content)) {
            content = normalizeContent(choice.path("text"));
        }

        if (isBlank(content)) {
            // 诊断信息：finish_reason + usage + 完整响应片段，便于排查
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
                    + " | 可能原因：max_tokens 被思考过程耗尽（把 max_tokens 调到 4096+）/ 模型返回格式异常"
                    + " | 响应片段：" + truncateForError(responseBody));
        }
        return sanitizeAssistantContent(content);
    }

    /** 错误日志用：响应体可能很长，只截前 500 字符。 */
    private static String truncateForError(String s) {
        if (s == null) return "null";
        return s.length() <= 500 ? s : s.substring(0, 500) + "...<truncated>";
    }

    private String normalizeContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentNode) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isTextual()) {
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
        String baseUrl = config.getGlm5().getBaseUrl();
        String chatPath = config.getGlm5().getChatPath();
        if (isBlank(chatPath)) {
            chatPath = "/v1/chat/completions";
        }
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
        result.setModel(config.getGlm5().getModel());
        result.setSummary("AI 模型暂未返回正式解读结果：" + reason);
        result.setBusinessSummary("当前没有获取到正式的业务解读内容，请检查模型配置、接口连通性或重试。");
        result.setTechnicalSummary("当前没有获取到正式的技术检查内容，请检查模型配置、接口连通性或重试。");
        result.setRawText("""
                ## 解读失败说明
                当前没有从 AI 模型拿到正式解读结果。

                ## 可能原因
                1. 模型 Provider 未启用或配置不完整。
                2. 模型接口调用失败。
                3. 模型只返回了思考过程，没有返回正式答案。

                ## 建议
                1. 检查 AI 配置是否正确。
                2. 重新触发一次智能解读。
                3. 如仍失败，请查看后端日志中的模型请求异常信息。
                """);
        return result;
    }

    private String sanitizeAssistantContent(String content) {
        if (isBlank(content)) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").trim();
        normalized = normalized.replaceAll("(?is)<think>.*?</think>", "").trim();
        int markerIndex = normalized.indexOf("Thinking Process:");
        if (markerIndex == 0) {
            int answerStart = findAnswerStart(normalized);
            if (answerStart > 0) {
                normalized = normalized.substring(answerStart).trim();
            }
        }
        return normalized;
    }

    private int findAnswerStart(String content) {
        int best = Integer.MAX_VALUE;
        for (String marker : List.of("## ", "# ", "第一步：", "## 第一步", "## 总览", "## 业务解读", "## 结构拆解")) {
            int idx = content.indexOf(marker);
            if (idx > 0 && idx < best) {
                best = idx;
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private String extractSection(String content, String label, int fallbackLimit) {
        String marker = "## " + label;
        int start = content.indexOf(marker);
        if (start < 0) {
            return truncate(content, fallbackLimit);
        }
        int next = content.indexOf("## ", start + marker.length());
        if (next < 0) {
            return content.substring(start).trim();
        }
        return content.substring(start, next).trim();
    }

    private String truncate(String content, int limit) {
        if (content == null) {
            return "";
        }
        if (content.length() <= limit) {
            return content;
        }
        return content.substring(0, limit) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
