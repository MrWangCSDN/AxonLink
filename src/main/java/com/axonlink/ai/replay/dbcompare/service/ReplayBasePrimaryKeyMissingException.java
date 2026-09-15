package com.axonlink.ai.replay.dbcompare.service;

public final class ReplayBasePrimaryKeyMissingException extends RuntimeException {

    private final String tableName;

    public ReplayBasePrimaryKeyMissingException(String tableName) {
        super("该表没有主键，请联系 DBA 创建表主键");
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }
}
