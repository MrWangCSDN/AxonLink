package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentSource;
import com.axonlink.ai.replay.dto.ReplayGeneratedReportRef;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryColumn;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import com.axonlink.notification.service.MailAttachment;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayReportMailAttachmentServiceTest {

    private final ReplayDailyDataDao dao = mock(ReplayDailyDataDao.class);
    private final ReplayReportMailAttachmentService service = new ReplayReportMailAttachmentService(dao);

    @Test
    void resolvesSystemReportsWithCurrentDeduplicatedAndQueryBeforeAccounting() {
        ReplayWeeklyReportDao weeklyDao = mock(ReplayWeeklyReportDao.class);
        ReplayReportSummaryCodec codec = new ReplayReportSummaryCodec();
        ReplayReportMailAttachmentService mixedService = new ReplayReportMailAttachmentService(
                dao, weeklyDao, codec, new ReplayLegacySummaryExtractor());
        ReplayDailyReportSnapshot currentSnapshot = snapshot("DZ20260916-01", "current.xlsx", zipBytes(),
                codec.encode(summary(ReplayReportPeriod.DAILY, null, "DZ20260916-01")));
        ReplayWeeklyReportSnapshot queryWeekly = weekly("RPT20260909-01", "RPT20260916-01", codec);
        when(dao.findReportSnapshot("DZ20260916-01")).thenReturn(java.util.Optional.of(currentSnapshot));
        when(weeklyDao.findSnapshot(queryWeekly.startBatchNo(), queryWeekly.endBatchNo()))
                .thenReturn(java.util.Optional.of(queryWeekly));
        ReplayGeneratedReportRef current = new ReplayGeneratedReportRef(
                ReplayReportPeriod.DAILY, null, "DZ20260916-01");

        var resolved = mixedService.resolveSystemReports(current, List.of(
                current,
                new ReplayGeneratedReportRef(ReplayReportPeriod.WEEKLY,
                        "RPT20260909-01", "RPT20260916-01")));

        assertEquals(List.of("RPT", "DZ"),
                resolved.stream().map(item -> item.summary().family()).toList());
        assertEquals(2, resolved.size());
    }

    @Test
    void ordersMixedGeneratedReportsQueryBeforeAccountingAndDeduplicatesCurrent() {
        ReplayWeeklyReportDao weeklyDao = mock(ReplayWeeklyReportDao.class);
        ReplayReportSummaryCodec codec = new ReplayReportSummaryCodec();
        ReplayReportMailAttachmentService mixedService = new ReplayReportMailAttachmentService(
                dao, weeklyDao, codec, new ReplayLegacySummaryExtractor());
        var rptDaily = snapshot("RPT20260915-01", "rpt-daily.xlsx", zipBytes(),
                codec.encode(summary(ReplayReportPeriod.DAILY, null, "RPT20260915-01")));
        when(dao.findReportSnapshot("RPT20260915-01")).thenReturn(java.util.Optional.of(rptDaily));
        var rptWeekly = weekly("RPT20260909-01", "RPT20260916-01", codec);
        when(weeklyDao.findSnapshot(rptWeekly.startBatchNo(), rptWeekly.endBatchNo()))
                .thenReturn(java.util.Optional.of(rptWeekly));
        ReplayReportSummaryView currentSummary = summary(
                ReplayReportPeriod.WEEKLY, "DZ20260909-01", "DZ20260916-01");
        MailAttachment current = new MailAttachment("current.xlsx", zipBytes(), "application/octet-stream");
        ReplayMailAttachmentMetadata currentMetadata = new ReplayMailAttachmentMetadata(
                "current.xlsx", current.content().length, ReplayMailAttachmentSource.CURRENT_REPORT,
                "DZ20260916-01", ReplayReportPeriod.WEEKLY, "DZ20260909-01", "DZ20260916-01");

        var result = mixedService.resolve(current, currentMetadata, currentSummary, List.of(
                new ReplayGeneratedReportRef(ReplayReportPeriod.WEEKLY, "DZ20260909-01", "DZ20260916-01"),
                new ReplayGeneratedReportRef(ReplayReportPeriod.DAILY, null, "RPT20260915-01"),
                new ReplayGeneratedReportRef(ReplayReportPeriod.WEEKLY, "RPT20260909-01", "RPT20260916-01")),
                List.of());

        assertEquals(List.of("RPT", "RPT", "DZ"),
                result.summaries().stream().map(ReplayReportSummaryView::family).toList());
        assertEquals(List.of(ReplayReportPeriod.DAILY, ReplayReportPeriod.WEEKLY, ReplayReportPeriod.WEEKLY),
                result.summaries().stream().map(ReplayReportSummaryView::period).toList());
    }

    @Test
    void ordersAndDeduplicatesCurrentGeneratedAndLocalAttachments() {
        ReplayDailyReportSnapshot extra = snapshot("DZ20260914-01", "extra.xlsx", zipBytes());
        when(dao.findReportSnapshots(List.of("DZ20260914-01")))
                .thenReturn(new LinkedHashMap<>(java.util.Map.of(extra.batchNo(), extra)));
        MailAttachment current = new MailAttachment("current.xlsx", zipBytes(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        ReplayMailAttachmentMetadata currentMetadata = new ReplayMailAttachmentMetadata(
                "current.xlsx", current.content().length, ReplayMailAttachmentSource.CURRENT_REPORT, "RPT20260915-01");
        MockMultipartFile local = new MockMultipartFile("files", "local.xls", "application/octet-stream", oleBytes());

        var result = service.resolve(current, currentMetadata,
                List.of("RPT20260915-01", "DZ20260914-01", "DZ20260914-01"), List.of(local), "RPT20260915-01");

        assertEquals(List.of("current.xlsx", "账务日报-20260914.xlsx", "local.xls"),
                result.mailAttachments().stream().map(MailAttachment::fileName).toList());
        assertEquals(List.of(ReplayMailAttachmentSource.CURRENT_REPORT,
                        ReplayMailAttachmentSource.GENERATED_DAILY, ReplayMailAttachmentSource.LOCAL_EXCEL),
                result.metadata().stream().map(ReplayMailAttachmentMetadata::source).toList());
    }

    @Test
    void rejectsNonExcelContent() {
        MailAttachment current = new MailAttachment("current.xlsx", zipBytes(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        ReplayMailAttachmentMetadata metadata = new ReplayMailAttachmentMetadata(
                "current.xlsx", current.content().length, ReplayMailAttachmentSource.CURRENT_REPORT, "RPT20260915-01");
        MockMultipartFile invalid = new MockMultipartFile("files", "fake.xlsx", "application/octet-stream", "plain".getBytes());

        assertThrows(ReplayReportMailAttachmentService.InvalidAttachmentException.class,
                () -> service.resolve(current, metadata, List.of(), List.of(invalid), "RPT20260915-01"));
    }

    private static ReplayDailyReportSnapshot snapshot(String batchNo, String name, byte[] bytes) {
        return snapshot(batchNo, name, bytes, null);
    }

    private static ReplayDailyReportSnapshot snapshot(String batchNo, String name, byte[] bytes, String json) {
        return new ReplayDailyReportSnapshot(batchNo, name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes, bytes.length, json, LocalDateTime.of(2026, 9, 15, 12, 0));
    }

    private static ReplayWeeklyReportSnapshot weekly(String start, String end, ReplayReportSummaryCodec codec) {
        byte[] bytes = zipBytes();
        return new ReplayWeeklyReportSnapshot(start, end, "weekly.xlsx", "application/octet-stream",
                bytes, bytes.length, codec.encode(summary(ReplayReportPeriod.WEEKLY, start, end)),
                LocalDateTime.of(2026, 9, 15, 12, 0));
    }

    private static ReplayReportSummaryView summary(ReplayReportPeriod period, String start, String end) {
        var column = new ReplayReportSummaryColumn("domain", "领域", null,
                ReplayReportSummaryColumn.ValueType.TEXT, 0);
        var detail = new ReplayReportSummaryRow("公共组", "DETAIL", java.util.Map.of("domain", "公共组"));
        var total = new ReplayReportSummaryRow("合计", "TOTAL", java.util.Map.of("domain", "合计"));
        return new ReplayReportSummaryView(1, period, end.startsWith("DZ") ? "DZ" : "RPT",
                start, end, "报告", List.of(column), List.of(detail), total);
    }

    private static byte[] zipBytes() {
        return new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0};
    }

    private static byte[] oleBytes() {
        return new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    }
}
