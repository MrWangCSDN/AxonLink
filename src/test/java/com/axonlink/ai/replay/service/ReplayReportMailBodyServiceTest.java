package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayGeneratedReportRef;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentSource;
import com.axonlink.ai.replay.dto.ReplayReportMailBodyPreviewRequest;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.notification.service.MailAttachment;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayReportMailBodyServiceTest {

    private final ReplayReportMailAttachmentService attachmentService = mock(ReplayReportMailAttachmentService.class);
    private final ReplayReportMailBodyMetricExtractor extractor = mock(ReplayReportMailBodyMetricExtractor.class);
    private final ReplayReportMailBodyService service = new ReplayReportMailBodyService(
            attachmentService, extractor, new ReplayReportMailBodyComposer());

    @Test
    void canBeCreatedBySpringWhenTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("attachmentService", attachmentService);
            context.registerBean(ReplayReportMailBodyService.class);

            context.refresh();

            assertThat(context.getBean(ReplayReportMailBodyService.class)).isNotNull();
        }
    }

    @Test
    void aggregatesMultipleReportsByFamilyAndUsesCurrentPeriodAsTemplate() {
        ReplayGeneratedReportRef current = daily("RPT20260916-01");
        List<ReplayGeneratedReportRef> selected = List.of(
                daily("RPT20260915-01"), daily("DZ20260916-01"));
        var queryCurrent = report(ReplayReportPeriod.DAILY, "RPT", null, "RPT20260916-01");
        var queryPrevious = report(ReplayReportPeriod.DAILY, "RPT", null, "RPT20260915-01");
        var accounting = report(ReplayReportPeriod.DAILY, "DZ", null, "DZ20260916-01");
        when(attachmentService.resolveSystemReports(current, selected))
                .thenReturn(List.of(queryCurrent, queryPrevious, accounting));
        when(extractor.extract(queryCurrent.attachment().content(), queryCurrent.summary(),
                queryCurrent.summary().endBatchNo()))
                .thenReturn(new ReplayReportMailBodyMetricExtractor.Metrics(10, 9, 100));
        when(extractor.extract(queryPrevious.attachment().content(), queryPrevious.summary(),
                queryPrevious.summary().endBatchNo()))
                .thenReturn(new ReplayReportMailBodyMetricExtractor.Metrics(20, 18, 200));
        when(extractor.extract(accounting.attachment().content(), accounting.summary(),
                accounting.summary().endBatchNo()))
                .thenReturn(new ReplayReportMailBodyMetricExtractor.Metrics(30, 27, 300));

        var preview = service.preview(new ReplayReportMailBodyPreviewRequest(current, selected));

        assertThat(preview.body()).isEqualTo("""
                各位领导、老师：
                查询交易总交易30，本轮回放实发交易27，采集交易量300，实发交易量300；
                账务交易总交易30，本轮回放实发交易27，采集交易量300，实发交易量300。""");
    }

    @Test
    void usesWeeklyTemplateWhenCurrentReportIsWeekly() {
        ReplayGeneratedReportRef current = new ReplayGeneratedReportRef(
                ReplayReportPeriod.WEEKLY, "DZ20260909-01", "DZ20260916-01");
        var accounting = report(ReplayReportPeriod.WEEKLY, "DZ",
                "DZ20260909-01", "DZ20260916-01");
        when(attachmentService.resolveSystemReports(current, List.of())).thenReturn(List.of(accounting));
        when(extractor.extract(accounting.attachment().content(), accounting.summary(),
                accounting.summary().endBatchNo()))
                .thenReturn(new ReplayReportMailBodyMetricExtractor.Metrics(30, 27, 300));

        var preview = service.preview(new ReplayReportMailBodyPreviewRequest(current, List.of()));

        assertThat(preview.body()).contains("本周回放比对主要内容如下，请查阅，谢谢。");
    }

    @Test
    void rejectsMissingCurrentReport() {
        assertThatThrownBy(() -> service.preview(new ReplayReportMailBodyPreviewRequest(null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("当前报告附件不能为空");
    }

    private static ReplayGeneratedReportRef daily(String batchNo) {
        return new ReplayGeneratedReportRef(ReplayReportPeriod.DAILY, null, batchNo);
    }

    private static ReplayReportMailAttachmentService.ResolvedSystemReport report(
            ReplayReportPeriod period, String family, String start, String end) {
        byte[] content = (period + family + end).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ReplayReportSummaryView summary = new ReplayReportSummaryView(1, period, family, start, end,
                "报告", List.of(), List.of(),
                new ReplayReportSummaryRow("合计", "TOTAL", Map.of("sentTransactionCount", 1L)));
        MailAttachment attachment = new MailAttachment(end + ".xlsx", content,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        ReplayMailAttachmentMetadata metadata = new ReplayMailAttachmentMetadata(
                end + ".xlsx", content.length, ReplayMailAttachmentSource.GENERATED_DAILY,
                end, period, start, end);
        return new ReplayReportMailAttachmentService.ResolvedSystemReport(attachment, metadata, summary);
    }
}
