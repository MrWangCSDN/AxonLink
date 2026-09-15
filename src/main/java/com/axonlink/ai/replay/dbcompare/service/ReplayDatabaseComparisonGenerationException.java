package com.axonlink.ai.replay.dbcompare.service;

import org.springframework.http.HttpStatus;

public class ReplayDatabaseComparisonGenerationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Object data;

    public ReplayDatabaseComparisonGenerationException(
            HttpStatus status,
            String errorCode,
            String message,
            Object data) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.data = data;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public Object data() {
        return data;
    }
}
