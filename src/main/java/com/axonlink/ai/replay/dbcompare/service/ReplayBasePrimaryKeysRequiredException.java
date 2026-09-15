package com.axonlink.ai.replay.dbcompare.service;

import java.util.List;

public final class ReplayBasePrimaryKeysRequiredException extends RuntimeException {

    private final String tableName;
    private final List<String> missingPrimaryKeyNames;

    public ReplayBasePrimaryKeysRequiredException(String tableName, List<String> missingPrimaryKeyNames) {
        super("比对字段必须包含 BASE 母库全部主键");
        this.tableName = tableName;
        this.missingPrimaryKeyNames = List.copyOf(missingPrimaryKeyNames);
    }

    public String tableName() {
        return tableName;
    }

    public List<String> missingPrimaryKeyNames() {
        return missingPrimaryKeyNames;
    }
}
