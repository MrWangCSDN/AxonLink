package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayDailyReportMailStatus;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ReplayDailyReportMailDao {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<ReplayMailAttachmentMetadata>> ATTACHMENT_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReplayDailyReportMailDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public Optional<ReplayDailyReportMailStatus> find(String batchNo) {
        List<ReplayDailyReportMailStatus> rows = jdbc.query("""
                        SELECT batch_no,status,subject,body,sender_email,to_emails,cc_emails,attachment_manifest,
                               sent_at,failure_message,updated_at
                          FROM dii_replay_daily_report_mail
                         WHERE batch_no=?
                        """, (resultSet, ignored) -> new ReplayDailyReportMailStatus(
                resultSet.getString("batch_no"), resultSet.getString("status"),
                resultSet.getString("subject"), resultSet.getString("body"),
                resultSet.getString("sender_email"), readEmails(resultSet.getString("to_emails")),
                readEmails(resultSet.getString("cc_emails")),
                readAttachments(resultSet.getString("attachment_manifest")),
                resultSet.getTimestamp("sent_at") == null ? null : resultSet.getTimestamp("sent_at").toLocalDateTime(),
                resultSet.getString("failure_message"), resultSet.getTimestamp("updated_at").toLocalDateTime()), batchNo);
        return rows.stream().findFirst();
    }

    public void markSending(String batchNo, String subject, String body, String senderEmail,
                            List<String> toEmails, List<String> ccEmails) {
        markSending(batchNo, subject, body, senderEmail, toEmails, ccEmails, List.of());
    }

    public void markSending(String batchNo, String subject, String body, String senderEmail,
                            List<String> toEmails, List<String> ccEmails,
                            List<ReplayMailAttachmentMetadata> attachments) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                        INSERT INTO dii_replay_daily_report_mail
                               (batch_no,status,subject,body,sender_email,to_emails,cc_emails,attachment_manifest,
                                sent_at,failure_message,created_at,updated_at)
                        VALUES (?,'SENDING',?,?,?,?,?,?,NULL,NULL,?,?)
                        ON DUPLICATE KEY UPDATE status='SENDING',subject=VALUES(subject),body=VALUES(body),
                               sender_email=VALUES(sender_email),to_emails=VALUES(to_emails),
                               cc_emails=VALUES(cc_emails),attachment_manifest=VALUES(attachment_manifest),
                               sent_at=NULL,failure_message=NULL,updated_at=VALUES(updated_at)
                        """, batchNo, subject, body, senderEmail, writeEmails(toEmails), writeEmails(ccEmails),
                writeAttachments(attachments),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public void markSent(String batchNo) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE dii_replay_daily_report_mail SET status='SENT',sent_at=?,failure_message=NULL,updated_at=? WHERE batch_no=?",
                Timestamp.valueOf(now), Timestamp.valueOf(now), batchNo);
    }

    public void markFailed(String batchNo, String failureMessage) {
        String message = failureMessage == null || failureMessage.isBlank() ? "邮件发送失败" : failureMessage;
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        jdbc.update("UPDATE dii_replay_daily_report_mail SET status='FAILED',sent_at=NULL,failure_message=?,updated_at=? WHERE batch_no=?",
                message, Timestamp.valueOf(LocalDateTime.now()), batchNo);
    }

    public int delete(String batchNo) {
        return jdbc.update("DELETE FROM dii_replay_daily_report_mail WHERE batch_no=?", batchNo);
    }

    private String writeEmails(List<String> emails) {
        try {
            return objectMapper.writeValueAsString(emails == null ? List.of() : emails);
        } catch (Exception exception) {
            throw new IllegalStateException("序列化日报邮件收件人失败", exception);
        }
    }

    private List<String> readEmails(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("读取日报邮件收件人失败", exception);
        }
    }

    private String writeAttachments(List<ReplayMailAttachmentMetadata> attachments) {
        try {
            return objectMapper.writeValueAsString(attachments == null ? List.of() : attachments);
        } catch (Exception exception) {
            throw new IllegalStateException("序列化日报邮件附件失败", exception);
        }
    }

    private List<ReplayMailAttachmentMetadata> readAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, ATTACHMENT_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("读取日报邮件附件失败", exception);
        }
    }
}
