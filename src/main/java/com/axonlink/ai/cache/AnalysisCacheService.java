package com.axonlink.ai.cache;

import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.AnalysisResponse;
import com.axonlink.config.AiAnalysisConfig;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析结果缓存。
 */
@Service
public class AnalysisCacheService {

    private final AiAnalysisConfig config;
    private final Map<String, AnalysisResponse> cache = new ConcurrentHashMap<>();

    public AnalysisCacheService(AiAnalysisConfig config) {
        this.config = config;
    }

    public Optional<AnalysisResponse> get(String txId, AnalysisRequest request) {
        if (!config.getCache().isEnabled() || request.isForceRefresh()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(buildKey(txId, request)));
    }

    public void put(String txId, AnalysisRequest request, AnalysisResponse response) {
        if (!config.getCache().isEnabled()) {
            return;
        }
        cache.put(buildKey(txId, request), response);
    }

    private String buildKey(String txId, AnalysisRequest request) {
        return txId + "|" + request.resolveMode().name() + "|" + request.getFocus() + "|" + request.getSelectedPath();
    }
}
