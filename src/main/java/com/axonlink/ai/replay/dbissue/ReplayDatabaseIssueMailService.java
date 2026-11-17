package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.dto.ReplayIssueMailRecipientStatus;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.notification.service.MailService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.axonlink.ai.replay.dbissue.ReplayDatabaseIssueDao.text;

@Service
public class ReplayDatabaseIssueMailService {
    private static final String PLATFORM_URL = "http://ccbsai.spdbdev.com:8123";
    private static final Set<String> SKIP_SEND_STATUSES = Set.of("修复待验证", "无需处理");
    private final ReplayDatabaseIssueDao issues;
    private final SysUserDao users;
    private final ReplayDatabaseIssueMailDao mailDao;
    private final MailService mailService;
    private final Executor mailExecutor;

    public ReplayDatabaseIssueMailService(ReplayDatabaseIssueDao issues, SysUserDao users,
                                          ReplayDatabaseIssueMailDao mailDao, MailService mailService,
                                          @Qualifier("diiBatchExecutor") Executor mailExecutor) {
        this.issues = issues;
        this.users = users;
        this.mailDao = mailDao;
        this.mailService = mailService;
        this.mailExecutor = mailExecutor;
    }

    public ReplayIssueMailStatus status(long id, ReplayIssueOperator operator) {
        return status(context(id, operator));
    }

    public ReplayIssueMailStatus requestSend(long id, List<String> selectedEmails,
                                             ReplayIssueOperator operator) {
        MailContext context = context(id, operator);
        if (SKIP_SEND_STATUSES.contains(text(context.issue().get("issueStatus")))) return status(context);
        if (context.sender().isBlank()) throw new IllegalArgumentException("系统发件邮箱未配置");
        if (context.recipients().isEmpty()) throw new IllegalArgumentException("没有可发送的协同人或开发负责人");
        Set<String> selected = selectedEmails == null ? null : selectedEmails.stream()
                .filter(email -> email != null && !email.isBlank()).map(ReplayDatabaseIssueMailService::emailKey)
                .collect(Collectors.toSet());
        List<Recipient> recipients = mailDao.inTransaction(() -> {
            List<Recipient> claimed = new ArrayList<>();
            for (Recipient recipient : context.recipients()) {
                if (selected != null && !selected.contains(emailKey(recipient.email()))) continue;
                if (mailDao.claim(id, text(context.issue().get("issueId")), recipient.username(),
                        recipient.email(), context.sender(), context.hash())) {
                    claimed.add(recipient);
                }
            }
            return List.copyOf(claimed);
        });
        if (recipients.isEmpty()) return status(context);
        try {
            mailExecutor.execute(() -> sendNow(context, recipients));
        } catch (RuntimeException exception) {
            markFailed(context, recipients, exception);
        }
        return status(context);
    }

    private MailContext context(long id, ReplayIssueOperator operator) {
        if (operator == null || text(operator.username()).isBlank()) throw new SecurityException("请先登录");
        Map<String, Object> current = issues.find(id);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据库回放问题不存在");
        Map<String, Object> issue = new LinkedHashMap<>(current);
        var registrations = issues.technologyOwners(List.of(ReplayDatabaseIssueDao.TableIdentity.of(issue)));
        var personnel = registrations.size() == 1 ? registrations.get(0) : null;
        List<Recipient> recipients = resolveRecipients(issue, personnel);
        String subject = "issue_id 是 " + value(issue.get("issueId")) + " 的数据库问题协同处理";
        String body = body(issue, personnel, operator);
        return new MailContext(id, issue, text(mailService.configuredFrom()).trim(), subject, body,
                contentHash(subject, body), recipients);
    }

