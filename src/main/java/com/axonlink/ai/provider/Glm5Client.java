package com.axonlink.ai.provider;

import com.axonlink.config.AiAnalysisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * GLM 系列模型客户端（OpenAI 兼容协议）。
 *
 * <p>所有 HTTP / SSE / 解析逻辑见 {@link OpenAiCompatibleClient}。
 * 本类标 {@code @Primary} —— 在没有显式 router 选择时（兼容老路径），默认使用 GLM。
 */
@Service
@Primary
public class Glm5Client extends OpenAiCompatibleClient {

    public Glm5Client(AiAnalysisConfig globalConfig, ObjectMapper objectMapper) {
        super(globalConfig, objectMapper);
    }

    @Override
    protected OpenAiCompatibleConfig cfg() {
        return globalConfig.getGlm5();
    }
}
