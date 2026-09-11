package com.axonlink.ai.replay.dbcompare.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayDatabaseComparisonPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ReplayDatabaseComparisonProperties.class);

    @Test
    void bindsBaseConnectionAndAccessOptions() {
        contextRunner.withPropertyValues(
                        "replay-database-comparison.base-datasource.url=jdbc:h2:mem:base_meta",
                        "replay-database-comparison.base-datasource.username=reader",
                        "replay-database-comparison.base-datasource.password=secret",
                        "replay-database-comparison.base-datasource.driver-class-name=org.h2.Driver",
                        "replay-database-comparison.base-datasource.schema=base_schema",
                        "replay-database-comparison.base-datasource.maximum-pool-size=4",
                        "replay-database-comparison.base-datasource.connection-timeout-ms=3210",
                        "replay-database-comparison.admin-employee-nos[0]=000001",
                        "replay-database-comparison.admin-employee-nos[1]=000002",
                        "replay-database-comparison.group-options[0]=存款组",
                        "replay-database-comparison.group-options[1]=公共组",
                        "replay-database-comparison.import-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReplayDatabaseComparisonProperties properties =
                            context.getBean(ReplayDatabaseComparisonProperties.class);
                    assertThat(properties.getBaseDatasource().getUrl()).isEqualTo("jdbc:h2:mem:base_meta");
                    assertThat(properties.getBaseDatasource().getUsername()).isEqualTo("reader");
                    assertThat(properties.getBaseDatasource().getPassword()).isEqualTo("secret");
                    assertThat(properties.getBaseDatasource().getDriverClassName()).isEqualTo("org.h2.Driver");
                    assertThat(properties.getBaseDatasource().getSchema()).isEqualTo("base_schema");
                    assertThat(properties.getBaseDatasource().getMaximumPoolSize()).isEqualTo(4);
                    assertThat(properties.getBaseDatasource().getConnectionTimeoutMs()).isEqualTo(3210);
                    assertThat(properties.getAdminEmployeeNos()).containsExactly("000001", "000002");
                    assertThat(properties.getGroupOptions()).containsExactly("存款组", "公共组");
                    assertThat(properties.isImportEnabled()).isFalse();
                });
    }

    @Test
    void suppliesSafeDefaultsWhenOptionalConfigurationIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ReplayDatabaseComparisonProperties properties =
                    context.getBean(ReplayDatabaseComparisonProperties.class);
            assertThat(properties.getBaseDatasource().getUrl()).isBlank();
            assertThat(properties.getBaseDatasource().getMaximumPoolSize()).isEqualTo(3);
            assertThat(properties.getBaseDatasource().getConnectionTimeoutMs()).isEqualTo(5000);
            assertThat(properties.getAdminEmployeeNos()).isEqualTo(List.of());
            assertThat(properties.getGroupOptions())
                    .containsExactly("公共组", "贷款组", "结算组", "存款组");
            assertThat(properties.isImportEnabled()).isTrue();
        });
    }
}
