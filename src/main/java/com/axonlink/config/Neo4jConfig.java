package com.axonlink.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 图数据库配置。
 *
 * <p>通过 {@code neo4j.enabled} 控制开关，内网无 Neo4j 时设为 false 不影响启动。
 */
@Configuration
@ConfigurationProperties(prefix = "neo4j")
public class Neo4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConfig.class);

    private boolean enabled = false;
    private String uri = "bolt://localhost:7687";
    private String username = "neo4j";
    private String password = "axonlink123";

    @Bean
    public Driver neo4jDriver() {
        if (!enabled) {
            log.info("[Neo4j] neo4j.enabled=false，跳过 Driver 创建");
            return null;
        }
        try {
            Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
            driver.verifyConnectivity();
            log.info("[Neo4j] 连接成功: {}", uri);
            return driver;
        } catch (Exception e) {
            log.warn("[Neo4j] 连接失败（{}），图功能不可用: {}", uri, e.getMessage());
            return null;
        }
    }

    public boolean isEnabled()           { return enabled; }
    public void setEnabled(boolean v)    { this.enabled = v; }
    public String getUri()               { return uri; }
    public void setUri(String v)         { this.uri = v; }
    public String getUsername()           { return username; }
    public void setUsername(String v)     { this.username = v; }
    public String getPassword()          { return password; }
    public void setPassword(String v)    { this.password = v; }
}
