package com.axonlink.ai.replay.dbcompare.service;

public class ReplayDatabaseComparisonPartitionForbiddenException extends RuntimeException {
    public ReplayDatabaseComparisonPartitionForbiddenException() {
        super("无权配置读取分区数");
    }
}
