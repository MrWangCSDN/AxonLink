package com.axonlink.ai.replay.dbcompare.service;

public class ReplayBaseTableNotFoundException extends IllegalArgumentException {

    private final String tableName;

    public ReplayBaseTableNotFoundException(String tableName) {
        super("BASE 母库中不存在表：" + tableName);
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }
}
