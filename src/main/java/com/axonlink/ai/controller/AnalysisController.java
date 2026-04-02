package com.axonlink.ai.controller;

import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.AnalysisResponse;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.CodeExplainRequest;
import com.axonlink.ai.dto.FlowtransTagCatalogResponse;
import com.axonlink.ai.orchestrator.AnalysisOrchestrator;
import com.axonlink.ai.prompt.CodeExplainPromptService;
import com.axonlink.ai.prompt.FlowtransTagCatalogService;
import com.axonlink.ai.service.CodeExplainService;
import com.axonlink.common.R;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * AI 代码解读接口。
 */
@RestController
@RequestMapping("/api/ai")
public class AnalysisController {

    private final AnalysisOrchestrator analysisOrchestrator;
    private final CodeExplainService codeExplainService;
    private final CodeExplainPromptService codeExplainPromptService;
    private final FlowtransTagCatalogService flowtransTagCatalogService;

    public AnalysisController(AnalysisOrchestrator analysisOrchestrator,
                              CodeExplainService codeExplainService,
                              CodeExplainPromptService codeExplainPromptService,
                              FlowtransTagCatalogService flowtransTagCatalogService) {
        this.analysisOrchestrator = analysisOrchestrator;
        this.codeExplainService = codeExplainService;
        this.codeExplainPromptService = codeExplainPromptService;
        this.flowtransTagCatalogService = flowtransTagCatalogService;
    }

    @PostMapping("/transactions/{txId}/analysis")
    public R<AnalysisResponse> analyzeTransaction(@PathVariable String txId,
                                                  @RequestBody(required = false) AnalysisRequest request) {
        try {
            return R.ok(analysisOrchestrator.analyzeTransaction(txId, request));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            return R.fail("AI 分析失败：" + e.getMessage());
        }
    }

    @PostMapping(value = "/code-explain/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> streamCodeExplain(@RequestBody(required = false) CodeExplainRequest request) {
        StreamingResponseBody body = outputStream -> codeExplainService.streamExplain(request, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .body(body);
    }

    @PostMapping("/code-explain/prompt-preview")
    public R<AnalysisPrompt> previewCodeExplainPrompt(@RequestBody(required = false) CodeExplainRequest request) {
        return R.ok(codeExplainPromptService.buildPrompt(request == null ? new CodeExplainRequest() : request));
    }

    @GetMapping("/flowtrans/tag-catalog")
    public R<FlowtransTagCatalogResponse> getFlowtransTagCatalog() {
        return R.ok(flowtransTagCatalogService.getCatalog());
    }
}
