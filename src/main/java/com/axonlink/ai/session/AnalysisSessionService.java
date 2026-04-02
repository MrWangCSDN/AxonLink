package com.axonlink.ai.session;

import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析会话与短期记忆。
 */
@Service
public class AnalysisSessionService {

    private final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();

    public String ensureSessionId(String requestedSessionId) {
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            return requestedSessionId;
        }
        return UUID.randomUUID().toString();
    }

    public void remember(String sessionId, AnalysisContext context, AnalysisRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("txId", context.getTxId());
        snapshot.put("mode", context.getMode().name());
        snapshot.put("focus", request.getFocus());
        snapshot.put("selectedPath", request.getSelectedPath());
        snapshot.put("snippetCount", context.getCodeSnippets().size());
        snapshot.put("findingCount", context.getRuleFindings().size());
        sessions.put(sessionId, snapshot);
    }

    public Map<String, Object> getSnapshot(String sessionId) {
        return sessions.getOrDefault(sessionId, Map.of());
    }
}
