package com.axonlink.ai.replay.dbcompare.dto;

/** Physical source and human label; the existing tableName remains the immutable comparison code. */
public record ReplayDbCompareConfigurationIdentity(String sourceTableName, String configurationName) {
    public ReplayDbCompareConfigurationIdentity {
        sourceTableName = sourceTableName == null ? "" : sourceTableName.strip();
        configurationName = configurationName == null ? "" : configurationName.strip();
        if (sourceTableName.isEmpty() || sourceTableName.length() > 256 || sourceTableName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("真实来源表名无效");
        }
        if (configurationName.length() > 200 || configurationName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("配置名称不能超过200个字符或包含NUL字符");
        }
    }
}
