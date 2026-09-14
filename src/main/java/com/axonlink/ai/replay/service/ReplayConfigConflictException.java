package com.axonlink.ai.replay.service;

/** 回放配置乐观锁、唯一键或索引并发冲突。 */
public class ReplayConfigConflictException extends RuntimeException {
    public ReplayConfigConflictException(String message) {
        super(message);
    }
}
