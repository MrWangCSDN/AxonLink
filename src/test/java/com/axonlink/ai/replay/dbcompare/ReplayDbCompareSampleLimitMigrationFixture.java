package com.axonlink.ai.replay.dbcompare;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class ReplayDbCompareSampleLimitMigrationFixture {
    private ReplayDbCompareSampleLimitMigrationFixture() {
    }

    public static void apply(JdbcTemplate jdbc) {
        String migration = "db/daoindex/V98__replay_db_compare_full_difference_samples.sql";
        try {
            String sql = new ClassPathResource(migration).getContentAsString(StandardCharsets.UTF_8);
            // H2 uses standard DROP CONSTRAINT where the MySQL deployment uses DROP CHECK.
            var script = new ByteArrayResource(sql.replace("DROP CHECK", "DROP CONSTRAINT")
                    .getBytes(StandardCharsets.UTF_8), migration);
            new ResourceDatabasePopulator(script).execute(jdbc.getDataSource());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
