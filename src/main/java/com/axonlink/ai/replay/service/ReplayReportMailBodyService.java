package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportMailBodyPreview;
import com.axonlink.ai.replay.dto.ReplayReportMailBodyPreviewRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReplayReportMailBodyService {

    private final ReplayReportMailAttachmentService attachmentService;
    private final ReplayReportMailBodyMetricExtractor extractor;
    private final ReplayReportMailBodyComposer composer;

    @Autowired
    public ReplayReportMailBodyService(ReplayReportMailAttachmentService attachmentService) {
        this(attachmentService, new ReplayReportMailBodyMetricExtractor(), new ReplayReportMailBodyComposer());
    }

    ReplayReportMailBodyService(ReplayReportMailAttachmentService attachmentService,
                                ReplayReportMailBodyMetricExtractor extractor,
                                ReplayReportMailBodyComposer composer) {
        this.attachmentService = attachmentService;
        this.extractor = extractor;
        this.composer = composer;
    }

    public ReplayReportMailBodyPreview preview(ReplayReportMailBodyPreviewRequest request) {
        if (request == null || request.currentReport() == null) {
            throw new IllegalArgumentException("当前报告附件不能为空");
        }
        List<ReplayReportMailAttachmentService.ResolvedSystemReport> reports =
                attachmentService.resolveSystemReports(request.currentReport(), request.generatedReports());
        if (reports.isEmpty()) throw new IllegalArgumentException("邮件报告附件不能为空");

        Map<String, MutableMetrics> totals = new LinkedHashMap<>();
        for (ReplayReportMailAttachmentService.ResolvedSystemReport report : reports) {
            String family = report.summary().family();
            if (!"RPT".equals(family) && !"DZ".equals(family)) {
                throw new IllegalArgumentException("报告批次族不支持：" + family);
            }
            ReplayReportMailBodyMetricExtractor.Metrics metrics = extractor.extract(
                    report.attachment().content(), report.summary(), report.summary().endBatchNo());
            totals.computeIfAbsent(family, ignored -> new MutableMetrics()).add(metrics);
        }

        List<ReplayReportMailBodyComposer.FamilyMetrics> familyMetrics = totals.entrySet().stream()
                .map(entry -> entry.getValue().toFamilyMetrics(entry.getKey()))
                .toList();
        String body = composer.compose(request.currentReport().period(), familyMetrics);
        return new ReplayReportMailBodyPreview(body);
    }

    private static final class MutableMetrics {
        private long expected;
        private long actual;
        private long collected;

        void add(ReplayReportMailBodyMetricExtractor.Metrics value) {
            try {
                expected = Math.addExact(expected, value.expectedTransactions());
                actual = Math.addExact(actual, value.actualTransactions());
                collected = Math.addExact(collected, value.collectedTransactions());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("报告正文统计数值超出范围", exception);
            }
        }

        ReplayReportMailBodyComposer.FamilyMetrics toFamilyMetrics(String family) {
            return new ReplayReportMailBodyComposer.FamilyMetrics(family, expected, actual, collected);
        }
    }
}
