package com.axonlink.ai.replay.dbcompare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "replay-database-comparison")
public class ReplayDatabaseComparisonProperties {

    private String baseTargetEnv = "base";
    private String baseSchema = "";
    private boolean importEnabled = true;

    public String getBaseTargetEnv() {
        return baseTargetEnv;
    }

    public void setBaseTargetEnv(String baseTargetEnv) {
        this.baseTargetEnv = baseTargetEnv;
    }

    public String getBaseSchema() {
        return baseSchema;
    }

    public void setBaseSchema(String baseSchema) {
        this.baseSchema = baseSchema;
    }

    public boolean isImportEnabled() {
        return importEnabled;
    }

    public void setImportEnabled(boolean importEnabled) {
        this.importEnabled = importEnabled;
    }

}
