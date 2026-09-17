package com.axonlink.ai.replay.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayReportMailPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsDailyAndWeeklyMailDefaultsFromIndependentPrefixes() {
        contextRunner.withPropertyValues(
                        "axon-link.replay.daily-report-mail.to[0]=daily-to@example.com",
                        "axon-link.replay.daily-report-mail.cc[0]=daily-cc@example.com",
                        "axon-link.replay.daily-report-mail.subject-prefix=日报-",
                        "axon-link.replay.daily-report-mail.body=日报正文",
                        "axon-link.replay.weekly-report-mail.to[0]=weekly-to@example.com",
                        "axon-link.replay.weekly-report-mail.cc[0]=weekly-cc@example.com",
                        "axon-link.replay.weekly-report-mail.subject-prefix=周报-",
                        "axon-link.replay.weekly-report-mail.body=周报正文")
                .run(context -> {
                    ReplayDailyReportMailProperties daily = context.getBean(ReplayDailyReportMailProperties.class);
                    ReplayWeeklyReportMailProperties weekly = context.getBean(ReplayWeeklyReportMailProperties.class);

                    assertThat(daily.getTo()).containsExactly("daily-to@example.com");
                    assertThat(daily.getCc()).containsExactly("daily-cc@example.com");
                    assertThat(daily.getSubjectPrefix()).isEqualTo("日报-");
                    assertThat(daily.getBody()).isEqualTo("日报正文");
                    assertThat(weekly.getTo()).containsExactly("weekly-to@example.com");
                    assertThat(weekly.getCc()).containsExactly("weekly-cc@example.com");
                    assertThat(weekly.getSubjectPrefix()).isEqualTo("周报-");
                    assertThat(weekly.getBody()).isEqualTo("周报正文");
                });
    }

    @EnableConfigurationProperties({
            ReplayDailyReportMailProperties.class,
            ReplayWeeklyReportMailProperties.class
    })
    static class TestConfig {
    }
}