    private List<Recipient> resolveRecipients(Map<String, Object> issue,
                                              ReplayDatabaseIssueDao.TechnologyOwnerRegistration personnel) {
        Map<String, Recipient> recipients = new LinkedHashMap<>();
        String collaborator = text(issue.get("cooperationPersonUsername")).trim();
        if (!collaborator.isEmpty()) {
            var user = users.findByUsername(collaborator);
            if (user != null) addRecipient(recipients, user, collaborator, "协同人");
        }
        if (personnel != null) {
            for (String username : identities(personnel.developerUsernames())) {
                addRecipient(recipients, users.findByUsername(username), username, "开发负责人");
            }
            List<String> identities = identities(personnel.identity());
            List<SysUser> matches = users.findByUsernamesOrEmpNos(identities);
            for (String identity : identities) {
                Map<String, SysUser> candidates = new LinkedHashMap<>();
                for (SysUser user : matches) {
                    if (identity.equals(text(user.getUsername()).trim()) || identity.equals(text(user.getEmpNo()).trim())) {
                        candidates.put(text(user.getUsername()).trim(), user);
                    }
                }
                if (candidates.size() == 1) {
                    SysUser user = candidates.values().iterator().next();
                    addRecipient(recipients, user, user.getUsername(), "科技负责人");
                }
            }
        }
        return List.copyOf(recipients.values());
    }

    private void addRecipient(Map<String, Recipient> recipients, SysUser user, String fallbackUsername, String role) {
        String username = text(user == null ? fallbackUsername : user.getUsername()).trim();
        if (username.isEmpty()) return;
        String email = user == null ? "" : text(user.getEmail()).trim();
        if (email.isEmpty()) email = username + "@spdbdev.com";
        String name = user == null ? username : value(user.getRealName());
        recipients.putIfAbsent(emailKey(email), new Recipient(name, username, email, role));
    }

    private String body(Map<String, Object> issue, ReplayDatabaseIssueDao.TechnologyOwnerRegistration personnel,
                        ReplayIssueOperator operator) {
        String owner = personnel == null ? "" : text(personnel.owner());
        String technologyOwner = personnel == null ? "" : text(personnel.name());
        String tableComment = personnel == null ? text(issue.get("tableComment")) : text(personnel.tableComment());
        String domain = personnel == null ? "" : text(personnel.domain());
        return "回放数据库问题协同处理\n\n"
                + "处理平台：" + PLATFORM_URL + "\n\n"
                + "发件人：" + operatorDisplay(operator) + "\n"
                + "issue_id：" + value(issue.get("issueId")) + "\n"
                + "表英文名：" + value(issue.get("tableName")) + "\n"
                + "表中文名：" + value(tableComment) + "\n"
                + "领域：" + domain + "\n"
                + "开发负责人：" + owner + "\n"
                + "科技负责人：" + technologyOwner + "\n"
                + "问题级别：" + value(issue.get("issueLevel")) + "\n"
                + "字段英文名：" + value(issue.get("fieldName")) + "\n"
                + "批次：" + value(issue.get("batchNo")) + "\n"
                + "528总量：" + value(issue.get("total528")) + "\n"
                + "ccbs总量：" + value(issue.get("totalCcbs")) + "\n"
                + "仅528存在数量：" + value(issue.get("only528Count")) + "\n"
                + "仅ccbs存在数量：" + value(issue.get("onlyCcbsCount")) + "\n"
                + "差异笔数：" + value(issue.get("differenceCount")) + "\n"
                + "问题状态：" + value(issue.get("issueStatus")) + "\n"
                + "问题类型：" + value(issue.get("issueType")) + "\n"
                + "初步问题分析：" + value(issue.get("initialAnalysis")) + "\n"
                + "最终处理方案：" + value(issue.get("finalSolution")) + "\n"
                + "需协同人：" + value(issue.get("cooperationPerson")) + "\n"
                + "备注：" + value(issue.get("remark"));
    }

