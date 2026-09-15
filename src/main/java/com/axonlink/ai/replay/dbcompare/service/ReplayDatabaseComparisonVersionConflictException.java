package com.axonlink.ai.replay.dbcompare.service;

public class ReplayDatabaseComparisonVersionConflictException extends RuntimeException {

    public ReplayDatabaseComparisonVersionConflictException() {
        super("登记数据已被其他用户修改，请刷新后重试");
    }
}
