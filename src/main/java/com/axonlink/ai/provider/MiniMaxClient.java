package com.axonlink.ai.provider;

import com.axonlink.config.AiAnalysisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * MiniMax 模型客户端（OpenAI 兼容协议）。
 *
 * <p>所有 HTTP / SSE / 解析逻辑见 {@link OpenAiCompatibleClient}。
 * 由 {@link LlmClientRouter} 按 {@code model=minimax-2.5} 路由到本类。
 */
@Service
public class MiniMaxClient extends OpenAiCompatibleClient {

    public MiniMaxClient(AiAnalysisConfig globalConfig, ObjectMapper objectMapper) {
        super(globalConfig, objectMapper);
    }

    @Override
    protected OpenAiCompatibleConfig cfg() {
        return globalConfig.getMinimax();
    }
}
