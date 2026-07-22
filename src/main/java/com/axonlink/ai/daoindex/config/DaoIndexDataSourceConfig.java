package com.axonlink.ai.daoindex.config;

import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class DaoIndexDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DaoIndexDataSourceConfig.class);

    @Bean(name = "diiResultDataSource", destroyMethod = "close")
    public HikariDataSource diiResultDataSource(DaoIndexAnalysisProperties props) {
        DaoIndexAnalysisProperties.ResultDatasource cfg = props.getResultDatasource();
        if (cfg == null || isBlank(cfg.getUrl())) {
            throw new IllegalStateException(
                    "必须配置 dao-index-analysis.result-datasource.url");
        }
        HikariConfig hc = new HikariConfig();
        if (!isBlank(cfg.getDriverClassName())) {
            hc.setDriverClassName(cfg.getDriverClassName());
        }
        // 结果库 MySQL：强制补批量写优化参数（缺则补），让 batchUpdate 重写成多值 INSERT。
        // 放在代码里而非仅靠 yml 默认值——prod 用 env 变量覆盖 URL 时也能生效。
        String effectiveUrl = withMysqlBatchParams(cfg.getUrl());
        hc.setJdbcUrl(effectiveUrl);
        hc.setUsername(cfg.getUsername());
        hc.setPassword(cfg.getPassword());
        hc.setMaximumPoolSize(cfg.getMaximumPoolSize());
        hc.setMinimumIdle(cfg.getMinimumIdle());
        hc.setConnectionTimeout(cfg.getConnectionTimeoutMs());
        if (!isBlank(cfg.getConnectionTestQuery())) {
            hc.setConnectionTestQuery(cfg.getConnectionTestQuery());
        }
        hc.setPoolName("dii-result-pool");
        applyStartupConnectPolicy(hc, props.isDatasourceFailFast());
        log.info("[dii] 结果库 HikariDataSource 初始化：url={} pool={} failFast={}",
                effectiveUrl, hc.getMaximumPoolSize(), props.isDatasourceFailFast());
        return new HikariDataSource(hc);
    }

    /**
     * 启动建连策略（{@code dao-index-analysis.datasource-fail-fast}）：
     * <ul>
     *   <li>true（默认）：保持 HikariCP 默认 fail-fast（initializationFailTimeout=1）——
     *       池构造时立即建连校验，连不上直接抛异常阻断启动，内网/生产快速暴露配置错误；</li>
     *   <li>false：惰性初始化——initializationFailTimeout=-1 跳过启动建连校验，
     *       minimumIdle=0 停掉后台补空闲连接（否则连不上时每个 housekeeping 周期都刷失败日志），
     *       连接完全按需创建。外网本地连不上内网库时应用也能正常启动。</li>
     * </ul>
     */
    private static void applyStartupConnectPolicy(HikariConfig hc, boolean failFast) {
        if (failFast) {
            return;
        }
        hc.setInitializationFailTimeout(-1);
        hc.setMinimumIdle(0);
    }

    /**
     * 给 MySQL 结果库 URL 补批量写优化参数（幂等，仅 jdbc:mysql 生效）：
     * <ul>
     *   <li>{@code rewriteBatchedStatements=true}：batchUpdate 重写成 {@code INSERT ... VALUES (...),(...)} 多值语句，
     *       大批量插入 10-100x 提速（慢SQL 单次导入数十万行的关键）</li>
     *   <li>{@code cachePrepStmts=true}：缓存预编译语句</li>
     * </ul>
     */
    static String withMysqlBatchParams(String url) {
        if (url == null || !url.startsWith("jdbc:mysql")) return url;
        String u = appendParamIfAbsent(url, "rewriteBatchedStatements", "true");
        u = appendParamIfAbsent(u, "cachePrepStmts", "true");
        return u;
    }

    private static String appendParamIfAbsent(String url, String key, String val) {
        if (url.contains(key + "=")) return url;
        return url + (url.contains("?") ? "&" : "?") + key + "=" + val;
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
                    "必须至少配置一个 dao-index-analysis.targets.<env>");
        }
        boolean failFast = props.isDatasourceFailFast();
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
            applyStartupConnectPolicy(hc, failFast);
            try {
                dsMap.put(env, new HikariDataSource(hc));
                log.info("[dii] 目标库 HikariDataSource 初始化：env={} url={} pool={} failFast={}",
                        env, t.getUrl(), hc.getMaximumPoolSize(), failFast);
            } catch (RuntimeException ex) {
                // HikariDataSource 构造时就会解析 JDBC 驱动（DriverDataSource）：驱动缺失、
                // URL scheme 不被识别（如默认 jdbc:opengauss:// 配社区版 opengauss-jdbc 5.0.0，
                // 其 SPI 注册的是 org.postgresql.Driver，只认 jdbc:postgresql:）都在这里抛，
                // 惰性初始化拦不住。fail-fast=false 时按"该目标库不可用"降级跳过，保住应用启动。
                if (failFast) {
                    throw ex;
                }
                log.warn("[dii] 目标库 env={} 数据源装配失败，惰性模式跳过（该环境巡检不可用）：{}",
                        env, ex.getMessage());
            }
        }
        if (dsMap.isEmpty()) {
            if (failFast) {
                throw new IllegalStateException("dao-index-analysis.targets 全部配置无效");
            }
            log.warn("[dii] 惰性模式：无任何可用目标库数据源，DII 巡检执行能力整体不可用");
            return new TargetDataSourceRegistry(dsMap, props.getDefaultEnv());
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
