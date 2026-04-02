package com.axonlink.ai.tool;

import com.axonlink.service.FlowtranService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * 图谱上下文工具。
 */
@Component
public class GraphAnalysisTool {

    private final FlowtranService flowtranService;

    public GraphAnalysisTool(FlowtranService flowtranService) {
        this.flowtranService = flowtranService;
    }

    public Map<String, Object> loadTransactionChain(String txId) {
        Map<String, Object> chain = flowtranService.getChain(txId);
        return chain == null ? Collections.emptyMap() : chain;
    }
}
