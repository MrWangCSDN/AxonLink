package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayWeeklyReportOption;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class ReplayWeeklyReportDao {

    private final JdbcTemplate jdbc;

    public ReplayWeeklyReportDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    public Optional<ReplayWeeklyReportSnapshot> findSnapshot(String startBatchNo, String endBatchNo) {
        if (isBlank(startBatchNo) || isBlank(endBatchNo)) {
            return Optional.empty();
        }
        List<ReplayWeeklyReportSnapshot> rows = jdbc.query("""
                        SELECT start_batch_no,end_batch_no,file_name,content_type,file_content,file_size,generated_at
                          FROM dii_replay_weekly_report_snapshot
                         WHERE start_batch_no=? AND end_batch_no=?
                        """, (resultSet, ignored) -> new ReplayWeeklyReportSnapshot(
                resultSet.getString("start_batch_no"), resultSet.getString("end_batch_no"),
                resultSet.getString("file_name"), resultSet.getString("content_type"),
                resultSet.getBytes("file_content"), resultSet.getLong("file_size"),
                resultSet.getTimestamp("generated_at").toLocalDateTime()),
                startBatchNo.trim(), endBatchNo.trim());
        return rows.stream().findFirst();
    }

    public Optional<ReplayWeeklyReportSnapshot> findSnapshotByEndBatchNo(String endBatchNo) {
        if (isBlank(endBatchNo)) {
            return Optional.empty();
        }
        List<ReplayWeeklyReportSnapshot> rows = jdbc.query("""
                        SELECT start_batch_no,end_batch_no,file_name,content_type,file_content,file_size,generated_at
                          FROM dii_replay_weekly_report_snapshot
                         WHERE end_batch_no=?
                        """, (resultSet, ignored) -> new ReplayWeeklyReportSnapshot(
                resultSet.getString("start_batch_no"), resultSet.getString("end_batch_no"),
                resultSet.getString("file_name"), resultSet.getString("content_type"),
                resultSet.getBytes("file_content"), resultSet.getLong("file_size"),
                resultSet.getTimestamp("generated_at").toLocalDateTime()), endBatchNo.trim());
        return rows.stream().findFirst();
    }

    public void saveSnapshot(ReplayWeeklyReportSnapshot snapshot) {
        jdbc.update("""
                        INSERT INTO dii_replay_weekly_report_snapshot
                               (start_batch_no,end_batch_no,file_name,content_type,file_content,file_size,generated_at)
                        VALUES (?,?,?,?,?,?,?)
                        """, snapshot.startBatchNo(), snapshot.endBatchNo(), snapshot.fileName(),
                snapshot.contentType(), snapshot.content(), snapshot.fileSize(), Timestamp.valueOf(snapshot.generatedAt()));
    }

    public int replaceSnapshot(ReplayWeeklyReportSnapshot snapshot) {
        return jdbc.update("""
                        UPDATE dii_replay_weekly_report_snapshot
                           SET file_name=?,content_type=?,file_content=?,file_size=?,generated_at=?
                         WHERE start_batch_no=? AND end_batch_no=?
                        """, snapshot.fileName(), snapshot.contentType(), snapshot.content(), snapshot.fileSize(),
                Timestamp.valueOf(snapshot.generatedAt()), snapshot.startBatchNo(), snapshot.endBatchNo());
    }

    public List<ReplayWeeklyReportOption> findGeneratedReports() {
        return jdbc.query("""
                        SELECT snapshot.start_batch_no,snapshot.end_batch_no,snapshot.generated_at,
                               mail.status,mail.sent_at,mail.failure_message
                          FROM dii_replay_weekly_report_snapshot snapshot
                          LEFT JOIN dii_replay_weekly_report_mail mail
                            ON mail.start_batch_no=snapshot.start_batch_no
                           AND mail.end_batch_no=snapshot.end_batch_no
                         ORDER BY snapshot.generated_at DESC,snapshot.end_batch_no DESC,snapshot.start_batch_no DESC
                        """, (resultSet, ignored) -> new ReplayWeeklyReportOption(
                resultSet.getString("start_batch_no"), resultSet.getString("end_batch_no"),
                family(resultSet.getString("end_batch_no")),
                resultSet.getTimestamp("generated_at").toLocalDateTime(),
                resultSet.getString("status") == null ? "UNSENT" : resultSet.getString("status"),
                resultSet.getTimestamp("sent_at") == null ? null : resultSet.getTimestamp("sent_at").toLocalDateTime(),
                resultSet.getString("failure_message")));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String family(String batchNo) {
        return batchNo != null && batchNo.startsWith("DZ") ? "DZ" : "RPT";
    }
}
