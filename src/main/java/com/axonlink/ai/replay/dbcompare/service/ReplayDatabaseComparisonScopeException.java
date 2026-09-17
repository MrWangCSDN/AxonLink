package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScopeValidationError;

import java.util.List;

public class ReplayDatabaseComparisonScopeException extends RuntimeException {

    private final List<ReplayDbCompareScopeValidationError> errors;

    public ReplayDatabaseComparisonScopeException(List<ReplayDbCompareScopeValidationError> errors) {
        super("比对范围配置存在 " + errors.size() + " 个问题");
        this.errors = List.copyOf(errors);
    }

    public List<ReplayDbCompareScopeValidationError> errors() {
        return errors;
    }
}
