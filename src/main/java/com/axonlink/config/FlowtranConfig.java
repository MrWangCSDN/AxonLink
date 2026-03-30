package com.axonlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * flowtran 功能配置属性。
 *
 * <ul>
 *   <li>{@code flowtran.datasource}：当前运行环境标识，{@code local} 或 {@code intranet}，默认 {@code local}。</li>
 *   <li>{@code flowtran.cache.enabled}：是否在启动时预热 Neo4j/XML 元数据缓存，默认 {@code true}。</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "flowtran")
public class FlowtranConfig {

    /** 激活的数据源环境：local | intranet */
    private String datasource = "local";

    /** 元数据缓存配置 */
    private Cache cache = new Cache();

    public String getDatasource() { return datasource; }
    public void setDatasource(String datasource) { this.datasource = datasource; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }

    public static class Cache {
        /** 是否启动时预热 Neo4j/XML 元数据缓存 */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
