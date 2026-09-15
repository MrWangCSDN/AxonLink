package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayBaseTableOption(
        String schemaName,
        String tableName,
        String tableComment,
        String registrationStatus,
        Long registrationId,
        Long registrationVersion) {

    public ReplayBaseTableOption(String tableName, String tableComment) {
        this(null, tableName, tableComment, "UNREGISTERED", null, null);
    }
}
