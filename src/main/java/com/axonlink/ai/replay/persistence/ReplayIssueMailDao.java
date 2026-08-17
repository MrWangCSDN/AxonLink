package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ReplayIssueMailDao {
    private final JdbcTemplate jdbc;

    public ReplayIssueMailDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReplayIssueMailStatus findStatus(long issueId, String issueKey, String recipientEmail,
                                             String senderEmail, String contentHash) {
        List<ReplayIssueMailStatus> current = jdbc.query("SELECT status,sent_at,recipient_email,failure_message "
                        + "FROM dii_replay_issue_mail WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND content_hash=? LIMIT 1",
                (rs, rowNum) -> new ReplayIssueMailStatus(rs.getString("status"),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toLocalDateTime(),
                        rs.getString("recipient_email"), rs.getString("failure_message")),
                issueId, recipientEmail, senderEmail, contentHash);
        if (!current.isEmpty()) return current.get(0);
        Integer prior = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_mail WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND status='SENT'",
                Integer.class, issueId, recipientEmail, senderEmail);
        return new ReplayIssueMailStatus(prior != null && prior > 0 ? ReplayIssueMailStatus.PENDING : ReplayIssueMailStatus.UNSENT,
                null, recipientEmail, null);
    }

    public ReplayIssueMailStatus findStatusForRecipient(long issueId, String recipientEmail,
                                                        String senderEmail, String contentHash) {
        return findStatus(issueId, "", recipientEmail, senderEmail, contentHash);
    }

    public void insertPending(long issueId, String issueKey, String recipientUsername, String recipientEmail,
                              String senderEmail, String contentHash) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO dii_replay_issue_mail (replay_issue_id,issue_key,recipient_username,recipient_email,sender_email,content_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?, ?,?) ON DUPLICATE KEY UPDATE recipient_username=VALUES(recipient_username),status='SENDING',failure_message=NULL,updated_at=VALUES(updated_at)",
                issueId, issueKey, recipientUsername, recipientEmail, senderEmail, contentHash, ReplayIssueMailStatus.SENDING,
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public void markSent(long issueId, String recipientEmail, String senderEmail, String contentHash) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE dii_replay_issue_mail SET status='SENT',sent_at=?,failure_message=NULL,updated_at=? WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND content_hash=?",
                Timestamp.valueOf(now), Timestamp.valueOf(now), issueId, recipientEmail, senderEmail, contentHash);
    }

    public void markFailed(long issueId, String recipientEmail, String senderEmail, String contentHash, String failureMessage) {
        jdbc.update("UPDATE dii_replay_issue_mail SET status='FAILED',failure_message=?,updated_at=? WHERE replay_issue_id=? AND recipient_email=? AND sender_email=? AND content_hash=?",
                failureMessage == null ? "邮件发送失败" : failureMessage.substring(0, Math.min(1000, failureMessage.length())), Timestamp.valueOf(LocalDateTime.now()),
                issueId, recipientEmail, senderEmail, contentHash);
    }
}
