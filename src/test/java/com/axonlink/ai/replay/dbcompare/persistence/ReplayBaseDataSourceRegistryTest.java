package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseDatabaseUnavailableException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayBaseDataSourceRegistryTest {

    @Test
    void createsLazyReadOnlyPoolFromConfiguredBaseConnection() {
        ReplayDatabaseComparisonProperties properties = configuredProperties();

        try (ReplayBaseDataSourceRegistry registry = new ReplayBaseDataSourceRegistry(properties)) {
            DataSource dataSource = registry.requireDataSource();

            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) dataSource;
            assertThat(hikari.isReadOnly()).isTrue();
            assertThat(hikari.getMaximumPoolSize()).isEqualTo(3);
            assertThat(hikari.getConnectionTimeout()).isEqualTo(5000);
            assertThat(hikari.getPoolName()).isEqualTo("replay-base-metadata-pool");
            assertThat(registry.requireDataSource()).isSameAs(dataSource);
        }
    }

    @Test
    void unconfiguredRegistryFailsOnlyWhenDataSourceIsRequested() {
        ReplayDatabaseComparisonProperties properties = new ReplayDatabaseComparisonProperties();
        ReplayBaseDataSourceRegistry registry = new ReplayBaseDataSourceRegistry(properties);

        assertThatThrownBy(registry::requireDataSource)
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessage("BASE 母库未配置或暂不可用")
                .hasMessageNotContaining("jdbc:");
    }

    @Test
    void rejectsUnsafeConfiguredSchemaWithoutExposingConnectionDetails() {
        ReplayDatabaseComparisonProperties properties = configuredProperties();
        properties.getBaseDatasource().setSchema("base_schema; drop table users");
        ReplayBaseDataSourceRegistry registry = new ReplayBaseDataSourceRegistry(properties);

        assertThatThrownBy(registry::requireDataSource)
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessage("BASE 母库配置无效")
                .hasMessageNotContaining("drop table")
                .hasMessageNotContaining("jdbc:");
    }

    private ReplayDatabaseComparisonProperties configuredProperties() {
        ReplayDatabaseComparisonProperties properties = new ReplayDatabaseComparisonProperties();
        ReplayDatabaseComparisonProperties.BaseDatasource base = properties.getBaseDatasource();
        base.setUrl("jdbc:h2:mem:replay_base_registry;DB_CLOSE_DELAY=-1");
        base.setUsername("sa");
        base.setPassword("");
        base.setDriverClassName("org.h2.Driver");
        base.setSchema("base_schema");
        base.setMaximumPoolSize(3);
        base.setConnectionTimeoutMs(5000);
        return properties;
    }
}
