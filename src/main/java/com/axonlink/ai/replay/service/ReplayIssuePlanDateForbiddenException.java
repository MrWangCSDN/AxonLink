package com.axonlink.ai.replay.service;

/** Raised when the current user cannot edit the issue group's planned completion date. */
public class ReplayIssuePlanDateForbiddenException extends RuntimeException {
    public ReplayIssuePlanDateForbiddenException(String message) {
        super(message);
    }
}
