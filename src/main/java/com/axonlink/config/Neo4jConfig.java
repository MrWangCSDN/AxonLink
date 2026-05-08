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

    /**
     * Neo4j Driver Bean。
     *
     * <p><b>关键设计：永远返回非 null 的 Driver 对象（除非 enabled=false）</b>，
     * 否则下游 {@code FlowtranServiceImpl} / {@code Neo4jGraphBuilder} 等用 required 构造器
     * 注入 Driver 的 bean 会因 "No qualifying bean of type Driver" 让整个 Spring 容器启动失败。
     *
     * <p>启动时如果 Neo4j 不可达，<b>不在这里阻塞</b>：仍然把未验证的 Driver 放进容器，
     * 等业务真用到时再失败（每次 session.run 会自然抛 ServiceUnavailable，可被业务层 try/catch）。
     *
     * <p>{@code enabled=false} 时返回 null：表示业务上明确不要 Neo4j，调用方需要用
     * {@code @Autowired(required=false)} 或 {@code ObjectProvider} 接收。但目前所有下游都是
     * required 注入，所以 enabled=false 必须配合改下游签名才能用——内网部署一律 enabled=true。
     */
    @Bean
    public Driver neo4jDriver() {
        if (!enabled) {
            log.info("[Neo4j] neo4j.enabled=false，跳过 Driver 创建（注意下游 required 注入会失败）");
            return null;
        }
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        try {
            driver.verifyConnectivity();
            log.info("[Neo4j] 连接成功: {}", uri);
        } catch (Exception e) {
            // 启动期连不上不阻塞容器启动；后续业务调用如果还连不上，会自然抛 ServiceUnavailable。
            // 这样：① Neo4j 后启动也行；② Neo4j 临时挂了 axon-link-server 不会跟着挂。
            log.warn("[Neo4j] 启动期连接失败（{}），保留 Driver bean，业务首次调用时再重试: {}",
                    uri, e.getMessage());
        }
        return driver;
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
