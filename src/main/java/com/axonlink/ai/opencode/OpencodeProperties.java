package com.axonlink.ai.opencode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * opencode serve 接入配置（深度分析路径）。
 *
 * <p>opencode 与本服务同机部署、仅监听 loopback（复用「详细设计文档生成」设计中的现有部署），
 * 详见 Obsidian《opencode深度分析接入-设计》。
 */
@Configuration
@ConfigurationProperties(prefix = "ai.opencode")
public class OpencodeProperties {

    /** 总开关：false 时深度分析入口直接走降级路径（现有单轮）。 */
    private boolean enabled = false;

    /** opencode serve 地址，默认同机 loopback。 */
    private String baseUrl = "http://127.0.0.1:4096";

    /** HTTP Basic 用户名（opencode 默认 "opencode"）。 */
    private String username = "opencode";

    /** HTTP Basic 密码（对应 OPENCODE_SERVER_PASSWORD；为空表示服务端未启鉴权）。 */
    private String password = "";

    /** 深度分析用的自定义 agent 名（需已在 opencode.json 注册，只读工具）。 */
    private String agent = "axon-deep";

    /** 模型 provider id（跟随现有 opencode 部署的 provider 配置）。 */
    private String providerId = "spdb-new-api";

    /** 模型 id。 */
    private String modelId = "glm-47";

    /** 单次深度分析整体超时（秒）——多轮探索耗时远超单轮，默认 5 分钟。 */
    private int timeoutSeconds = 300;

    /** 建连超时（秒）。 */
    private int connectTimeoutSeconds = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
}
