package com.axonlink.ai.replay.service;

public class ReplayIssueDomainForbiddenException extends RuntimeException {
    public ReplayIssueDomainForbiddenException(String message) {
        super(message);
    }
}
