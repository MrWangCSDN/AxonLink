package com.axonlink.ai.replay.service;

/** 回放配置目标记录不存在。 */
public class ReplayConfigNotFoundException extends RuntimeException {
    public ReplayConfigNotFoundException(String message) {
        super(message);
    }
}
