package com.axonlink.ai.daoindex.config;

import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DAO 索引巡检模块 DataSource 注册。
 *
 * <p>注册两类数据源：
 * <ul>
 *     <li>结果库 DataSource（写入巡检结果）— 对应 {@code dao-index-analysis.result-datasource}；</li>
 *     <li>目标库 DataSource Registry（被巡检的 GaussDB，多环境）— 对应 {@code dao-index-analysis.targets.*}。</li>
 * </ul>
 *
 * <p>全部手动构建 HikariDataSource，不参与 {@code spring.datasource} 自动装配，避免与项目其它业务配置冲突。
 */
@Configuration
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class DaoIndexDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DaoIndexDataSourceConfig.class);

    @Bean(name = "diiResultDataSource", destroyMethod = "close")
    public HikariDataSource diiResultDataSource(DaoIndexAnalysisProperties props) {
        DaoIndexAnalysisProperties.ResultDatasource cfg = props.getResultDatasource();
        if (cfg == null || isBlank(cfg.getUrl())) {
            throw new IllegalStateException(
                    "dao-index-analysis.enabled=true 时必须配置 result-datasource.url");
        }
        HikariConfig hc = new HikariConfig();
        if (!isBlank(cfg.getDriverClassName())) {
            hc.setDriverClassName(cfg.getDriverClassName());
        }
        hc.setJdbcUrl(cfg.getUrl());
        hc.setUsername(cfg.getUsername());
        hc.setPassword(cfg.getPassword());
        hc.setMaximumPoolSize(cfg.getMaximumPoolSize());
        hc.setMinimumIdle(cfg.getMinimumIdle());
        hc.setConnectionTimeout(cfg.getConnectionTimeoutMs());
        if (!isBlank(cfg.getConnectionTestQuery())) {
            hc.setConnectionTestQuery(cfg.getConnectionTestQuery());
        }
        hc.setPoolName("dii-result-pool");
        log.info("[dii] 结果库 HikariDataSource 初始化：url={} pool={}", cfg.getUrl(), hc.getMaximumPoolSize());
        return new HikariDataSource(hc);
    }

    @Bean(name = "diiResultJdbcTemplate")
    public JdbcTemplate diiResultJdbcTemplate(DataSource diiResultDataSource) {
        return new JdbcTemplate(diiResultDataSource);
    }

    @Bean(destroyMethod = "close")
    public TargetDataSourceRegistry targetDataSourceRegistry(DaoIndexAnalysisProperties props) {
        Map<String, DaoIndexAnalysisProperties.Target> targets = props.getTargets();
        if (targets == null || targets.isEmpty()) {
            throw new IllegalStateException(
                    "dao-index-analysis.enabled=true 时必须至少配置一个 targets.<env>");
        }
        Map<String, HikariDataSource> dsMap = new LinkedHashMap<>();
        for (Map.Entry<String, DaoIndexAnalysisProperties.Target> entry : targets.entrySet()) {
            String env = entry.getKey();
            DaoIndexAnalysisProperties.Target t = entry.getValue();
            if (t == null || isBlank(t.getUrl())) {
                log.warn("[dii] 目标库 env={} 缺少 url，已跳过", env);
                continue;
            }
            HikariConfig hc = new HikariConfig();
            // 留空则走 JDBC 4.0 ServiceLoader 自动发现（推荐），
            // 与 sunline-benchmark 的 DriverManager.getConnection(url,...) 行为一致，
            // 兼容 CSI / GaussDB 定制版等类名非 org.opengauss.Driver 的驱动。
            if (!isBlank(t.getDriverClassName())) {
                hc.setDriverClassName(t.getDriverClassName());
            }
            hc.setJdbcUrl(t.getUrl());
            hc.setUsername(t.getUsername());
            hc.setPassword(t.getPassword());
            hc.setMaximumPoolSize(t.getMaximumPoolSize());
            hc.setMinimumIdle(t.getMinimumIdle());
            hc.setConnectionTimeout(t.getConnectionTimeoutMs());
            hc.setReadOnly(true);
            hc.setPoolName("dii-target-" + env);
            dsMap.put(env, new HikariDataSource(hc));
            log.info("[dii] 目标库 HikariDataSource 初始化：env={} url={} pool={}",
                    env, t.getUrl(), hc.getMaximumPoolSize());
        }
        if (dsMap.isEmpty()) {
            throw new IllegalStateException("dao-index-analysis.targets 全部配置无效");
        }
        String defaultEnv = props.getDefaultEnv();
        if (isBlank(defaultEnv) || !dsMap.containsKey(defaultEnv)) {
            defaultEnv = dsMap.keySet().iterator().next();
            log.warn("[dii] default-env 不在已配置 targets 中，回退为 {}", defaultEnv);
        }
        return new TargetDataSourceRegistry(dsMap, defaultEnv);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
