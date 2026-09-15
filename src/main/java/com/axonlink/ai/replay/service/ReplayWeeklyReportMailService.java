package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.config.ReplayDailyReportMailProperties;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailSendRequest;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailStatus;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailView;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentSource;
import com.axonlink.notification.service.MailAttachment;
import org.springframework.web.multipart.MultipartFile;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportMailDao;
import com.axonlink.notification.service.MailService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReplayWeeklyReportMailService {

    private static final Pattern BATCH_DATE = Pattern.compile("^(?:RPT|DZ)(\\d{8}).+");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_SUBJECT_LENGTH = 255;
    private static final int MAX_BODY_LENGTH = 10_000;

    private final ReplayWeeklyReportDao weeklyReportDao;
    private final ReplayWeeklyReportMailDao mailDao;
    private final ReplayDailyReportMailProperties properties;
    private final MailService mailService;
    private final ReplayReportMailAttachmentService attachmentService;

    public ReplayWeeklyReportMailService(ReplayWeeklyReportDao weeklyReportDao,
                                         ReplayWeeklyReportMailDao mailDao,
                                         ReplayDailyReportMailProperties properties,
                                         MailService mailService,
                                         ReplayReportMailAttachmentService attachmentService) {
        this.weeklyReportDao = weeklyReportDao;
        this.mailDao = mailDao;
        this.properties = properties;
        this.mailService = mailService;
        this.attachmentService = attachmentService;
    }

    public ReplayWeeklyReportMailView configuration(String startBatchNo, String endBatchNo) {
        MailContext context = context(startBatchNo, endBatchNo);
        return view(context, mailDao.find(context.snapshot().startBatchNo(), context.snapshot().endBatchNo())
                .orElse(null));
    }

    public ReplayWeeklyReportMailView send(ReplayWeeklyReportMailSendRequest request) {
        return send(request, List.of());
    }

    public ReplayWeeklyReportMailView send(ReplayWeeklyReportMailSendRequest request, List<MultipartFile> files) {
        if (request == null) {
            throw new IllegalArgumentException("邮件请求不能为空");
        }
        MailContext defaults = context(request.startBatchNo(), request.endBatchNo());
        String subject = request.subject() == null ? "" : request.subject().trim();
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("邮件标题不能为空");
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("邮件标题不能超过255个字符");
        }
        List<String> toEmails = validateEmails(request.toEmails());
        if (toEmails.isEmpty()) {
            throw new IllegalArgumentException("邮件收件人不能为空");
        }
        List<String> ccEmails = validateEmails(request.ccEmails());
        String body = request.body();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("邮件正文不能为空");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("邮件正文不能超过10000个字符");
        }
        ReplayWeeklyReportSnapshot snapshot = defaults.snapshot();
        if (snapshot.content() == null || snapshot.content().length == 0) {
            throw new SnapshotNotFoundException();
        }
        MailContext context = new MailContext(snapshot, subject, defaults.sender(), toEmails, ccEmails, body);
        ReplayMailAttachmentMetadata currentMetadata = currentAttachment(snapshot);
        var resolved = attachmentService.resolve(
                new MailAttachment(snapshot.fileName(), snapshot.content(), snapshot.contentType()), currentMetadata,
                request.reportBatchNos(), files, null);
        mailDao.markSending(snapshot.startBatchNo(), snapshot.endBatchNo(), subject, body,
                context.sender(), toEmails, ccEmails, resolved.metadata());
        try {
            mailService.sendTextWithAttachmentsSync(toEmails, ccEmails, subject, body, resolved.mailAttachments());
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null ? "邮件发送失败" : exception.getMessage();
            mailDao.markFailed(snapshot.startBatchNo(), snapshot.endBatchNo(), reason);
            throw new MailSendException(exception);
        }
        mailDao.markSent(snapshot.startBatchNo(), snapshot.endBatchNo());
        return view(context, mailDao.find(snapshot.startBatchNo(), snapshot.endBatchNo()).orElseThrow());
    }

    private MailContext context(String startBatchNo, String endBatchNo) {
        String normalizedStart = normalizeBatch(startBatchNo);
        String normalizedEnd = normalizeBatch(endBatchNo);
        Matcher endMatcher = BATCH_DATE.matcher(normalizedEnd);
        if (!endMatcher.matches()) {
            throw new MalformedBatchException();
        }
        ReplayWeeklyReportSnapshot snapshot = weeklyReportDao.findSnapshot(normalizedStart, normalizedEnd)
                .orElseThrow(SnapshotNotFoundException::new);
        List<String> toEmails = normalizeEmails(properties.getTo());
        List<String> ccEmails = normalizeEmails(properties.getCc());
        String sender = mailService.configuredFrom();
        if (sender == null || sender.isBlank()
                || toEmails.stream().anyMatch(email -> !EMAIL.matcher(email).matches())
                || ccEmails.stream().anyMatch(email -> !EMAIL.matcher(email).matches())) {
            throw new ConfigurationException();
        }
        String prefix = properties.getWeeklySubjectPrefix() == null ? "" : properties.getWeeklySubjectPrefix();
        String body = properties.getWeeklyBody() == null ? "" : properties.getWeeklyBody();
        return new MailContext(snapshot, prefix + endMatcher.group(1), sender.trim(), toEmails, ccEmails, body);
    }

    private static String normalizeBatch(String batchNo) {
        String normalized = batchNo == null ? "" : batchNo.trim();
        if (!BATCH_DATE.matcher(normalized).matches()) {
            throw new MalformedBatchException();
        }
        return normalized;
    }

    private ReplayWeeklyReportMailView view(MailContext context, ReplayWeeklyReportMailStatus status) {
        ReplayWeeklyReportSnapshot snapshot = context.snapshot();
        ReplayMailAttachmentMetadata current = currentAttachment(snapshot);
        return new ReplayWeeklyReportMailView(snapshot.startBatchNo(), snapshot.endBatchNo(), context.subject(),
                context.toEmails(), context.ccEmails(), context.body(), current,
                status == null ? List.of(current) : status.attachments(),
                status == null ? "UNSENT" : status.status(), status == null ? null : status.sentAt(),
                status == null ? null : status.failureMessage());
    }

    private ReplayMailAttachmentMetadata currentAttachment(ReplayWeeklyReportSnapshot snapshot) {
        return new ReplayMailAttachmentMetadata(snapshot.fileName(), snapshot.fileSize(),
                ReplayMailAttachmentSource.CURRENT_REPORT, snapshot.endBatchNo());
    }

    private List<String> validateEmails(List<String> values) {
        List<String> emails = normalizeEmails(values);
        for (String email : emails) {
            if (!EMAIL.matcher(email).matches()) {
                throw new IllegalArgumentException("邮箱格式错误：" + email);
            }
        }
        return emails;
    }

    private List<String> normalizeEmails(List<String> configured) {
        LinkedHashMap<String, String> emails = new LinkedHashMap<>();
        if (configured != null) {
            for (String value : configured) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                for (String part : value.split("[;,，；\\r\\n]+")) {
                    String email = part.trim().toLowerCase(Locale.ROOT);
                    if (!email.isEmpty()) {
                        emails.putIfAbsent(email, email);
                    }
                }
            }
        }
        return List.copyOf(emails.values());
    }

    private record MailContext(ReplayWeeklyReportSnapshot snapshot, String subject, String sender,
                               List<String> toEmails, List<String> ccEmails, String body) {
    }

    public static final class MalformedBatchException extends RuntimeException {
    }

    public static final class SnapshotNotFoundException extends RuntimeException {
    }

    public static final class ConfigurationException extends RuntimeException {
    }

    public static final class MailSendException extends RuntimeException {
        public MailSendException(Throwable cause) {
            super("邮件发送失败", cause);
        }
    }
}
