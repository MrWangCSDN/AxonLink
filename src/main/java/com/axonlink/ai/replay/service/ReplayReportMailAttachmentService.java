package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayGeneratedReportRef;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentSource;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
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
import java.util.Comparator;
import java.util.Objects;

@Service
public class ReplayReportMailAttachmentService {

    static final long MAX_LOCAL_FILE_SIZE = 20L * 1024 * 1024;
    static final long MAX_TOTAL_SIZE = 50L * 1024 * 1024;

    private final ReplayDailyDataDao dailyDataDao;
    private final ReplayWeeklyReportDao weeklyReportDao;
    private final ReplayReportSummaryCodec summaryCodec;
    private final ReplayLegacySummaryExtractor legacySummaryExtractor;

    public ReplayReportMailAttachmentService(ReplayDailyDataDao dailyDataDao) {
        this(dailyDataDao, null, new ReplayReportSummaryCodec(), new ReplayLegacySummaryExtractor());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReplayReportMailAttachmentService(ReplayDailyDataDao dailyDataDao,
                                             ReplayWeeklyReportDao weeklyReportDao,
                                             ReplayReportSummaryCodec summaryCodec,
                                             ReplayLegacySummaryExtractor legacySummaryExtractor) {
        this.dailyDataDao = dailyDataDao;
        this.weeklyReportDao = weeklyReportDao;
        this.summaryCodec = summaryCodec;
        this.legacySummaryExtractor = legacySummaryExtractor;
    }

    public ResolvedReportMail resolve(MailAttachment current,
                                      ReplayMailAttachmentMetadata currentMetadata,
                                      ReplayReportSummaryView currentSummary,
                                      List<ReplayGeneratedReportRef> generatedReports,
                                      List<MultipartFile> localFiles) {
        List<ResolvedSystemReport> systemReports = new ArrayList<>();
        systemReports.add(new ResolvedSystemReport(current, currentMetadata, currentSummary));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(businessKey(currentSummary.period(), currentSummary.startBatchNo(), currentSummary.endBatchNo()));
        if (generatedReports != null) {
            for (ReplayGeneratedReportRef ref : generatedReports) {
                if (ref == null || !seen.add(ref.businessKey())) continue;
                systemReports.add(load(ref));
            }
        }
        systemReports.sort(Comparator
                .comparingInt((ResolvedSystemReport report) -> "RPT".equals(report.summary().family()) ? 0 : 1)
                .thenComparing(report -> report.summary().period())
                .thenComparing(report -> report.summary().endBatchNo(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(report -> report.summary().startBatchNo(), Comparator.nullsFirst(String::compareTo)));

        List<MailAttachment> attachments = new ArrayList<>();
        List<ReplayMailAttachmentMetadata> metadata = new ArrayList<>();
        List<ReplayReportSummaryView> summaries = new ArrayList<>();
        long totalSize = 0;
        for (ResolvedSystemReport report : systemReports) {
            attachments.add(report.attachment());
            metadata.add(report.metadata());
            summaries.add(report.summary());
            totalSize += report.attachment().content().length;
        }
        LocalAttachments local = resolveLocal(localFiles);
        attachments.addAll(local.attachments());
        metadata.addAll(local.metadata());
        totalSize += local.totalSize();
        if (totalSize > MAX_TOTAL_SIZE) throw new AttachmentTotalSizeException();
        return new ResolvedReportMail(attachments, metadata, summaries, totalSize);
    }

    public List<ResolvedSystemReport> resolveSystemReports(ReplayGeneratedReportRef currentReport,
                                                            List<ReplayGeneratedReportRef> generatedReports) {
        if (currentReport == null) throw new IllegalArgumentException("当前报告附件不能为空");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ResolvedSystemReport> reports = new ArrayList<>();
        if (seen.add(currentReport.businessKey())) reports.add(load(currentReport));
        if (generatedReports != null) {
            for (ReplayGeneratedReportRef report : generatedReports) {
                if (report != null && seen.add(report.businessKey())) reports.add(load(report));
            }
        }
        reports.sort(systemReportComparator());
        return List.copyOf(reports);
    }

    private ResolvedSystemReport load(ReplayGeneratedReportRef ref) {
        if (ref.period() == ReplayReportPeriod.DAILY) {
            ReplayDailyReportSnapshot snapshot = dailyDataDao.findReportSnapshot(ref.endBatchNo())
                    .orElseThrow(() -> new MissingReportSnapshotsException(List.of(ref.endBatchNo())));
            ReplayReportSummaryView summary = resolveSummary(snapshot.summaryViewJson(), snapshot.content(), ref);
            String fileName = ReplayReportFileNames.daily(snapshot.batchNo());
            return new ResolvedSystemReport(
                    new MailAttachment(fileName, snapshot.content(), snapshot.contentType()),
                    new ReplayMailAttachmentMetadata(fileName, snapshot.fileSize(),
                            ReplayMailAttachmentSource.GENERATED_DAILY, snapshot.batchNo(),
                            ReplayReportPeriod.DAILY, null, snapshot.batchNo()), summary);
        }
        if (weeklyReportDao == null) throw new MissingReportSnapshotsException(List.of(ref.businessKey()));
        ReplayWeeklyReportSnapshot snapshot = weeklyReportDao.findSnapshot(ref.startBatchNo(), ref.endBatchNo())
                .orElseThrow(() -> new MissingReportSnapshotsException(List.of(ref.businessKey())));
        ReplayReportSummaryView summary = resolveSummary(snapshot.summaryViewJson(), snapshot.content(), ref);
        String fileName = ReplayReportFileNames.weekly(snapshot.startBatchNo(), snapshot.endBatchNo());
        return new ResolvedSystemReport(
                new MailAttachment(fileName, snapshot.content(), snapshot.contentType()),
                new ReplayMailAttachmentMetadata(fileName, snapshot.fileSize(),
                        ReplayMailAttachmentSource.GENERATED_WEEKLY, snapshot.endBatchNo(),
                        ReplayReportPeriod.WEEKLY, snapshot.startBatchNo(), snapshot.endBatchNo()), summary);
    }

    public ReplayReportSummaryView resolveSummary(String json, byte[] content, ReplayGeneratedReportRef ref) {
        try {
            return json == null || json.isBlank()
                    ? legacySummaryExtractor.extract(content, ref.period(), ref.startBatchNo(), ref.endBatchNo())
                    : summaryCodec.decode(json);
        } catch (RuntimeException exception) {
            throw new SummaryUnavailableException(ref.businessKey(), exception);
        }
    }

    private LocalAttachments resolveLocal(List<MultipartFile> localFiles) {
        List<MailAttachment> attachments = new ArrayList<>();
        List<ReplayMailAttachmentMetadata> metadata = new ArrayList<>();
        long totalSize = 0;
        if (localFiles == null) return new LocalAttachments(attachments, metadata, 0);
        for (MultipartFile file : localFiles) {
            if (file == null) continue;
            String original = file.getOriginalFilename();
            String name = original == null ? "" : StringUtils.cleanPath(original);
            if (name.isBlank() || name.contains("..") || !name.equals(original))
                throw new InvalidAttachmentException(name, "附件文件名无效");
            String lower = name.toLowerCase();
            if (!lower.endsWith(".xls") && !lower.endsWith(".xlsx"))
                throw new InvalidAttachmentException(name, "附件仅支持 Excel");
            if (file.isEmpty()) throw new InvalidAttachmentException(name, "附件不能为空");
            if (file.getSize() > MAX_LOCAL_FILE_SIZE) throw new AttachmentTooLargeException(name);
            byte[] content;
            try { content = file.getBytes(); }
            catch (IOException exception) { throw new InvalidAttachmentException(name, "附件读取失败"); }
            FileMagic magic;
            try { magic = FileMagic.valueOf(new ByteArrayInputStream(content)); }
            catch (IOException exception) { throw new InvalidAttachmentException(name, "附件读取失败"); }
            String contentType;
            if (magic == FileMagic.OLE2 && lower.endsWith(".xls")) contentType = "application/vnd.ms-excel";
            else if (magic == FileMagic.OOXML && lower.endsWith(".xlsx"))
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            else throw new InvalidAttachmentException(name, "附件内容不是有效 Excel");
            attachments.add(new MailAttachment(name, content, contentType));
            metadata.add(new ReplayMailAttachmentMetadata(name, content.length,
                    ReplayMailAttachmentSource.LOCAL_EXCEL, null, null, null, null));
            totalSize += content.length;
        }
        return new LocalAttachments(attachments, metadata, totalSize);
    }

    private static String businessKey(ReplayReportPeriod period, String start, String end) {
        return period + "|" + Objects.toString(start, "") + "|" + end;
    }

    private static Comparator<ResolvedSystemReport> systemReportComparator() {
        return Comparator
                .comparingInt((ResolvedSystemReport report) -> "RPT".equals(report.summary().family()) ? 0 : 1)
                .thenComparing(report -> report.summary().period())
                .thenComparing(report -> report.summary().endBatchNo(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(report -> report.summary().startBatchNo(), Comparator.nullsFirst(String::compareTo));
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
            String fileName = ReplayReportFileNames.daily(snapshot.batchNo());
            mailAttachments.add(new MailAttachment(fileName, snapshot.content(), snapshot.contentType()));
            metadata.add(new ReplayMailAttachmentMetadata(fileName, snapshot.fileSize(),
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

    public record ResolvedReportMail(List<MailAttachment> mailAttachments,
                                     List<ReplayMailAttachmentMetadata> metadata,
                                     List<ReplayReportSummaryView> summaries,
                                     long totalSize) {
        public ResolvedReportMail {
            mailAttachments = List.copyOf(mailAttachments);
            metadata = List.copyOf(metadata);
            summaries = List.copyOf(summaries);
        }
    }

    public record ResolvedSystemReport(MailAttachment attachment,
                                       ReplayMailAttachmentMetadata metadata,
                                       ReplayReportSummaryView summary) {}

    private record LocalAttachments(List<MailAttachment> attachments,
                                    List<ReplayMailAttachmentMetadata> metadata,
                                    long totalSize) {}

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

    public static final class SummaryUnavailableException extends RuntimeException {
        public SummaryUnavailableException(String report, Throwable cause) {
            super("报告汇总信息不可用：" + report, cause);
        }
    }
}
