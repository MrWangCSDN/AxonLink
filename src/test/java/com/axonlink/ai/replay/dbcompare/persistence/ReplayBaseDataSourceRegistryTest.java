package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseDatabaseUnavailableException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplayBaseDataSourceRegistryTest {

    @Test
    void selectsBaseFromSharedTargetRegistryByDefault() {
        TargetDataSourceRegistry targets = mock(TargetDataSourceRegistry.class);
        DataSource base = mock(DataSource.class);
        when(targets.getByEnv("base")).thenReturn(base);
        ReplayDatabaseComparisonProperties properties = configuredProperties();

        ReplayBaseDataSourceRegistry registry = new ReplayBaseDataSourceRegistry(targets, properties);

        assertThat(registry.requireDataSource()).isSameAs(base);
        verify(targets).getByEnv("base");
    }

    @Test
    void supportsConfiguredBaseTargetName() {
        TargetDataSourceRegistry targets = mock(TargetDataSourceRegistry.class);
        DataSource base = mock(DataSource.class);
        when(targets.getByEnv("base-metadata")).thenReturn(base);
        ReplayDatabaseComparisonProperties properties = configuredProperties();
        properties.setBaseTargetEnv("base-metadata");

        ReplayBaseDataSourceRegistry registry = new ReplayBaseDataSourceRegistry(targets, properties);

        assertThat(registry.requireDataSource()).isSameAs(base);
        verify(targets).getByEnv("base-metadata");
    }

    @Test
    void missingSharedTargetIsReportedWithoutLeakingRegistryDetails() {
        TargetDataSourceRegistry targets = mock(TargetDataSourceRegistry.class);
        when(targets.getByEnv("base")).thenThrow(new IllegalArgumentException(
                "未配置目标库 env：base；已配置环境：[dev, sit]"));

        ReplayBaseDataSourceRegistry registry =
                new ReplayBaseDataSourceRegistry(targets, configuredProperties());

        assertThatThrownBy(registry::requireDataSource)
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessage("BASE 母库未配置或暂不可用")
                .hasMessageNotContaining("dev")
                .hasMessageNotContaining("sit");
    }

    @Test
    void rejectsUnsafeSchemaBeforeAccessingSharedTarget() {
        TargetDataSourceRegistry targets = mock(TargetDataSourceRegistry.class);
        ReplayDatabaseComparisonProperties properties = configuredProperties();
        properties.setBaseSchema("base_schema; drop table users");
        ReplayBaseDataSourceRegistry registry = new ReplayBaseDataSourceRegistry(targets, properties);

        assertThatThrownBy(registry::requireDataSource)
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessage("BASE 母库配置无效")
                .hasMessageNotContaining("drop table");
        verifyNoInteractions(targets);
    }

    private ReplayDatabaseComparisonProperties configuredProperties() {
        ReplayDatabaseComparisonProperties properties = new ReplayDatabaseComparisonProperties();
        properties.setBaseSchema("base_schema");
        return properties;
    }
}
