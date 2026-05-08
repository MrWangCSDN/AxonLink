package com.axonlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 代码解读配置。
 *
 * <p>当前先提供统一的配置入口，便于后续接入内网 GLM5、
 * 上下文截断策略、缓存策略和 Prompt 版本管理。
 */
@Configuration
@ConfigurationProperties(prefix = "ai.analysis")
public class AiAnalysisConfig {

    private boolean enabled = false;
    private String provider = "glm5";
    private String businessPromptVersion = "v1";
    private String technicalPromptVersion = "v1";
    private int maxCodeSnippets = 8;
    private int maxCodeCharsPerSnippet = 3000;
    private int requestTimeoutSeconds = 120;

    private final Glm5 glm5 = new Glm5();
    private final MiniMax minimax = new MiniMax();
    private final Cache cache = new Cache();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBusinessPromptVersion() {
        return businessPromptVersion;
    }

    public void setBusinessPromptVersion(String businessPromptVersion) {
        this.businessPromptVersion = businessPromptVersion;
    }

    public String getTechnicalPromptVersion() {
        return technicalPromptVersion;
    }

    public void setTechnicalPromptVersion(String technicalPromptVersion) {
        this.technicalPromptVersion = technicalPromptVersion;
    }

    public int getMaxCodeSnippets() {
        return maxCodeSnippets;
    }

    public void setMaxCodeSnippets(int maxCodeSnippets) {
        this.maxCodeSnippets = maxCodeSnippets;
    }

    public int getMaxCodeCharsPerSnippet() {
        return maxCodeCharsPerSnippet;
    }

    public void setMaxCodeCharsPerSnippet(int maxCodeCharsPerSnippet) {
        this.maxCodeCharsPerSnippet = maxCodeCharsPerSnippet;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public Glm5 getGlm5() {
        return glm5;
    }

    public MiniMax getMinimax() {
        return minimax;
    }

    public Cache getCache() {
        return cache;
    }

    public static class Glm5 implements com.axonlink.ai.provider.OpenAiCompatibleConfig {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "glm-5";
        private String chatPath = "/v1/chat/completions";
        /**
         * 底层 HTTP 请求是否使用 SSE 流式协议。
         * <ul>
         *   <li>{@code false}（默认）：HTTP 非流式，一次性拿整段响应。
         *       analyze() 直接返回，stream() 拿到后整段一次性回调 onDelta。</li>
         *   <li>{@code true}：HTTP SSE 流式，逐 token 推送。
         *       analyze() 内部累积后返回，stream() 每个 delta 立即回调 onDelta（前端可见逐字出现效果）。</li>
         * </ul>
         */
        private boolean stream = false;
        private double temperature = 0.2d;
        private int maxTokens = 4096;
        /** 默认 false：关闭思考链，节省 token、提速、避免 JSON 输出被截断。 */
        private boolean enableThinking = false;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        @Override
        public boolean isEnableThinking() { return enableThinking; }
        public void setEnableThinking(boolean enableThinking) { this.enableThinking = enableThinking; }
    }

    /**
     * MiniMax 模型配置：与 GLM 同样走 OpenAI 兼容协议。
     * 模型 key 在前后端约定为 {@code minimax-2.5}。
     */
    public static class MiniMax implements com.axonlink.ai.provider.OpenAiCompatibleConfig {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "minimax-m2.5";
        private String chatPath = "/v1/chat/completions";
        private boolean stream = false;
        private double temperature = 0.2d;
        private int maxTokens = 4096;
        /** 默认 false：关闭思考链。 */
        private boolean enableThinking = false;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getChatPath() { return chatPath; }
        public void setChatPath(String chatPath) { this.chatPath = chatPath; }
        public boolean isStream() { return stream; }
        public void setStream(boolean stream) { this.stream = stream; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        @Override
        public boolean isEnableThinking() { return enableThinking; }
        public void setEnableThinking(boolean enableThinking) { this.enableThinking = enableThinking; }
    }

    public static class Cache {
        private boolean enabled = true;
        private int ttlMinutes = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(int ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }
}