    private ReplayIssueMailStatus status(MailContext context) {
        List<ReplayIssueMailRecipientStatus> recipients = context.recipients().stream()
                .map(recipient -> recipientStatus(context, recipient)).toList();
        if (recipients.isEmpty()) return new ReplayIssueMailStatus(ReplayIssueMailStatus.UNSENT, null, null, null, recipients);
        String aggregate;
        if (recipients.stream().anyMatch(recipient -> ReplayIssueMailStatus.FAILED.equals(recipient.status()))) {
            aggregate = ReplayIssueMailStatus.FAILED;
        } else if (recipients.stream().anyMatch(recipient -> ReplayIssueMailStatus.PENDING.equals(recipient.status()))) {
            aggregate = ReplayIssueMailStatus.PENDING;
        } else if (recipients.stream().allMatch(recipient -> ReplayIssueMailStatus.SENT.equals(recipient.status()))) {
            aggregate = ReplayIssueMailStatus.SENT;
        } else if (recipients.stream().anyMatch(recipient -> ReplayIssueMailStatus.SENDING.equals(recipient.status()))) {
            aggregate = ReplayIssueMailStatus.SENDING;
        } else {
            aggregate = ReplayIssueMailStatus.UNSENT;
        }
        String failure = recipients.stream().map(ReplayIssueMailRecipientStatus::failureMessage)
                .filter(message -> message != null).findFirst().orElse(null);
        return new ReplayIssueMailStatus(aggregate, null, recipients.get(0).email(), failure, recipients);
    }

    private ReplayIssueMailRecipientStatus recipientStatus(MailContext context, Recipient recipient) {
        if (context.sender().isBlank()) {
            return new ReplayIssueMailRecipientStatus(recipient.displayName(), recipient.username(), recipient.email(),
                    recipient.role(), ReplayIssueMailStatus.UNSENT, null, "系统发件邮箱未配置");
        }
        var status = mailDao.findStatus(context.id(), recipient.email(), context.sender(), context.hash());
        return new ReplayIssueMailRecipientStatus(recipient.displayName(), recipient.username(), recipient.email(),
                recipient.role(), status.status(), status.sentAt() == null ? null : status.sentAt().toString(), status.failureMessage());
    }

    private void sendNow(MailContext context, List<Recipient> recipients) {
        List<String> to = new ArrayList<>();
        List<String> cc = new ArrayList<>();
        for (Recipient recipient : recipients) {
            if ("科技负责人".equals(recipient.role())) cc.add(recipient.email());
            else to.add(recipient.email());
        }
        if (to.isEmpty()) to.add(cc.remove(0));
        try {
            mailService.sendTextSync(to, cc, null, context.subject(), context.body());
            for (Recipient recipient : recipients) {
                mailDao.markSent(context.id(), recipient.email(), context.sender(), context.hash());
            }
        } catch (Exception exception) {
            markFailed(context, recipients, exception);
        }
    }

    private void markFailed(MailContext context, List<Recipient> recipients, Exception exception) {
        String reason = exception.getMessage() == null ? "邮件发送失败" : exception.getMessage();
        for (Recipient recipient : recipients) {
            mailDao.markFailed(context.id(), recipient.email(), context.sender(), context.hash(), reason);
        }
    }

    private static List<String> identities(String value) {
        return Arrays.stream(text(value).split("[、,，;；\\s]+"))
                .map(String::trim).filter(identity -> !identity.isEmpty()).distinct().toList();
    }

    private static String operatorDisplay(ReplayIssueOperator operator) {
        String username = text(operator.username()).trim();
        String name = text(operator.realName()).trim();
        return name.isEmpty() ? username : name + "(" + username + ")";
    }

    private static String emailKey(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String value(Object value) {
        String text = text(value);
        return text.isBlank() ? "-" : text;
    }

    private static String contentHash(String subject, String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((subject + "\n" + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("生成邮件内容指纹失败", exception);
        }
    }

    private record Recipient(String displayName, String username, String email, String role) {}

    private record MailContext(long id, Map<String, Object> issue, String sender, String subject,
                               String body, String hash, List<Recipient> recipients) {}
}
