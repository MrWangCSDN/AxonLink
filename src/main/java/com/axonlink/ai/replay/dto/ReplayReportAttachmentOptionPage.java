package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayReportAttachmentOptionPage(
        List<ReplayReportAttachmentOption> items,
        int page,
        int size,
        long total) {
    public ReplayReportAttachmentOptionPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
