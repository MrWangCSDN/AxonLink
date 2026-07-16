package com.axonlink.ai.opencode;

import com.axonlink.ai.context.AnalysisContextService;
import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.AnalysisResponse;
import com.axonlink.ai.orchestrator.AnalysisOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 深度分析编排单测：mock Gateway/ContextService/Orchestrator。
 *
 * <p>text 事件按 PoC 校准结论是纯增量（见 {@link OpencodeEvent#text}），
 * 不做「累积再取差集」；正常流转场景直接断言每条 delta 事件的增量内容。
 */
class DeepAnalysisServiceTest {

    private OpencodeProperties props;
    private OpencodeGateway gateway;
    private AnalysisContextService contextService;
    private AnalysisOrchestrator orchestrator;
    private DeepAnalysisService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = new OpencodeProperties();
        props.setEnabled(true);
        props.setTimeoutSeconds(10);
        gateway = mock(OpencodeGateway.class);
        contextService = mock(AnalysisContextService.class);
        orchestrator = mock(AnalysisOrchestrator.class);
        service = new DeepAnalysisService(props, gateway, new OpencodeProtocol(mapper),
                contextService, orchestrator, mapper);
    }

    private List<JsonNode> runAndParse(String txId, DeepAnalysisRequest req) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamDeepAnalyze(txId, req, out);
        List<JsonNode> lines = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(mapper.readTree(line));
            }
        }
        return lines;
    }

    @Test
    void disabled_fallsBackToOrchestrator() throws Exception {
        props.setEnabled(false);
        AnalysisResponse resp = new AnalysisResponse();
        resp.setSummary("单轮总览");
        resp.setBusinessSummary("业务");
        resp.setTechnicalSummary("技术");
        when(orchestrator.analyzeTransaction(eq("TX1"), any())).thenReturn(resp);

        List<JsonNode> events = runAndParse("TX1", new DeepAnalysisRequest());

        assertEquals("start", events.get(0).get("type").asText());
        assertEquals("fallback", events.get(1).get("type").asText());
        assertEquals("done", events.get(2).get("type").asText());
        assertTrue(events.get(2).get("content").asText().contains("单轮总览"));
        verify(gateway, never()).createSession();
    }

    @Test
    void unhealthy_fallsBackToOrchestrator() throws Exception {
        when(gateway.isHealthy()).thenReturn(false);
        when(orchestrator.analyzeTransaction(eq("TX1"), any())).thenReturn(new AnalysisResponse());

        List<JsonNode> events = runAndParse("TX1", new DeepAnalysisRequest());

        assertEquals("fallback", events.get(1).get("type").asText());
        verify(gateway, never()).createSession();
    }

    @Test
    void normalFlow_forwardsTextAndToolEvents_thenDone_andDeletesSession() throws Exception {
        when(gateway.isHealthy()).thenReturn(true);
        when(gateway.createSession()).thenReturn("ses_1");
        AnalysisContext ctx = new AnalysisContext();
        when(contextService.buildTransactionContext(eq("TX1"), anyString(), any(AnalysisRequest.class)))
                .thenReturn(ctx);
        // 模拟事件流：两条纯增量 text（"你好" / "，世界"）+ 一个 tool 事件 + done
        doAnswer(inv -> {
            Consumer<OpencodeEvent> sink = inv.getArgument(2);
            sink.accept(OpencodeEvent.text("ses_1", "你好"));
            sink.accept(OpencodeEvent.tool("ses_1", "read", "running"));
            sink.accept(OpencodeEvent.text("ses_1", "，世界"));
            sink.accept(OpencodeEvent.done("ses_1"));
            return null;
        }).when(gateway).streamPrompt(eq("ses_1"), anyString(), any(), any(Duration.class));

        DeepAnalysisRequest req = new DeepAnalysisRequest();
        req.setQuestion("这支交易做什么？");
        List<JsonNode> events = runAndParse("TX1", req);

        assertEquals("start", events.get(0).get("type").asText());
        assertEquals("delta", events.get(1).get("type").asText());
        assertEquals("你好", events.get(1).get("content").asText());
        assertEquals("tool", events.get(2).get("type").asText());
        assertEquals("read", events.get(2).get("tool").asText());
        assertEquals("delta", events.get(3).get("type").asText());
        assertEquals("，世界", events.get(3).get("content").asText());
        assertEquals("done", events.get(4).get("type").asText());
        assertEquals("你好，世界", events.get(4).get("content").asText());
        verify(gateway).deleteSession("ses_1");
    }

    @Test
    void gatewayFailure_writesErrorEvent_andDeletesSession() throws Exception {
        when(gateway.isHealthy()).thenReturn(true);
        when(gateway.createSession()).thenReturn("ses_1");
        when(contextService.buildTransactionContext(anyString(), anyString(), any()))
                .thenReturn(new AnalysisContext());
        doThrow(new java.io.IOException("连接被拒绝"))
                .when(gateway).streamPrompt(anyString(), anyString(), any(), any());

        List<JsonNode> events = runAndParse("TX1", new DeepAnalysisRequest());

        JsonNode last = events.get(events.size() - 1);
        assertEquals("error", last.get("type").asText());
        assertTrue(last.get("message").asText().contains("连接被拒绝"));
        verify(gateway).deleteSession("ses_1");
    }
}
