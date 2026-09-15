package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareAuditGroupPage(
        List<ReplayDbCompareAuditGroup> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public ReplayDbCompareAuditGroupPage {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
