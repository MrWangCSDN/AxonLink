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
        });
    }
}
