package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareAuditPage(
        List<ReplayDbCompareAuditEvent> items,
        int page,
        int size,
        long total) {

    public ReplayDbCompareAuditPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
