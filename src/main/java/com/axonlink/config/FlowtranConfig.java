package com.axonlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * flowtran 功能配置属性。
 *
 * <ul>
 *   <li>{@code flowtran.datasource}：当前运行环境标识，{@code local} 或 {@code intranet}，默认 {@code local}。</li>
 *   <li>{@code flowtran.startup-auto-build}：服务启动完成后是否自动触发一次 Neo4j 异步构建，默认 {@code true}。</li>
 *   <li>{@code flowtran.cache.enabled}：是否在启动时预热 Neo4j/XML 元数据缓存，默认 {@code true}。</li>
 *   <li>{@code flowtran.cache.impact-projection-enabled}：是否启用影响分析投影缓存，默认 {@code true}。</li>
 *   <li>{@code flowtran.cache.impact-projection-dir}：影响分析投影快照目录，默认 {@code data/cache}。</li>
 *   <li>{@code flowtran.cache.impact-projection-load-on-startup}：是否在启动时读取影响分析投影快照，默认 {@code true}。</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "flowtran")
public class FlowtranConfig {

    /** 激活的数据源环境：local | intranet */
    private String datasource = "local";
    /** 服务启动完成后是否自动触发一次异步构建 */
    private boolean startupAutoBuild = true;

    /** 元数据缓存配置 */
    private Cache cache = new Cache();

    public String getDatasource() { return datasource; }
    public void setDatasource(String datasource) { this.datasource = datasource; }
    public boolean isStartupAutoBuild() { return startupAutoBuild; }
    public void setStartupAutoBuild(boolean startupAutoBuild) { this.startupAutoBuild = startupAutoBuild; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }

    public static class Cache {
        /** 是否启动时预热 Neo4j/XML 元数据缓存 */
        private boolean enabled = true;
        /** 是否启用影响分析投影缓存 */
        private boolean impactProjectionEnabled = true;
        /** 影响分析投影快照目录 */
        private String impactProjectionDir = "data/cache";
        /** 是否在启动时加载影响分析投影快照 */
        private boolean impactProjectionLoadOnStartup = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isImpactProjectionEnabled() { return impactProjectionEnabled; }
        public void setImpactProjectionEnabled(boolean impactProjectionEnabled) { this.impactProjectionEnabled = impactProjectionEnabled; }
        public String getImpactProjectionDir() { return impactProjectionDir; }
        public void setImpactProjectionDir(String impactProjectionDir) { this.impactProjectionDir = impactProjectionDir; }
        public boolean isImpactProjectionLoadOnStartup() { return impactProjectionLoadOnStartup; }
        public void setImpactProjectionLoadOnStartup(boolean impactProjectionLoadOnStartup) {
            this.impactProjectionLoadOnStartup = impactProjectionLoadOnStartup;
        }
    }
}
