package com.axonlink.ai.provider;

/**
 * OpenAI 兼容协议的 LLM 配置抽象。
 *
 * <p>{@link com.axonlink.config.AiAnalysisConfig.Glm5} / {@link com.axonlink.config.AiAnalysisConfig.MiniMax}
 * 都实现该接口，让 {@link Glm5Client} / {@link MiniMaxClient} 共用同一份 HTTP 调用逻辑。
 *
 * <p>新增模型：
 *  <ol>
 *    <li>在 yml 加配置块（base-url / api-key / model / chat-path / stream / temperature / max-tokens）；</li>
 *    <li>在 {@link com.axonlink.config.AiAnalysisConfig} 加内部类，实现本接口；</li>
 *    <li>新建 *Client，仿照现有实现，将 cfg 拆出来传入。</li>
 *  </ol>
 */
public interface OpenAiCompatibleConfig {
    String getBaseUrl();
    String getApiKey();
    String getModel();
    String getChatPath();
    boolean isStream();
    double getTemperature();
    int getMaxTokens();

    /**
     * 是否允许模型输出 reasoning_content（思考链）。
     * <ul>
     *   <li>{@code true}（默认，保持向后兼容）：不在请求体添加任何字段，模型按自己默认行为。</li>
     *   <li>{@code false}：请求体追加 {@code "enable_thinking": false}，让 GLM-Z1 / DeepSeek-R1
     *       等思考型模型直接出最终答案，不浪费 token 在 {@code <think>} 块上，
     *       彻底避免 max_tokens 被思考过程吃光导致 JSON 输出截断。</li>
     * </ul>
     * 部分上游服务可能不识别该参数（OpenAI 官方就没这字段），多余字段一般会被忽略，安全。
     */
    default boolean isEnableThinking() { return true; }
}
