package com.axonlink.ai.daoindex.target;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 按 env 名字查找目标库（被巡检库）DataSource。
 *
 * <p>注册表一次性构建，不支持运行时动态增删。
 */
public class TargetDataSourceRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TargetDataSourceRegistry.class);

    private final Map<String, HikariDataSource> dataSources;
    private final String defaultEnv;

    public TargetDataSourceRegistry(Map<String, HikariDataSource> dataSources, String defaultEnv) {
        this.dataSources = new LinkedHashMap<>(dataSources);
        this.defaultEnv = defaultEnv;
    }

    /**
     * 按 env 取 DataSource；env 为 null 或空时回退 defaultEnv。
     *
     * @throws IllegalArgumentException 如果 env 不存在
     */
    public DataSource getByEnv(String env) {
        String chosen = (env == null || env.isBlank()) ? defaultEnv : env.trim();
        HikariDataSource ds = dataSources.get(chosen);
        if (ds == null) {
            throw new IllegalArgumentException("未配置目标库 env：" + chosen
                    + "；已配置环境：" + dataSources.keySet());
        }
        return ds;
    }

    /** 返回所有已注册的 env 名。 */
    public Set<String> allEnvs() {
        return Collections.unmodifiableSet(dataSources.keySet());
    }

    /** 默认 env 名。 */
    public String getDefaultEnv() {
        return defaultEnv;
    }

    @Override
    public void close() {
        for (Map.Entry<String, HikariDataSource> entry : dataSources.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("[dii] 关闭目标库连接池失败 env={} msg={}", entry.getKey(), e.getMessage());
            }
        }
    }
}
