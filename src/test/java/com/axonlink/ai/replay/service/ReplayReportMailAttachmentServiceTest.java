package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentSource;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
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

        assertEquals(List.of("current.xlsx", "extra.xlsx", "local.xls"),
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
        return new ReplayDailyReportSnapshot(batchNo, name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes, bytes.length, LocalDateTime.of(2026, 9, 15, 12, 0));
    }

    private static byte[] zipBytes() {
        return new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0};
    }

    private static byte[] oleBytes() {
        return new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    }
}
