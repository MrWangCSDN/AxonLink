package com.axonlink.ai.orchestrator;

import com.axonlink.ai.cache.AnalysisCacheService;
import com.axonlink.ai.context.AnalysisContextService;
import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.AnalysisResponse;
import com.axonlink.ai.dto.LlmResult;
import com.axonlink.ai.prompt.PromptTemplateService;
import com.axonlink.ai.provider.LlmClient;
import com.axonlink.ai.rule.RuleEngineService;
import com.axonlink.ai.session.AnalysisSessionService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 分析编排入口。
 */
@Service
public class AnalysisOrchestrator {

    private final AnalysisCacheService analysisCacheService;
    private final AnalysisContextService analysisContextService;
    private final RuleEngineService ruleEngineService;
    private final PromptTemplateService promptTemplateService;
    private final LlmClient llmClient;
    private final AnalysisSessionService analysisSessionService;

    public AnalysisOrchestrator(AnalysisCacheService analysisCacheService,
                                AnalysisContextService analysisContextService,
                                RuleEngineService ruleEngineService,
                                PromptTemplateService promptTemplateService,
                                LlmClient llmClient,
                                AnalysisSessionService analysisSessionService) {
        this.analysisCacheService = analysisCacheService;
        this.analysisContextService = analysisContextService;
        this.ruleEngineService = ruleEngineService;
        this.promptTemplateService = promptTemplateService;
        this.llmClient = llmClient;
        this.analysisSessionService = analysisSessionService;
    }

    public AnalysisResponse analyzeTransaction(String txId, AnalysisRequest request) {
        AnalysisRequest safeRequest = request == null ? new AnalysisRequest() : request;

        Optional<AnalysisResponse> cached = analysisCacheService.get(txId, safeRequest);
        if (cached.isPresent()) {
            AnalysisResponse response = cached.get();
            response.setCached(true);
            return response;
        }

        String sessionId = analysisSessionService.ensureSessionId(safeRequest.getSessionId());
        AnalysisContext context = analysisContextService.buildTransactionContext(txId, sessionId, safeRequest);
        context.setRuleFindings(ruleEngineService.run(context));

        AnalysisPrompt prompt = promptTemplateService.buildPrompt(context, context.getRuleFindings());
        LlmResult llmResult = llmClient.analyze(prompt, context.getMode(), safeRequest);

        analysisSessionService.remember(sessionId, context, safeRequest);

        AnalysisResponse response = new AnalysisResponse();
        response.setTxId(txId);
        response.setSessionId(sessionId);
        response.setMode(context.getMode().name());
        response.setCached(false);
        response.setModel(llmResult.getModel());
        response.setSummary(llmResult.getSummary());
        response.setBusinessSummary(llmResult.getBusinessSummary());
        response.setTechnicalSummary(llmResult.getTechnicalSummary());
        response.setFindings(context.getRuleFindings());
        response.setCodeSnippets(context.getCodeSnippets());
        response.setChain(context.getChain());
        response.setContextStats(buildContextStats(context, llmResult));

        analysisCacheService.put(txId, safeRequest, response);
        return response;
    }

    private Map<String, Object> buildContextStats(AnalysisContext context, LlmResult llmResult) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("mode", context.getMode().name());
        stats.put("snippetCount", context.getCodeSnippets().size());
        stats.put("findingCount", context.getRuleFindings().size());
        stats.put("selectedPath", context.getSelectedPath());
        stats.put("metadata", context.getMetadata());
        stats.put("model", llmResult.getModel());
        return stats;
    }
}
