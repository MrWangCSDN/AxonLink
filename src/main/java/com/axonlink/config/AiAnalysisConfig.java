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

    public Cache getCache() {
        return cache;
    }

    public static class Glm5 {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "glm-5";
        private String chatPath = "/v1/chat/completions";
        private boolean stream = false;
        private double temperature = 0.2d;
        private int maxTokens = 4096;

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
