package com.axonlink.notification.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * 通用邮件发送服务。仿 NotificationService 风格：JavaMailSender + @Async。
 *
 * <p>能力：纯文本 / HTML、抄送（cc）、密送（bcc）、多收件人。
 * <p>模板不由本服务管理（不涉及调休等具体业务），由调用方在 subject / body 里自行拼接或由前端传入。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    @Autowired
    private JavaMailSender mailSender;

    /** 发件人地址：优先用 yml 的 {@code axon-link.mail.from}，缺省回退到 {@code spring.mail.username}。 */
    @Value("${axon-link.mail.from:#{null}}")
    private String fromAddress;

    @Value("${spring.mail.username:}")
    private String fallbackFrom;

    /**
     * 异步发送纯文本邮件。
     *
     * @param to      收件人列表
     * @param subject 主题
     * @param body    正文（纯文本）
     */
    @Async("diiBatchExecutor")
    public void sendText(List<String> to, String subject, String body) {
        sendText(to, null, null, subject, body);
    }

    @Async("diiBatchExecutor")
    public void sendText(List<String> to, List<String> cc, List<String> bcc,
                        String subject, String body) {
        if (to == null || to.isEmpty()) {
            log.warn("[mail] 跳过发送：to 为空 subject={}", subject);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(resolveFrom());
            msg.setTo(to.toArray(new String[0]));
            if (cc != null && !cc.isEmpty()) msg.setCc(cc.toArray(new String[0]));
            if (bcc != null && !bcc.isEmpty()) msg.setBcc(bcc.toArray(new String[0]));
            msg.setSubject(subject);
            msg.setText(body == null ? "" : body);
            msg.setSentDate(new Date());
            mailSender.send(msg);
            log.info("[mail] 文本邮件已发送 subject={} to={} cc={} bcc={}",
                    subject, to, cc, bcc);
        } catch (Exception e) {
            // 异常仅记日志，不向调用方抛（@Async 调用方早已返回）
            log.error("[mail] 文本邮件发送失败 subject={} to={} : {}",
                    subject, to, e.getMessage(), e);
        }
    }

    /**
     * 异步发送 HTML 邮件。
     */
    @Async("diiBatchExecutor")
    public void sendHtml(List<String> to, List<String> cc, List<String> bcc,
                        String subject, String htmlBody) {
        if (to == null || to.isEmpty()) {
            log.warn("[mail] 跳过发送：to 为空 subject={}", subject);
            return;
        }
        try {
            MimeMessage mm = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mm, true, StandardCharsets.UTF_8.name());
            helper.setFrom(resolveFrom());
            helper.setTo(to.toArray(new String[0]));
            if (cc != null && !cc.isEmpty()) helper.setCc(cc.toArray(new String[0]));
            if (bcc != null && !bcc.isEmpty()) helper.setBcc(bcc.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlBody == null ? "" : htmlBody, true);
            helper.setSentDate(new Date());
            mailSender.send(mm);
            log.info("[mail] HTML 邮件已发送 subject={} to={}", subject, to);
        } catch (MessagingException e) {
            log.error("[mail] HTML 邮件发送失败 subject={} to={} : {}",
                    subject, to, e.getMessage(), e);
        } catch (Exception e) {
            log.error("[mail] HTML 邮件发送异常 subject={} to={} : {}",
                    subject, to, e.getMessage(), e);
        }
    }

    /**
     * 同步发送纯文本（用于测试 / 立即获取结果的场景）。
     * <p>调用方需自行捕获异常。
     */
    public void sendTextSync(List<String> to, String subject, String body) {
        sendText(to, null, null, subject, body);
    }

    // ── 业务便捷方法：白名单邮件（自动渲染模板） ─────────────────────────

    /**
     * 发送白名单申请相关邮件：传入模板 + 变量 map，自动渲染后异步发送。
     * <p>失败仅 log.error，不向调用方抛（@Async 调用方早已返回）。
     */
    @Async("diiBatchExecutor")
    public void sendWhitelistMail(List<String> to, String subjectTpl, String bodyTpl,
                                 java.util.Map<String, ?> vars) {
        if (to == null || to.isEmpty()) {
            log.warn("[mail-whitelist] 跳过发送：to 为空");
            return;
        }
        try {
            String subject = WhitelistMailTemplate.render(subjectTpl, vars);
            String body    = WhitelistMailTemplate.render(bodyTpl, vars);
            sendText(to, null, null, subject, body);
            log.info("[mail-whitelist] 已发送 subject={} to={}", subject, to);
        } catch (Exception e) {
            log.error("[mail-whitelist] 发送失败 subject={} to={} : {}",
                    subjectTpl, to, e.getMessage(), e);
        }
    }

    private String resolveFrom() {
        if (fromAddress != null && !fromAddress.isBlank()) return fromAddress.trim();
        return fallbackFrom;
    }
}
