package com.axonlink.notification.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailServiceTest {

    @Test
    void sendsAttachmentsInCallerOrderWithNamesAndContentTypes() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        MailService service = new MailService();
        ReflectionTestUtils.setField(service, "mailSender", sender);
        ReflectionTestUtils.setField(service, "fromAddress", "sender@example.com");
        ReflectionTestUtils.setField(service, "fallbackFrom", "");

        service.sendTextWithAttachmentsSync(
                List.of("to@example.com"), List.of("cc@example.com"), "日报", "正文",
                List.of(
                        new MailAttachment("当前日报.xlsx", new byte[]{1, 2},
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                        new MailAttachment("补充.xls", new byte[]{3, 4}, "application/vnd.ms-excel")));

        verify(sender).send(message);
        message.saveChanges();
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        assertEquals(3, multipart.getCount());
        MimeBodyPart first = (MimeBodyPart) multipart.getBodyPart(1);
        MimeBodyPart second = (MimeBodyPart) multipart.getBodyPart(2);
        assertEquals("当前日报.xlsx", first.getFileName());
        assertEquals("补充.xls", second.getFileName());
        assertTrue(first.getContentType().startsWith(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertTrue(second.getContentType().startsWith("application/vnd.ms-excel"));
    }
}
