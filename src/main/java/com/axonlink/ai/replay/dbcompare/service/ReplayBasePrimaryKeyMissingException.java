package com.axonlink.ai.replay.dbcompare.service;

public final class ReplayBasePrimaryKeyMissingException extends RuntimeException {

    private final String tableName;

    public ReplayBasePrimaryKeyMissingException(String tableName) {
        super("该表没有主键或可用唯一索引，请联系 DBA 创建比对键索引");
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }
}
