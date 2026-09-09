package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.config.ReplayDailyReportMailProperties;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailStatus;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailSendRequest;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailView;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayDailyReportMailDao;
import com.axonlink.notification.service.MailService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReplayDailyReportMailService {

    private static final Pattern BATCH_DATE = Pattern.compile("^(?:RPT|DZ)(\\d{8}).+");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_SUBJECT_LENGTH = 255;
    private static final int MAX_BODY_LENGTH = 10_000;

    private final ReplayDailyDataDao dailyDataDao;
    private final ReplayDailyReportMailDao mailDao;
    private final ReplayDailyReportMailProperties properties;
    private final MailService mailService;

    public ReplayDailyReportMailService(ReplayDailyDataDao dailyDataDao,
                                        ReplayDailyReportMailDao mailDao,
                                        ReplayDailyReportMailProperties properties,
                                        MailService mailService) {
        this.dailyDataDao = dailyDataDao;
        this.mailDao = mailDao;
        this.properties = properties;
        this.mailService = mailService;
    }

    public ReplayDailyReportMailView configuration(String batchNo) {
        MailContext context = context(batchNo);
        return view(context, mailDao.find(context.snapshot().batchNo()).orElse(null));
    }

    public ReplayDailyReportMailView send(ReplayDailyReportMailSendRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("邮件请求不能为空");
        }
        MailContext defaults = context(request.batchNo());
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
        ReplayDailyReportSnapshot currentSnapshot = defaults.snapshot();
        if (currentSnapshot.content() == null || currentSnapshot.content().length == 0) {
            throw new SnapshotNotFoundException();
        }
        MailContext context = new MailContext(currentSnapshot, subject, defaults.sender(), toEmails, ccEmails, body);
        ReplayDailyReportSnapshot snapshot = context.snapshot();
        mailDao.markSending(snapshot.batchNo(), context.subject(), context.body(), context.sender(),
                context.toEmails(), context.ccEmails());
        try {
            mailService.sendTextWithAttachmentSync(context.toEmails(), context.ccEmails(),
                    context.subject(), context.body(), snapshot.fileName(), snapshot.content(), snapshot.contentType());
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null ? "邮件发送失败" : exception.getMessage();
            mailDao.markFailed(snapshot.batchNo(), reason);
            throw new MailSendException(exception);
        }
        mailDao.markSent(snapshot.batchNo());
        return view(context, mailDao.find(snapshot.batchNo()).orElseThrow());
    }

    private MailContext context(String batchNo) {
        Matcher matcher = BATCH_DATE.matcher(batchNo == null ? "" : batchNo.trim());
        if (!matcher.matches()) {
            throw new MalformedBatchException();
        }
        String normalizedBatch = batchNo.trim();
        ReplayDailyReportSnapshot snapshot = dailyDataDao.findReportSnapshot(normalizedBatch)
                .orElseThrow(SnapshotNotFoundException::new);
        List<String> toEmails = normalizeEmails(properties.getTo());
        List<String> ccEmails = normalizeEmails(properties.getCc());
        String sender = mailService.configuredFrom();
        if (sender == null || sender.isBlank()
                || toEmails.stream().anyMatch(email -> !EMAIL.matcher(email).matches())
                || ccEmails.stream().anyMatch(email -> !EMAIL.matcher(email).matches())) {
            throw new ConfigurationException();
        }
        String prefix = properties.getSubjectPrefix() == null ? "" : properties.getSubjectPrefix();
        String body = properties.getBody() == null ? "" : properties.getBody();
        return new MailContext(snapshot, prefix + matcher.group(1), sender.trim(), toEmails, ccEmails, body);
    }

    private ReplayDailyReportMailView view(MailContext context, ReplayDailyReportMailStatus status) {
        return new ReplayDailyReportMailView(context.snapshot().batchNo(), context.subject(),
                context.toEmails(), context.ccEmails(), context.body(), status == null ? "UNSENT" : status.status(),
                status == null ? null : status.sentAt(), status == null ? null : status.failureMessage());
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

    private record MailContext(ReplayDailyReportSnapshot snapshot, String subject, String sender,
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
