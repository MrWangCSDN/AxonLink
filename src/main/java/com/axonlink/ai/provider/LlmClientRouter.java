package com.axonlink.ai.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型路由：把前端传来的 {@code modelKey}（{@code glm-4.7} / {@code minimax-2.7} 等）
 * 解析成具体的 {@link LlmClient} 实现。
 *
 * <p>路由规则（大小写不敏感、横线/下划线/小数点都接受）：
 * <ul>
 *   <li>包含 {@code minimax} → {@link MiniMaxClient}</li>
 *   <li>包含 {@code glm}     → {@link Glm5Client}</li>
 *   <li>其它 / null          → {@link Glm5Client}（@Primary，兼容老调用方）</li>
 * </ul>
 */
@Service
public class LlmClientRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRouter.class);

    private final Glm5Client glm5;
    private final MiniMaxClient minimax;

    public LlmClientRouter(Glm5Client glm5, MiniMaxClient minimax) {
        this.glm5 = glm5;
        this.minimax = minimax;
    }

    public LlmClient route(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            log.debug("[llm-router] modelKey 空，使用默认 GLM");
            return glm5;
        }
        String key = modelKey.toLowerCase();
        if (key.contains("minimax")) {
            log.debug("[llm-router] modelKey={} → MiniMaxClient", modelKey);
            return minimax;
        }
        if (key.contains("glm")) {
            log.debug("[llm-router] modelKey={} → Glm5Client", modelKey);
            return glm5;
        }
        log.warn("[llm-router] 未识别的 modelKey={}，回退到默认 GLM", modelKey);
        return glm5;
    }
}
