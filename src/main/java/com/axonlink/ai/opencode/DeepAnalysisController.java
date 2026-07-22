package com.axonlink.ai.opencode;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 深度分析入口（路径②）。
 * NDJSON 流式协议与 /api/ai/code-explain/stream 同构：start/delta/tool/fallback/done/error。
 */
@RestController
@RequestMapping("/api/ai")
public class DeepAnalysisController {

    private final DeepAnalysisService deepAnalysisService;

    public DeepAnalysisController(DeepAnalysisService deepAnalysisService) {
        this.deepAnalysisService = deepAnalysisService;
    }

    @PostMapping(value = "/transactions/{txId}/deep-analysis/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> streamDeepAnalysis(@PathVariable String txId,
                                                                    @RequestBody(required = false) DeepAnalysisRequest request) {
        StreamingResponseBody body = outputStream ->
                deepAnalysisService.streamDeepAnalyze(txId, request, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .body(body);
    }
}
