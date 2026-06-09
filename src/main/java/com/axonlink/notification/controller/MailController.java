package com.axonlink.notification.controller;

import com.axonlink.common.R;
import com.axonlink.notification.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用邮件发送 HTTP 接口。
 *
 * <p>使用方式：业务侧 POST 一封邮件的结构体，异步触发 SMTP 发送。
 * <p>不做模板管理（不涉及调休等具体业务模板），由调用方组织 subject / body。
 */
@RestController
@RequestMapping("/api/mail")
public class MailController {

    private static final Logger log = LoggerFactory.getLogger(MailController.class);

    @Autowired
    private MailService mailService;

    /**
     * POST /api/mail/send —— 发送邮件（异步）。
     *
     * <pre>
     * 请求体：
     * {
     *   "to":      ["user1@example.com", "user2@example.com"],
     *   "cc":      ["cc@example.com"],     // 可选
     *   "bcc":     [],                     // 可选
     *   "subject": "测试邮件",
     *   "body":    "邮件正文",
     *   "html":    false                   // true=HTML，false=纯文本（默认）
     * }
     * </pre>
     */
    @PostMapping("/send")
    public R<Map<String, Object>> send(@RequestBody MailRequest req) {
        if (req == null || req.to == null || req.to.isEmpty()) {
            return R.fail("收件人 to 不能为空");
        }
        if (req.subject == null || req.subject.isBlank()) {
            return R.fail("主题 subject 不能为空");
        }
        if (req.body == null) {
            return R.fail("正文 body 不能为空");
        }
        try {
            boolean html = Boolean.TRUE.equals(req.html);
            if (html) {
                mailService.sendHtml(req.to, req.cc, req.bcc, req.subject, req.body);
            } else {
                mailService.sendText(req.to, req.cc, req.bcc, req.subject, req.body);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("to", req.to);
            out.put("html", html);
            out.put("subject", req.subject);
            log.info("[mail] 接受发送请求 subject={} to={} html={}", req.subject, req.to, html);
            return R.ok(out);
        } catch (Exception e) {
            log.error("[mail] 提交发送失败 subject={} : {}", req.subject, e.getMessage(), e);
            return R.fail("提交失败：" + e.getMessage());
        }
    }

    /**
     * POST /api/mail/test —— 测试发送（发到指定邮箱，用于验证 SMTP 配置）。
     */
    @PostMapping("/test")
    public R<Map<String, Object>> test(@RequestBody Map<String, String> body) {
        String to = body == null ? null : body.get("to");
        if (to == null || to.isBlank()) {
            return R.fail("参数 to 不能为空");
        }
        try {
            String subject = "【ccbs-ai 邮件服务自检】";
            String content = "这是一封自检邮件。\n如果你看到这条消息，说明 SMTP 配置正确。\n发送时间：" + new java.util.Date();
            mailService.sendText(java.util.Collections.singletonList(to.trim()), subject, content);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("to", to);
            return R.ok(out);
        } catch (Exception e) {
            return R.fail("测试发送失败：" + e.getMessage());
        }
    }

    /** 请求体 DTO。 */
    public static class MailRequest {
        public List<String> to;
        public List<String> cc;
        public List<String> bcc;
        public String subject;
        public String body;
        public Boolean html;
    }
}
