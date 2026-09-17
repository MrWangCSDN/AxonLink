package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayReportMailBodyPreviewRequest(
        ReplayGeneratedReportRef currentReport,
        List<ReplayGeneratedReportRef> generatedReports) {

    public ReplayReportMailBodyPreviewRequest {
        generatedReports = generatedReports == null ? List.of() : List.copyOf(generatedReports);
    }
}
