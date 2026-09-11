package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseDatabaseUnavailableException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.regex.Pattern;

@Component
public class ReplayBaseDataSourceRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplayBaseDataSourceRegistry.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ReplayDatabaseComparisonProperties properties;
    private volatile HikariDataSource dataSource;

    public ReplayBaseDataSourceRegistry(ReplayDatabaseComparisonProperties properties) {
        this.properties = properties;
    }

    public DataSource requireDataSource() {
        HikariDataSource current = dataSource;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (dataSource == null) {
                dataSource = createDataSource();
            }
            return dataSource;
        }
    }

    private HikariDataSource createDataSource() {
        ReplayDatabaseComparisonProperties.BaseDatasource config = properties.getBaseDatasource();
        if (config == null || isBlank(config.getUrl())) {
            throw new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用");
        }
        if (isBlank(config.getSchema()) || !SAFE_IDENTIFIER.matcher(config.getSchema().trim()).matches()
                || config.getMaximumPoolSize() < 1 || config.getConnectionTimeoutMs() < 250) {
            throw new ReplayBaseDatabaseUnavailableException("BASE 母库配置无效");
        }

        try {
            HikariConfig hikari = new HikariConfig();
            if (!isBlank(config.getDriverClassName())) {
                hikari.setDriverClassName(config.getDriverClassName().trim());
            }
            hikari.setJdbcUrl(config.getUrl().trim());
            hikari.setUsername(config.getUsername());
            hikari.setPassword(config.getPassword());
            hikari.setReadOnly(true);
            hikari.setMaximumPoolSize(config.getMaximumPoolSize());
            hikari.setMinimumIdle(0);
            hikari.setConnectionTimeout(config.getConnectionTimeoutMs());
            hikari.setPoolName("replay-base-metadata-pool");
            log.info("[replay-db-compare] BASE 元数据连接池已配置 schema={} pool={}",
                    config.getSchema().trim(), config.getMaximumPoolSize());
            return new HikariDataSource(hikari);
        } catch (RuntimeException exception) {
            log.warn("[replay-db-compare] BASE 元数据连接池初始化失败");
            throw new ReplayBaseDatabaseUnavailableException("BASE 母库配置无效");
        }
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
