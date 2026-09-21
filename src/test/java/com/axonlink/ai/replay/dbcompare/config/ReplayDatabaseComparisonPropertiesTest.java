package com.axonlink.ai.replay.dbcompare.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayDatabaseComparisonPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ReplayDatabaseComparisonProperties.class);

    @Test
    void bindsCommaSeparatedOrEmptyPartitionAllowlistSafely() {
        contextRunner.withPropertyValues("replay-database-comparison.partition-admin-emp-nos=200, 201")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ReplayDatabaseComparisonProperties.class);
                    assertThat(properties.canConfigurePartitions("200")).isTrue();
                    assertThat(properties.canConfigurePartitions("201")).isTrue();
                    assertThat(properties.canConfigurePartitions("202")).isFalse();
                });
        contextRunner.withPropertyValues("replay-database-comparison.partition-admin-emp-nos=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ReplayDatabaseComparisonProperties.class);
                    assertThat(properties.getPartitionAdminEmpNos()).isEmpty();
                    assertThat(properties.canConfigurePartitions("200")).isFalse();
                });
    }

    @Test
    void bindsBaseTargetSelectionAndAccessOptions() {
        contextRunner.withPropertyValues(
                        "replay-database-comparison.base-target-env=base-metadata",
                        "replay-database-comparison.base-schema=base_schema",
                        "replay-database-comparison.import-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReplayDatabaseComparisonProperties properties =
                            context.getBean(ReplayDatabaseComparisonProperties.class);
                    assertThat(properties.getBaseTargetEnv()).isEqualTo("base-metadata");
                    assertThat(properties.getBaseSchema()).isEqualTo("base_schema");
                    assertThat(properties.isImportEnabled()).isFalse();
                });
    }

    @Test
    void suppliesSafeDefaultsWhenOptionalConfigurationIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ReplayDatabaseComparisonProperties properties =
                    context.getBean(ReplayDatabaseComparisonProperties.class);
            assertThat(properties.getBaseTargetEnv()).isEqualTo("base");
            assertThat(properties.getBaseSchema()).isBlank();
            assertThat(properties.isImportEnabled()).isTrue();
            assertThat(properties.canConfigurePartitions("200")).isFalse();
        });
    }
}
