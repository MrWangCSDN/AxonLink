package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseDatabaseUnavailableException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.regex.Pattern;

@Component
public class ReplayBaseDataSourceRegistry {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final TargetDataSourceRegistry targetDataSourceRegistry;
    private final ReplayDatabaseComparisonProperties properties;

    public ReplayBaseDataSourceRegistry(
            TargetDataSourceRegistry targetDataSourceRegistry,
            ReplayDatabaseComparisonProperties properties) {
        this.targetDataSourceRegistry = targetDataSourceRegistry;
        this.properties = properties;
    }

    public DataSource requireDataSource() {
        String targetEnv = properties.getBaseTargetEnv();
        String schema = properties.getBaseSchema();
        if (isBlank(targetEnv) || isBlank(schema)
                || !SAFE_IDENTIFIER.matcher(schema.trim()).matches()) {
            throw new ReplayBaseDatabaseUnavailableException("BASE 母库配置无效");
        }
        try {
            return targetDataSourceRegistry.getByEnv(targetEnv.trim());
        } catch (IllegalArgumentException exception) {
            throw new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
