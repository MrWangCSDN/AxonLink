package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueMailRecipientStatus;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonRow;
import com.axonlink.ai.replay.persistence.ReplayIssueMailDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.notification.service.MailService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class ReplayIssueMailService {
    private static final String PLATFORM_URL = "http://ccbsai.spdbdev.com:8123";

    private final ReplayIssueMailDao mailDao;
    private final SysUserDao userDao;
    private final ReplayTransactionPersonDao transactionPersonDao;
    private final MailService mailService;
    private final Executor mailExecutor;

    @Autowired
    public ReplayIssueMailService(ReplayIssueMailDao mailDao, SysUserDao userDao,
                                  ReplayTransactionPersonDao transactionPersonDao, MailService mailService,
                                  @Qualifier("diiBatchExecutor") Executor mailExecutor) {
        this.mailDao = mailDao;
        this.userDao = userDao;
        this.transactionPersonDao = transactionPersonDao;
        this.mailService = mailService;
        this.mailExecutor = mailExecutor;
    }

    /** Compatibility constructor for isolated callers that supply the people directory directly. */
    public ReplayIssueMailService(ReplayIssueMailDao mailDao, SysUserDao userDao,
                                  ReplayTransactionPersonDao transactionPersonDao, MailService mailService) {
        this(mailDao, userDao, transactionPersonDao, mailService, Runnable::run);
    }

    /** Compatibility constructor for isolated callers that do not have the people directory. */
    public ReplayIssueMailService(ReplayIssueMailDao mailDao, SysUserDao userDao, MailService mailService) {
        this(mailDao, userDao, null, mailService, Runnable::run);
    }

    public ReplayIssueMailStatus status(ReplayIssueRow issue) {
        return status(issue, new ReplayIssueOperator(null, null));
    }

    public ReplayIssueMailStatus status(ReplayIssueRow issue, ReplayIssueOperator operator) {
        return status(issue, context(issue, operator));
    }

    private ReplayIssueMailStatus status(ReplayIssueRow issue, MailContext context) {
        String sender = mailService.configuredFrom();
        String hash = contentHash(issue, context);
        List<ReplayIssueMailRecipientStatus> statuses = resolveRecipients(issue, context.people()).stream()
                .map(recipient -> recipientStatus(issue, recipient, sender, hash))
                .toList();
        return aggregate(statuses);
    }

    public ReplayIssueMailStatus requestSend(ReplayIssueRow issue) {
        return requestSend(issue, null);
    }

    public ReplayIssueMailStatus requestSend(ReplayIssueRow issue, List<String> selectedEmails) {
        return requestSend(issue, selectedEmails, new ReplayIssueOperator(null, null));
    }

    public ReplayIssueMailStatus requestSend(ReplayIssueRow issue, List<String> selectedEmails,
                                             ReplayIssueOperator operator) {
        MailContext context = context(issue, operator);
        if (issue.issueStatus() == ReplayIssueStatus.PENDING_VERIFICATION
                || issue.issueStatus() == ReplayIssueStatus.NO_ACTION) return status(issue, context);
        String sender = mailService.configuredFrom();
        if (sender == null || sender.isBlank()) throw new IllegalArgumentException("系统发件邮箱未配置");
        List<Recipient> all = resolveRecipients(issue, context.people());
        if (all.isEmpty()) throw new IllegalArgumentException("没有可发送的协同人或开发负责人");
        String hash = contentHash(issue, context);
        var selected = selectedEmails == null ? null : selectedEmails.stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
        List<Recipient> to = new ArrayList<>();
        List<Recipient> cc = new ArrayList<>();
        for (Recipient recipient : all) {
            if (selected != null && !selected.contains(recipient.email().toLowerCase())) continue;
            ReplayIssueMailRecipientStatus current = recipientStatus(issue, recipient, sender, hash);
            if (ReplayIssueMailStatus.SENT.equals(current.status()) || ReplayIssueMailStatus.SENDING.equals(current.status())) continue;
            mailDao.insertPending(issue.id(), issue.issueKey(), recipient.username(), recipient.email(), sender, hash);
            ("科技负责人".equals(recipient.role()) ? cc : to).add(recipient);
        }
        if (to.isEmpty() && cc.isEmpty()) return status(issue, context);
        mailExecutor.execute(() -> sendNow(issue, to, cc, sender, hash, context));
        return status(issue, context);
    }

    private void sendNow(ReplayIssueRow issue, List<Recipient> to, List<Recipient> cc, String sender, String hash,
                         MailContext context) {
        List<String> toEmails = to.stream().map(Recipient::email).toList();
        List<String> ccEmails = cc.stream().map(Recipient::email).toList();
        List<Recipient> all = new ArrayList<>(to);
        all.addAll(cc);
        try {
            mailService.sendTextSync(toEmails, ccEmails, null, subject(issue), body(issue, context));
            all.forEach(recipient -> mailDao.markSent(issue.id(), recipient.email(), sender, hash));
        } catch (Exception exception) {
            String reason = exception.getMessage() == null ? "邮件发送失败" : exception.getMessage();
            all.forEach(recipient -> mailDao.markFailed(issue.id(), recipient.email(), sender, hash, reason));
        }
    }

    String contentHash(ReplayIssueRow issue) {
        return contentHash(issue, new ReplayIssueOperator(null, null));
    }

    String contentHash(ReplayIssueRow issue, ReplayIssueOperator operator) {
        return contentHash(issue, context(issue, operator));
    }

    private String contentHash(ReplayIssueRow issue, MailContext context) {
        String content = String.join("\n", value(issue.issueId()), value(issue.transactionCode()),
                value(issue.issueStatus() == null ? null : issue.issueStatus().displayValue()), value(issue.issueType()),
                value(issue.initialAnalysis()), value(issue.finalSolution()), value(issue.cooperationPersonRealName()),
                value(issue.cooperationPersonUsername()), value(issue.remark()), context.businessSender(),
                context.transactionOwner(), context.technologyOwner());
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("生成邮件内容指纹失败", exception);
        }
    }

    String subject(ReplayIssueRow issue) {
        return "issue_id 是 " + value(issue.issueId()) + " 的问题协同处理";
    }

    String body(ReplayIssueRow issue) {
        return body(issue, new ReplayIssueOperator(null, null));
    }

    String body(ReplayIssueRow issue, ReplayIssueOperator operator) {
        return body(issue, context(issue, operator));
    }

    private String body(ReplayIssueRow issue, MailContext context) {
        return "回放问题协同处理\n\n"
                + "处理平台：" + PLATFORM_URL + "\n\n"
                + "发件人：" + context.businessSender() + "\n"
                + "issue_id：" + value(issue.issueId()) + "\n"
                + "交易码：" + value(issue.transactionCode()) + "\n"
                + "交易名称：" + value(issue.transactionName()) + "\n"
                + "交易负责人：" + context.transactionOwner() + "\n"
                + "科技负责人：" + context.technologyOwner() + "\n"
                + "问题级别：" + value(issue.issueLevel()) + "\n"
                + "问题描述：" + value(issue.issueDescription()) + "\n"
                + "问题状态：" + value(issue.issueStatus() == null ? null : issue.issueStatus().displayValue()) + "\n"
                + "问题类型：" + value(issue.issueType()) + "\n"
                + "初步问题分析：" + value(issue.initialAnalysis()) + "\n"
                + "最终处理方案：" + value(issue.finalSolution()) + "\n"
                + "需协同人：" + value(issue.cooperationPersonRealName()) + "\n"
                + "备注：" + value(issue.remark());
    }

    private List<Recipient> resolveRecipients(ReplayIssueRow issue, ReplayTransactionPersonRow people) {
        Map<String, Recipient> recipients = new LinkedHashMap<>();
        addUser(recipients, collaborator(issue), "协同人");
        if (people != null) {
            split(people.developerUsernames()).forEach(username -> addUser(recipients, userDao.findByUsername(username), "开发负责人", username));
            split(people.bankOwnerEmpNos()).forEach(empNo -> addUser(recipients, userDao.findByEmpNo(empNo), "科技负责人", empNo));
        }
        return List.copyOf(recipients.values());
    }

    private SysUser collaborator(ReplayIssueRow issue) {
        return issue.cooperationPersonUsername() == null || issue.cooperationPersonUsername().isBlank()
                ? null : userDao.findByUsername(issue.cooperationPersonUsername().trim());
    }

    private void addUser(Map<String, Recipient> output, SysUser user, String role) {
        if (user != null) addUser(output, user, role, user.getUsername());
    }

    private void addUser(Map<String, Recipient> output, SysUser user, String role, String fallbackUsername) {
        String username = user == null ? fallbackUsername : user.getUsername();
        if (username == null || username.isBlank()) return;
        String email = user != null && user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail().trim() : username.trim() + "@spdbdev.com";
        output.putIfAbsent(email.toLowerCase(), new Recipient(user == null ? username : value(user.getRealName()), username, email, role));
    }

    private ReplayIssueMailRecipientStatus recipientStatus(ReplayIssueRow issue, Recipient recipient, String sender,
                                                           String hash) {
        if (sender == null || sender.isBlank()) return new ReplayIssueMailRecipientStatus(recipient.displayName(), recipient.username(), recipient.email(), recipient.role(), ReplayIssueMailStatus.UNSENT, null, "系统发件邮箱未配置");
        ReplayIssueMailStatus status = mailDao.findStatusForRecipient(issue.id(), recipient.email(), sender, hash);
        return new ReplayIssueMailRecipientStatus(recipient.displayName(), recipient.username(), recipient.email(), recipient.role(), status.status(), status.sentAt() == null ? null : status.sentAt().toString(), status.failureMessage());
    }

    private ReplayIssueMailStatus aggregate(List<ReplayIssueMailRecipientStatus> statuses) {
        if (statuses.isEmpty()) return new ReplayIssueMailStatus(ReplayIssueMailStatus.UNSENT, null, null, null, statuses);
        ReplayIssueMailRecipientStatus first = statuses.get(0);
        String aggregate = statuses.stream().anyMatch(s -> ReplayIssueMailStatus.FAILED.equals(s.status())) ? ReplayIssueMailStatus.FAILED
                : statuses.stream().anyMatch(s -> ReplayIssueMailStatus.PENDING.equals(s.status())) ? ReplayIssueMailStatus.PENDING
                : statuses.stream().allMatch(s -> ReplayIssueMailStatus.SENT.equals(s.status())) ? ReplayIssueMailStatus.SENT
                : statuses.stream().anyMatch(s -> ReplayIssueMailStatus.SENDING.equals(s.status())) ? ReplayIssueMailStatus.SENDING
                : ReplayIssueMailStatus.UNSENT;
        return new ReplayIssueMailStatus(aggregate, null, first.email(), statuses.stream().filter(s -> s.failureMessage() != null).map(ReplayIssueMailRecipientStatus::failureMessage).findFirst().orElse(null), statuses);
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split("、|,|;"));
    }

    private static String value(String value) { return value == null || value.isBlank() ? "-" : value; }

    private MailContext context(ReplayIssueRow issue, ReplayIssueOperator operator) {
        ReplayTransactionPersonRow people = transactionPersonDao == null
                ? null : transactionPersonDao.findByTransactionCode(issue.transactionCode());
        String transactionOwner = people == null ? "" : blankValue(people.developer());
        String technologyOwner = people == null ? "" : blankValue(people.bankOwner());
        return new MailContext(operatorDisplay(operator), transactionOwner, technologyOwner, people);
    }

    private static String operatorDisplay(ReplayIssueOperator operator) {
        if (operator == null) return "";
        String username = blankValue(operator.username());
        String realName = blankValue(operator.realName());
        if (!realName.isEmpty() && !username.isEmpty()) return realName + "(" + username + ")";
        return !realName.isEmpty() ? realName : username;
    }

    private static String blankValue(String value) { return value == null ? "" : value.trim(); }

    private record Recipient(String displayName, String username, String email, String role) {}
    private record MailContext(String businessSender, String transactionOwner, String technologyOwner,
                               ReplayTransactionPersonRow people) {}
}
