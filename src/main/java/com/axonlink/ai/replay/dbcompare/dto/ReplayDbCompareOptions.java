package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareOptions(
        List<String> domains,
        boolean importEnabled,
        boolean canImport) {

    public ReplayDbCompareOptions {
        domains = List.copyOf(domains);
    }
}
