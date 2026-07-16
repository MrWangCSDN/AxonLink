package com.axonlink.ai.opencode;

import com.axonlink.ai.context.AnalysisContextService;
import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.AnalysisResponse;
import com.axonlink.ai.orchestrator.AnalysisOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 深度分析编排：图谱上下文注入 + opencode 多轮探索 + NDJSON 流式输出。
 *
 * <p>与路径①（{@link AnalysisOrchestrator} 单轮）的关系：opencode 关闭或不健康时
 * 自动降级到路径①，前端收到 fallback 事件提示「已用快速模式」。
 * NDJSON 事件协议与 CodeExplainService 同构：start / delta / tool / fallback / done / error。
 *
 * <p>text 事件按 PoC 校准结论是纯增量（{@link OpencodeProtocol} 解析自 message.part.delta），
 * 不需要在本类做「累积再取差集」处理，直接透传即可。
 */
@Service
public class DeepAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DeepAnalysisService.class);

    private final OpencodeProperties props;
    private final OpencodeGateway gateway;
    private final OpencodeProtocol protocol;
    private final AnalysisContextService contextService;
    private final AnalysisOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public DeepAnalysisService(OpencodeProperties props,
                               OpencodeGateway gateway,
                               OpencodeProtocol protocol,
                               AnalysisContextService contextService,
                               AnalysisOrchestrator orchestrator,
                               ObjectMapper objectMapper) {
        this.props = props;
        this.gateway = gateway;
        this.protocol = protocol;
        this.contextService = contextService;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public void streamDeepAnalyze(String txId, DeepAnalysisRequest request, OutputStream out) throws IOException {
        DeepAnalysisRequest req = request == null ? new DeepAnalysisRequest() : request;
        writeEvent(out, "start", Map.of(
                "model", props.getProviderId() + "/" + props.getModelId(),
                "agent", props.getAgent(),
                "depth", "deep",
                "renderAs", "markdown"));

        if (!props.isEnabled() || !gateway.isHealthy()) {
            fallback(out, txId, req, props.isEnabled() ? "opencode 服务不可用" : "深度分析未启用");
            return;
        }

        String ocSession = null;
        try {
            String analysisSessionId = req.getSessionId() == null || req.getSessionId().isBlank()
                    ? UUID.randomUUID().toString() : req.getSessionId();
            // 主路径 includeCode=false：深度分析的 prompt 只用 chain/metadata，源码由 opencode
            // 用只读工具自行探索——预取代码片段（Neo4j 查询 + 文件读取）算完即弃，纯浪费
            AnalysisContext context = contextService.buildTransactionContext(
                    txId, analysisSessionId, toAnalysisRequest(req, false));
            String promptText = buildDeepPrompt(txId, context, req.getQuestion());

            ocSession = gateway.createSession();
            StringBuilder fullText = new StringBuilder();
            String promptJson = protocol.buildPromptBody(
                    props.getAgent(), props.getProviderId(), props.getModelId(), promptText);

            gateway.streamPrompt(ocSession, promptJson, event -> {
                try {
                    switch (event.getKind()) {
                        case TEXT -> {
                            // text 事件为纯增量（PoC 校准），直接透传，无需 tracker 累积/取差集
                            String delta = event.getText();
                            if (delta != null && !delta.isEmpty()) {
                                fullText.append(delta);
                                writeEvent(out, "delta", Map.of("content", delta));
                            }
                        }
                        case TOOL -> writeEvent(out, "tool", Map.of(
                                "tool", event.getToolName() == null ? "" : event.getToolName(),
                                "status", event.getToolStatus() == null ? "" : event.getToolStatus()));
                        case ERROR -> throw new IllegalStateException(
                                "opencode 会话错误：" + event.getErrorMessage());
                        default -> { /* DONE 由 streamPrompt 终止循环，无需写事件 */ }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("写入流式响应失败：" + e.getMessage(), e);
                }
            }, Duration.ofSeconds(props.getTimeoutSeconds()));

            writeEvent(out, "done", Map.of("content", fullText.toString(), "renderAs", "markdown"));
        } catch (Exception e) {
            log.warn("[deep-analysis] tx={} 深度分析失败：{}", txId, e.getMessage());
            writeEvent(out, "error", Map.of("message", String.valueOf(e.getMessage())));
        } finally {
            if (ocSession != null) {
                gateway.deleteSession(ocSession);
            }
        }
    }

    /** 降级：调路径①单轮分析，把结构化结果拼成 markdown 走 done 事件。 */
    private void fallback(OutputStream out, String txId, DeepAnalysisRequest req, String reason) throws IOException {
        writeEvent(out, "fallback", Map.of("reason", reason));
        try {
            // 降级路径 includeCode=true：路径①单轮分析把代码片段拼进 prompt，真正消费片段
            AnalysisResponse resp = orchestrator.analyzeTransaction(txId, toAnalysisRequest(req, true));
            StringBuilder md = new StringBuilder();
            if (notBlank(resp.getSummary())) {
                md.append("## 总览\n\n").append(resp.getSummary()).append("\n\n");
            }
            if (notBlank(resp.getBusinessSummary())) {
                md.append("## 业务解读\n\n").append(resp.getBusinessSummary()).append("\n\n");
            }
            if (notBlank(resp.getTechnicalSummary())) {
                md.append("## 技术检查\n\n").append(resp.getTechnicalSummary()).append("\n");
            }
            writeEvent(out, "done", Map.of("content", md.toString(), "renderAs", "markdown"));
        } catch (Exception e) {
            writeEvent(out, "error", Map.of("message", "降级分析也失败了：" + e.getMessage()));
        }
    }

    /**
     * 转成路径①的请求对象。
     *
     * @param includeCode 是否让 {@link AnalysisContextService} 预取代码片段。两条路径语义不同：
     *                    主路径传 false（prompt 只用 chain/metadata，源码由 opencode 自行探索，
     *                    预取即弃）；降级路径传 true（{@link AnalysisOrchestrator} 单轮分析
     *                    要把片段拼进 prompt）。
     */
    private AnalysisRequest toAnalysisRequest(DeepAnalysisRequest req, boolean includeCode) {
        AnalysisRequest r = new AnalysisRequest();
        r.setSessionId(req.getSessionId());
        r.setFocus(req.getQuestion() == null ? "" : req.getQuestion());
        r.setIncludeCode(includeCode);
        return r;
    }

    /** 组装深度分析 prompt：图谱上下文 + 探索指引 + 用户问题。 */
    private String buildDeepPrompt(String txId, AnalysisContext context, String question) {
        Map<String, Object> graphContext = new LinkedHashMap<>();
        graphContext.put("txId", txId);
        graphContext.put("chain", context.getChain());
        graphContext.put("metadata", context.getMetadata());
        String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(graphContext);
        } catch (Exception e) {
            log.warn("[deep-analysis] tx={} 图谱上下文序列化失败，prompt 退化为仅含 txId：{}", txId, e.getMessage());
            contextJson = "{\"txId\":\"" + txId + "\"}";
        }
        String q = (question == null || question.isBlank())
                ? "请解读这支交易的业务目的、各层职责、数据流向与潜在技术风险。" : question;
        return "以下是交易 " + txId + " 的图谱上下文（调用链与元数据，JSON）：\n\n"
                + contextJson
                + "\n\n请基于图谱上下文，用只读工具（read/grep/glob）在当前代码仓库中定位并阅读相关源码，"
                + "多轮探索后回答问题。要求：结论有代码依据（引用文件路径），用中文 markdown 输出。\n\n"
                + "问题：" + q;
    }

    private void writeEvent(OutputStream out, String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.putAll(payload);
        out.write(objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
