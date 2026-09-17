package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayWeeklyReportOption;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOption;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

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
                        SELECT start_batch_no,end_batch_no,file_name,content_type,file_content,file_size,
                               summary_view_json,generated_at
                          FROM dii_replay_weekly_report_snapshot
                         WHERE start_batch_no=? AND end_batch_no=?
                        """, (resultSet, ignored) -> new ReplayWeeklyReportSnapshot(
                resultSet.getString("start_batch_no"), resultSet.getString("end_batch_no"),
                resultSet.getString("file_name"), resultSet.getString("content_type"),
                resultSet.getBytes("file_content"), resultSet.getLong("file_size"),
                resultSet.getString("summary_view_json"),
                resultSet.getTimestamp("generated_at").toLocalDateTime()),
                startBatchNo.trim(), endBatchNo.trim());
        return rows.stream().findFirst();
    }

    public Optional<ReplayWeeklyReportSnapshot> findSnapshotByEndBatchNo(String endBatchNo) {
        if (isBlank(endBatchNo)) {
            return Optional.empty();
        }
        List<ReplayWeeklyReportSnapshot> rows = jdbc.query("""
                        SELECT start_batch_no,end_batch_no,file_name,content_type,file_content,file_size,
                               summary_view_json,generated_at
                          FROM dii_replay_weekly_report_snapshot
                         WHERE end_batch_no=?
                        """, (resultSet, ignored) -> new ReplayWeeklyReportSnapshot(
                resultSet.getString("start_batch_no"), resultSet.getString("end_batch_no"),
                resultSet.getString("file_name"), resultSet.getString("content_type"),
                resultSet.getBytes("file_content"), resultSet.getLong("file_size"),
                resultSet.getString("summary_view_json"),
                resultSet.getTimestamp("generated_at").toLocalDateTime()), endBatchNo.trim());
        return rows.stream().findFirst();
    }

    public void saveSnapshot(ReplayWeeklyReportSnapshot snapshot) {
        jdbc.update("""
                        INSERT INTO dii_replay_weekly_report_snapshot
                               (start_batch_no,end_batch_no,file_name,content_type,file_content,file_size,
                                summary_view_json,generated_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, snapshot.startBatchNo(), snapshot.endBatchNo(), snapshot.fileName(),
                snapshot.contentType(), snapshot.content(), snapshot.fileSize(), snapshot.summaryViewJson(),
                Timestamp.valueOf(snapshot.generatedAt()));
    }

    public int replaceSnapshot(ReplayWeeklyReportSnapshot snapshot) {
        return jdbc.update("""
                        UPDATE dii_replay_weekly_report_snapshot
                           SET file_name=?,content_type=?,file_content=?,file_size=?,summary_view_json=?,generated_at=?
                         WHERE start_batch_no=? AND end_batch_no=?
                        """, snapshot.fileName(), snapshot.contentType(), snapshot.content(), snapshot.fileSize(),
                snapshot.summaryViewJson(), Timestamp.valueOf(snapshot.generatedAt()),
                snapshot.startBatchNo(), snapshot.endBatchNo());
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

    public List<ReplayReportAttachmentOption> findReportAttachmentOptions(String keyword, String family) {
        String normalizedFamily = family == null ? "ALL" : family.trim().toUpperCase();
        if (!List.of("ALL", "RPT", "DZ").contains(normalizedFamily))
            throw new IllegalArgumentException("报告类型错误");
        String like = "%" + (keyword == null ? "" : keyword.trim().toLowerCase()) + "%";
        String familySql = "ALL".equals(normalizedFamily) ? "" : " AND end_batch_no LIKE ?";
        List<Object> args = new ArrayList<>(List.of(like, like, like));
        if (!familySql.isEmpty()) args.add(normalizedFamily + "%");
        return jdbc.query("""
                        SELECT start_batch_no,end_batch_no,file_name,file_size,summary_view_json,generated_at
                          FROM dii_replay_weekly_report_snapshot
                         WHERE file_content IS NOT NULL AND file_size > 0
                           AND (LOWER(start_batch_no) LIKE ? OR LOWER(end_batch_no) LIKE ? OR LOWER(file_name) LIKE ?)
                        """ + familySql + " ORDER BY generated_at DESC,end_batch_no DESC,start_batch_no DESC",
                (resultSet, ignored) -> new ReplayReportAttachmentOption(
                        resultSet.getString("end_batch_no"), family(resultSet.getString("end_batch_no")),
                        resultSet.getString("file_name"), resultSet.getLong("file_size"),
                        resultSet.getTimestamp("generated_at").toLocalDateTime(), ReplayReportPeriod.WEEKLY,
                        resultSet.getString("start_batch_no"), resultSet.getString("end_batch_no"), true,
                        resultSet.getString("summary_view_json") == null ? "LEGACY_EXCEL" : "SNAPSHOT_JSON"),
                args.toArray());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String family(String batchNo) {
        return batchNo != null && batchNo.startsWith("DZ") ? "DZ" : "RPT";
    }
}
