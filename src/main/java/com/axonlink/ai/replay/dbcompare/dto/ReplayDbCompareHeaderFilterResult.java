package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareHeaderFilterResult(
        List<ReplayDbCompareHeaderFilterOption> options,
        long candidateCount,
        long matchedRegistrationCount,
        boolean truncated) {

    public ReplayDbCompareHeaderFilterResult {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
