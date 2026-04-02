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

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(resolveChatUrl()))
                    .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (!isBlank(config.getGlm5().getApiKey())) {
                builder.header("Authorization", "Bearer " + config.getGlm5().getApiKey());
            }
            HttpRequest httpRequest = builder
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt, config.getGlm5().isStream())))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("模型请求失败，HTTP " + response.statusCode() + "，响应：" + response.body());
            }

            String content = extractContent(response.body());
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
        try {
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
                throw new IllegalStateException("模型流式请求失败，HTTP " + response.statusCode() + "，响应：" + errorBody);
            }
            consumeStream(response.body(), onDelta);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("调用模型失败：" + e.getMessage(), e);
        }
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
            throw new IllegalStateException("模型响应缺少 choices 字段");
        }
        JsonNode contentNode = choices.get(0).path("message").path("content");
        String content = normalizeContent(contentNode);
        if (isBlank(content)) {
            throw new IllegalStateException("模型响应未返回 message.content");
        }
        return sanitizeAssistantContent(content);
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
