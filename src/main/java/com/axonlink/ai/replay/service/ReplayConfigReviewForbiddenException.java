package com.axonlink.ai.replay.service;

/** 当前登录人无审核权限（非该行行方负责人，或该行无审核人）。 */
public class ReplayConfigReviewForbiddenException extends RuntimeException {
    public ReplayConfigReviewForbiddenException(String message) {
        super(message);
    }
}
