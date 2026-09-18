package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayDailyReportMailSendRequest(
        String batchNo,
        String subject,
        List<String> toEmails,
        List<String> ccEmails,
        String body,
        List<String> reportBatchNos,
        List<ReplayGeneratedReportRef> generatedReports) {
    public ReplayDailyReportMailSendRequest {
        reportBatchNos = reportBatchNos == null ? List.of() : List.copyOf(reportBatchNos);
        generatedReports = generatedReports == null ? List.of() : List.copyOf(generatedReports);
    }

    public ReplayDailyReportMailSendRequest(String batchNo, String subject, List<String> toEmails,
                                            List<String> ccEmails, String body, List<String> reportBatchNos) {
        this(batchNo, subject, toEmails, ccEmails, body, reportBatchNos, List.of());
    }

    public ReplayDailyReportMailSendRequest(String batchNo, String subject, List<String> toEmails,
                                            List<String> ccEmails, String body) {
        this(batchNo, subject, toEmails, ccEmails, body, List.of(), List.of());
    }

    public List<ReplayGeneratedReportRef> effectiveGeneratedReports() {
        if (generatedReports != null && !generatedReports.isEmpty()) return List.copyOf(generatedReports);
        if (reportBatchNos == null) return List.of();
        return reportBatchNos.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> new ReplayGeneratedReportRef(ReplayReportPeriod.DAILY, null, value))
                .toList();
    }
}
