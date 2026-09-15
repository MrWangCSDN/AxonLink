package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareVersionGateResult(
        List<ReplayDbCompareRegistration> registrations,
        List<ReplayDbCompareVersionGateError> errors) {

    public ReplayDbCompareVersionGateResult {
        registrations = registrations == null ? List.of() : List.copyOf(registrations);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean valid() {
        return !registrations.isEmpty() && errors.isEmpty();
    }
}
