package com.axonlink.ai.replay.dbcompare.config;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "replay-database-comparison")
public class ReplayDatabaseComparisonProperties {

    private List<String> partitionAdminEmpNos = List.of();

    public List<String> getPartitionAdminEmpNos() {
        return partitionAdminEmpNos;
    }

    public void setPartitionAdminEmpNos(List<String> values) {
        partitionAdminEmpNos = values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    public boolean canConfigurePartitions(String empNo) {
        return empNo != null && !empNo.isBlank() && partitionAdminEmpNos.contains(empNo.trim());
    }

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
