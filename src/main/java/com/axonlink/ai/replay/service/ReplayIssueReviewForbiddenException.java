package com.axonlink.ai.replay.service;

/** Raised when the current operator is not allowed to review the issue's group. */
public class ReplayIssueReviewForbiddenException extends RuntimeException {
    public ReplayIssueReviewForbiddenException(String message) {
        super(message);
    }
}
