package com.axonlink.ai.provider;

import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.LlmResult;

import java.util.function.Consumer;

/**
 * 模型调用抽象层。
 */
public interface LlmClient {

    LlmResult analyze(AnalysisPrompt prompt, AnalysisMode mode, AnalysisRequest request);

    default void stream(AnalysisPrompt prompt, Consumer<String> onDelta) {
        throw new UnsupportedOperationException("当前模型客户端暂未实现流式输出");
    }
}
