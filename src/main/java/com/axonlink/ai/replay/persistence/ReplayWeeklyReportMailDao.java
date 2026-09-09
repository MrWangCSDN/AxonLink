package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ReplayWeeklyReportMailDao {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ReplayWeeklyReportMailDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    public Optional<ReplayWeeklyReportMailStatus> find(String startBatchNo, String endBatchNo) {
        List<ReplayWeeklyReportMailStatus> rows = jdbc.query("""
                        SELECT start_batch_no,end_batch_no,status,subject,body,sender_email,to_emails,cc_emails,
                               sent_at,failure_message,updated_at
                          FROM dii_replay_weekly_report_mail
                         WHERE start_batch_no=? AND end_batch_no=?
                        """, (resultSet, ignored) -> new ReplayWeeklyReportMailStatus(
                resultSet.getString("start_batch_no"), resultSet.getString("end_batch_no"),
                resultSet.getString("status"), resultSet.getString("subject"), resultSet.getString("body"),
                resultSet.getString("sender_email"), readEmails(resultSet.getString("to_emails")),
                readEmails(resultSet.getString("cc_emails")),
                resultSet.getTimestamp("sent_at") == null ? null : resultSet.getTimestamp("sent_at").toLocalDateTime(),
                resultSet.getString("failure_message"), resultSet.getTimestamp("updated_at").toLocalDateTime()),
                startBatchNo, endBatchNo);
        return rows.stream().findFirst();
    }

    public void markSending(String startBatchNo, String endBatchNo, String subject, String body,
                            String senderEmail, List<String> toEmails, List<String> ccEmails) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                        INSERT INTO dii_replay_weekly_report_mail
                               (start_batch_no,end_batch_no,status,subject,body,sender_email,to_emails,cc_emails,
                                sent_at,failure_message,created_at,updated_at)
                        VALUES (?,?,'SENDING',?,?,?,?,?,NULL,NULL,?,?)
                        ON DUPLICATE KEY UPDATE status='SENDING',subject=VALUES(subject),body=VALUES(body),
                               sender_email=VALUES(sender_email),to_emails=VALUES(to_emails),
                               cc_emails=VALUES(cc_emails),sent_at=NULL,failure_message=NULL,updated_at=VALUES(updated_at)
                        """, startBatchNo, endBatchNo, subject, body, senderEmail,
                writeEmails(toEmails), writeEmails(ccEmails), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public void markSent(String startBatchNo, String endBatchNo) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                        UPDATE dii_replay_weekly_report_mail
                           SET status='SENT',sent_at=?,failure_message=NULL,updated_at=?
                         WHERE start_batch_no=? AND end_batch_no=?
                        """, Timestamp.valueOf(now), Timestamp.valueOf(now), startBatchNo, endBatchNo);
    }

    public void markFailed(String startBatchNo, String endBatchNo, String failureMessage) {
        String message = failureMessage == null || failureMessage.isBlank() ? "邮件发送失败" : failureMessage;
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        jdbc.update("""
                        UPDATE dii_replay_weekly_report_mail
                           SET status='FAILED',sent_at=NULL,failure_message=?,updated_at=?
                         WHERE start_batch_no=? AND end_batch_no=?
                        """, message, Timestamp.valueOf(LocalDateTime.now()), startBatchNo, endBatchNo);
    }

    private String writeEmails(List<String> emails) {
        try {
            return objectMapper.writeValueAsString(emails == null ? List.of() : emails);
        } catch (Exception exception) {
            throw new IllegalStateException("序列化周报邮件收件人失败", exception);
        }
    }

    private List<String> readEmails(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("读取周报邮件收件人失败", exception);
        }
    }
}
