package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentSource;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.notification.service.MailAttachment;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ReplayReportMailAttachmentService {

    static final long MAX_LOCAL_FILE_SIZE = 20L * 1024 * 1024;
    static final long MAX_TOTAL_SIZE = 50L * 1024 * 1024;

    private final ReplayDailyDataDao dailyDataDao;

    public ReplayReportMailAttachmentService(ReplayDailyDataDao dailyDataDao) {
        this.dailyDataDao = dailyDataDao;
    }

    public ResolvedAttachments resolve(MailAttachment current,
                                       ReplayMailAttachmentMetadata currentMetadata,
                                       List<String> reportBatchNos,
                                       List<MultipartFile> localFiles,
                                       String currentDailyBatchNo) {
        List<MailAttachment> mailAttachments = new ArrayList<>();
        List<ReplayMailAttachmentMetadata> metadata = new ArrayList<>();
        mailAttachments.add(current);
        metadata.add(currentMetadata);
        long totalSize = current.content().length;

        LinkedHashSet<String> batches = new LinkedHashSet<>();
        if (reportBatchNos != null) {
            reportBatchNos.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).filter(value -> !value.equals(currentDailyBatchNo)).forEach(batches::add);
        }
        Map<String, ReplayDailyReportSnapshot> snapshots = dailyDataDao.findReportSnapshots(List.copyOf(batches));
        List<String> missing = batches.stream().filter(batch -> !snapshots.containsKey(batch)).toList();
        if (!missing.isEmpty()) throw new MissingReportSnapshotsException(missing);
        for (String batch : batches) {
            ReplayDailyReportSnapshot snapshot = snapshots.get(batch);
            mailAttachments.add(new MailAttachment(snapshot.fileName(), snapshot.content(), snapshot.contentType()));
            metadata.add(new ReplayMailAttachmentMetadata(snapshot.fileName(), snapshot.fileSize(),
                    ReplayMailAttachmentSource.GENERATED_DAILY, snapshot.batchNo()));
            totalSize += snapshot.fileSize();
        }

        if (localFiles != null) {
            for (MultipartFile file : localFiles) {
                if (file == null) continue;
                String original = file.getOriginalFilename();
                String name = original == null ? "" : StringUtils.cleanPath(original);
                if (name.isBlank() || name.contains("..") || !name.equals(original)) {
                    throw new InvalidAttachmentException(name, "附件文件名无效");
                }
                String lower = name.toLowerCase();
                if (!lower.endsWith(".xls") && !lower.endsWith(".xlsx")) {
                    throw new InvalidAttachmentException(name, "附件仅支持 Excel");
                }
                if (file.isEmpty()) throw new InvalidAttachmentException(name, "附件不能为空");
                if (file.getSize() > MAX_LOCAL_FILE_SIZE) throw new AttachmentTooLargeException(name);
                byte[] content;
                try {
                    content = file.getBytes();
                } catch (IOException exception) {
                    throw new InvalidAttachmentException(name, "附件读取失败");
                }
                FileMagic magic;
                try {
                    magic = FileMagic.valueOf(new ByteArrayInputStream(content));
                } catch (IOException exception) {
                    throw new InvalidAttachmentException(name, "附件读取失败");
                }
                String contentType;
                if (magic == FileMagic.OLE2 && lower.endsWith(".xls")) {
                    contentType = "application/vnd.ms-excel";
                } else if (magic == FileMagic.OOXML && lower.endsWith(".xlsx")) {
                    contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                } else {
                    throw new InvalidAttachmentException(name, "附件内容不是有效 Excel");
                }
                mailAttachments.add(new MailAttachment(name, content, contentType));
                metadata.add(new ReplayMailAttachmentMetadata(name, content.length,
                        ReplayMailAttachmentSource.LOCAL_EXCEL, null));
                totalSize += content.length;
            }
        }
        if (totalSize > MAX_TOTAL_SIZE) throw new AttachmentTotalSizeException();
        return new ResolvedAttachments(mailAttachments, metadata, totalSize);
    }

    public record ResolvedAttachments(List<MailAttachment> mailAttachments,
                                      List<ReplayMailAttachmentMetadata> metadata,
                                      long totalSize) {
        public ResolvedAttachments {
            mailAttachments = List.copyOf(mailAttachments);
            metadata = List.copyOf(metadata);
        }
    }

    public static class InvalidAttachmentException extends IllegalArgumentException {
        public InvalidAttachmentException(String fileName, String reason) {
            super(reason + "：" + fileName);
        }
    }

    public static final class AttachmentTooLargeException extends RuntimeException {
        public AttachmentTooLargeException(String fileName) {
            super("单个附件不能超过20MB：" + fileName);
        }
    }

    public static final class AttachmentTotalSizeException extends RuntimeException {
        public AttachmentTotalSizeException() {
            super("附件总大小不能超过50MB");
        }
    }

    public static final class MissingReportSnapshotsException extends RuntimeException {
        public MissingReportSnapshotsException(List<String> batchNos) {
            super("以下日报尚未生成：" + String.join("、", batchNos));
        }
    }
}
