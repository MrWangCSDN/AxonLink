package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.function.Supplier;

@Repository
public class ReplayDatabaseIssueMailDao {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ReplayDatabaseIssueMailDao(@Qualifier("diiResultJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    public ReplayIssueMailStatus findStatus(long issueId, String recipientEmail,
                                           String senderEmail, String contentHash) {
        String recipient = emailKey(recipientEmail);
        String sender = emailKey(senderEmail);
        var current = jdbc.query("SELECT status,sent_at,recipient_email,failure_message "
                        + "FROM dii_replay_database_issue_mail "
                        + "WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND content_hash=?",
                (row, index) -> new ReplayIssueMailStatus(row.getString("status"),
                        row.getTimestamp("sent_at") == null ? null : row.getTimestamp("sent_at").toLocalDateTime(),
                        row.getString("recipient_email"), row.getString("failure_message")),
                issueId, recipient, sender, contentHash);
        if (!current.isEmpty()) return current.get(0);
        var prior = jdbc.query("SELECT sent_at FROM dii_replay_database_issue_mail "
                        + "WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND status='SENT' "
                        + "ORDER BY sent_at DESC,id DESC LIMIT 1",
                (row, index) -> new ReplayIssueMailStatus(ReplayIssueMailStatus.PENDING,
                        row.getTimestamp("sent_at") == null ? null : row.getTimestamp("sent_at").toLocalDateTime(),
                        recipient, null), issueId, recipient, sender);
        return prior.isEmpty() ? new ReplayIssueMailStatus(ReplayIssueMailStatus.UNSENT, null, recipient, null)
                : prior.get(0);
    }

    public boolean claim(long issueId, String issueKey, String recipientUsername, String recipientEmail,
                         String senderEmail, String contentHash) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String recipient = emailKey(recipientEmail);
        String sender = emailKey(senderEmail);
        try {
            jdbc.update("INSERT INTO dii_replay_database_issue_mail "
                            + "(replay_issue_id,issue_key,recipient_username,recipient_email,sender_email,content_hash,"
                            + "status,created_at,updated_at) VALUES (?,?,?,?,?,?,'SENDING',?,?)",
                    issueId, issueKey, recipientUsername, recipient, sender, contentHash, now, now);
            return true;
        } catch (DuplicateKeyException exception) {
            return jdbc.update("UPDATE dii_replay_database_issue_mail SET recipient_username=?,"
                            + "status='SENDING',sent_at=NULL,failure_message=NULL,updated_at=? "
                            + "WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND content_hash=? "
                            + "AND status='FAILED'",
                    recipientUsername, now, issueId, recipient, sender, contentHash) == 1;
        }
    }

    public void markSent(long issueId, String recipientEmail, String senderEmail, String contentHash) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("UPDATE dii_replay_database_issue_mail SET status='SENT',sent_at=?,failure_message=NULL,updated_at=? "
                        + "WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND content_hash=? AND status='SENDING'",
                now, now, issueId, emailKey(recipientEmail), emailKey(senderEmail), contentHash);
    }

    public void markFailed(long issueId, String recipientEmail, String senderEmail, String contentHash,
                           String failureMessage) {
        String message = failureMessage == null ? "邮件发送失败" : failureMessage;
        jdbc.update("UPDATE dii_replay_database_issue_mail SET status='FAILED',failure_message=?,updated_at=? "
                        + "WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND content_hash=? AND status='SENDING'",
                message.substring(0, Math.min(1000, message.length())), Timestamp.valueOf(LocalDateTime.now()),
                issueId, emailKey(recipientEmail), emailKey(senderEmail), contentHash);
    }

    private static String emailKey(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
