package com.axonlink.ai.context;

import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.tool.GraphAnalysisTool;
import com.axonlink.ai.tool.MetadataTool;
import com.axonlink.ai.tool.SourceSnippetTool;
import com.axonlink.config.AiAnalysisConfig;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 统一组装 AI 分析上下文。
 */
@Service
public class AnalysisContextService {

    private final GraphAnalysisTool graphAnalysisTool;
    private final SourceSnippetTool sourceSnippetTool;
    private final MetadataTool metadataTool;
    private final AiAnalysisConfig config;

    public AnalysisContextService(GraphAnalysisTool graphAnalysisTool,
                                  SourceSnippetTool sourceSnippetTool,
                                  MetadataTool metadataTool,
                                  AiAnalysisConfig config) {
        this.graphAnalysisTool = graphAnalysisTool;
        this.sourceSnippetTool = sourceSnippetTool;
        this.metadataTool = metadataTool;
        this.config = config;
    }

    public AnalysisContext buildTransactionContext(String txId, String sessionId, AnalysisRequest request) {
        Map<String, Object> chain = graphAnalysisTool.loadTransactionChain(txId);
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("交易不存在或链路为空：" + txId);
        }

        AnalysisContext context = new AnalysisContext();
        context.setTxId(txId);
        context.setSessionId(sessionId);
        context.setMode(request.resolveMode());
        context.setFocus(request.getFocus());
        context.setSelectedPath(request.getSelectedPath());
        context.setChain(chain);
        context.setMetadata(metadataTool.buildMetadata(txId, chain));

        if (request.isIncludeCode()) {
            int snippetLimit = request.getMaxCodeSnippets() > 0
                    ? request.getMaxCodeSnippets()
                    : config.getMaxCodeSnippets();
            context.setCodeSnippets(sourceSnippetTool.collectTransactionSnippets(
                    txId,
                    chain,
                    request.getSelectedPath(),
                    snippetLimit,
                    config.getMaxCodeCharsPerSnippet()
            ));
        }
        return context;
    }
}
