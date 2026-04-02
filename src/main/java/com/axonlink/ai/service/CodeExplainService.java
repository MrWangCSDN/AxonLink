package com.axonlink.ai.service;

import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.LlmResult;
import com.axonlink.ai.dto.CodeExplainRequest;
import com.axonlink.ai.prompt.CodeExplainPromptService;
import com.axonlink.ai.provider.LlmClient;
import com.axonlink.config.AiAnalysisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码弹窗流式智能解读服务。
 */
@Service
public class CodeExplainService {

    private final CodeExplainPromptService promptService;
    private final LlmClient llmClient;
    private final AiAnalysisConfig config;
    private final ObjectMapper objectMapper;

    public CodeExplainService(CodeExplainPromptService promptService,
                              LlmClient llmClient,
                              AiAnalysisConfig config,
                              ObjectMapper objectMapper) {
        this.promptService = promptService;
        this.llmClient = llmClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public void streamExplain(CodeExplainRequest request, OutputStream outputStream) throws IOException {
        AnalysisPrompt prompt = promptService.buildPrompt(request == null ? new CodeExplainRequest() : request);
        StringBuilder fullText = new StringBuilder();
        Exception streamFailure = null;
        writeEvent(outputStream, "start", Map.of(
                "model", config.getGlm5().getModel(),
                "focus", safe(request == null ? null : request.getFocus()),
                "nodeCode", safe(request == null ? null : request.getNodeCode()),
                "renderAs", "markdown"
        ));

        try {
            llmClient.stream(prompt, delta -> {
                if (delta == null || delta.isBlank()) {
                    return;
                }
                fullText.append(delta);
                try {
                    writeEvent(outputStream, "delta", Map.of("content", delta));
                } catch (IOException e) {
                    throw new IllegalStateException("写入流式响应失败：" + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            streamFailure = e;
        }

        String finalContent = sanitizeExplainContent(fullText.toString());
        if (finalContent.isBlank() || looksLikeReasoningTrace(finalContent)) {
            try {
                LlmResult fallbackResult = llmClient.analyze(prompt, AnalysisMode.FULL, null);
                finalContent = sanitizeExplainContent(fallbackResult == null ? "" : fallbackResult.getRawText());
                if (!finalContent.isBlank()) {
                    writeEvent(outputStream, "done", Map.of(
                            "content", finalContent,
                            "model", safe(fallbackResult == null ? null : fallbackResult.getModel()),
                            "renderAs", "markdown"
                    ));
                    return;
                }
            } catch (Exception fallbackError) {
                if (streamFailure == null) {
                    streamFailure = fallbackError;
                }
            }
        }

        if (!finalContent.isBlank()) {
            writeEvent(outputStream, "done", Map.of(
                    "content", finalContent,
                    "model", config.getGlm5().getModel(),
                    "renderAs", "markdown"
            ));
            return;
        }

        writeEvent(outputStream, "error", Map.of(
                "message", streamFailure == null || streamFailure.getMessage() == null
                        ? "智能解读失败，模型未返回正式业务解读内容"
                        : streamFailure.getMessage()
        ));
    }

    private void writeEvent(OutputStream outputStream, String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.putAll(payload);
        outputStream.write(objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
        outputStream.write('\n');
        outputStream.flush();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeExplainContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").trim();
        normalized = normalized.replaceAll("(?is)<think>.*?</think>", "").trim();
        if (normalized.startsWith("Thinking Process:")) {
            int answerStart = findAnswerStart(normalized);
            if (answerStart > 0) {
                normalized = normalized.substring(answerStart).trim();
            }
        }
        return normalized;
    }

    private boolean looksLikeReasoningTrace(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.trim();
        return normalized.startsWith("Thinking Process:")
                || normalized.startsWith("思考过程：")
                || normalized.startsWith("分析过程：");
    }

    private int findAnswerStart(String content) {
        int best = Integer.MAX_VALUE;
        for (String marker : new String[]{"## ", "# ", "第一步：", "## 第一步", "## 总览", "## 业务解读", "## 结构拆解"}) {
            int idx = content.indexOf(marker);
            if (idx > 0 && idx < best) {
                best = idx;
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }
}
