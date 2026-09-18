package com.axonlink.ai.replay.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOption;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOptionPage;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ReplayDailyDataDao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ReplayDailyDataDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    public void replaceBatch(ReplayDailyWorkbookData data, LocalDateTime importedAt) {
        if (data == null || data.batchNo() == null || data.batchNo().isBlank()) {
            throw new IllegalArgumentException("日报标准批次不能为空");
        }
        String batch = data.batchNo().trim();
        deleteBatch(batch);
        insertSummaries(data.summaries(), importedAt);
        insertComparisons(data.comparisons(), importedAt);
        insertCoverageSummaries(data.coverageSummaries(), importedAt);
        insertCoverageDetails(data.coverageDetails(), importedAt);
    }

    private void deleteBatch(String batch) {
        jdbc.update("DELETE FROM dii_replay_daily_summary WHERE batch_no=?", batch);
        jdbc.update("DELETE FROM dii_replay_interface_comparison WHERE batch_no=?", batch);
        jdbc.update("DELETE FROM dii_replay_coverage_summary WHERE batch_no=?", batch);
        jdbc.update("DELETE FROM dii_replay_coverage_detail WHERE batch_no=?", batch);
    }

    private void insertSummaries(List<ReplayDailySummaryRow> rows, LocalDateTime importedAt) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dii_replay_daily_summary (batch_no,domain,covered_interface_count,"
                        + "sent_transaction_count,both_fail_same_code,both_success,code_ignored,match_pass_rate,"
                        + "issue_total,source_row,raw_json,imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                rows, rows.size(), (statement, row) -> {
                    statement.setString(1, row.batchNo());
                    statement.setString(2, row.domain());
                    statement.setObject(3, row.coveredInterfaceCount());
                    statement.setObject(4, row.sentTransactionCount());
                    statement.setObject(5, row.bothFailSameCode());
                    statement.setObject(6, row.bothSuccess());
                    statement.setObject(7, row.codeIgnored());
                    statement.setBigDecimal(8, row.matchPassRate());
                    statement.setObject(9, row.issueTotal());
                    statement.setInt(10, row.sourceRow());
                    statement.setString(11, row.rawJson());
                    statement.setTimestamp(12, Timestamp.valueOf(importedAt));
                });
    }

    private void insertComparisons(List<ReplayInterfaceComparisonRow> rows, LocalDateTime importedAt) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dii_replay_interface_comparison (batch_no,row_type,transaction_code,s_code,"
                        + "transaction_description,developer,bank_owner,domain,sent_transaction_count,"
                        + "c528_success_ccbs_fail,c528_fail_ccbs_success,both_fail_same_code,both_fail_diff_code,"
                        + "both_success,code_ignored,transaction_success_rate,match_pass_rate,c528_avg_duration,"
                        + "ccbs_avg_duration,source_row,raw_json,imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                rows, rows.size(), (statement, row) -> {
                    statement.setString(1, row.batchNo());
                    statement.setString(2, row.rowType().name());
                    statement.setString(3, row.transactionCode());
                    statement.setString(4, row.sCode());
                    statement.setString(5, row.transactionDescription());
                    statement.setString(6, row.developer());
                    statement.setString(7, row.bankOwner());
                    statement.setString(8, row.domain());
                    statement.setObject(9, row.sentTransactionCount());
                    statement.setObject(10, row.c528SuccessCcbsFail());
                    statement.setObject(11, row.c528FailCcbsSuccess());
                    statement.setObject(12, row.bothFailSameCode());
                    statement.setObject(13, row.bothFailDiffCode());
                    statement.setObject(14, row.bothSuccess());
                    statement.setObject(15, row.codeIgnored());
                    statement.setBigDecimal(16, row.transactionSuccessRate());
                    statement.setBigDecimal(17, row.matchPassRate());
                    statement.setBigDecimal(18, row.c528AvgDuration());
                    statement.setBigDecimal(19, row.ccbsAvgDuration());
                    statement.setInt(20, row.sourceRow());
                    statement.setString(21, row.rawJson());
                    statement.setTimestamp(22, Timestamp.valueOf(importedAt));
                });
    }

    private void insertCoverageSummaries(List<ReplayCoverageSummaryRow> rows, LocalDateTime importedAt) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dii_replay_coverage_summary (batch_no,row_type,business_domain,"
                        + "full_transaction_count,sent_count,unsent_count,excluded_count,recent_transaction_count,"
                        + "pending_analysis_count,coverage_rate,source_row,raw_json,imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                rows, rows.size(), (statement, row) -> {
                    statement.setString(1, row.batchNo());
                    statement.setString(2, row.rowType().name());
                    statement.setString(3, row.businessDomain());
                    statement.setObject(4, row.fullTransactionCount());
                    statement.setObject(5, row.sentCount());
                    statement.setObject(6, row.unsentCount());
                    statement.setObject(7, row.excludedCount());
                    statement.setObject(8, row.recentTransactionCount());
                    statement.setObject(9, row.pendingAnalysisCount());
                    statement.setBigDecimal(10, row.coverageRate());
                    statement.setInt(11, row.sourceRow());
                    statement.setString(12, row.rawJson());
                    statement.setTimestamp(13, Timestamp.valueOf(importedAt));
                });
    }

    private void insertCoverageDetails(List<ReplayCoverageDetailRow> rows, LocalDateTime importedAt) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dii_replay_coverage_detail (batch_no,transaction_code,transaction_description,"
                        + "business_domain,s_code,related_code,replay_required,latest_transaction_date,"
                        + "sent_transaction_count,coverage_status,unsent_reason,developer,bank_owner,source_row,raw_json,"
                        + "imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                rows, rows.size(), (statement, row) -> {
                    statement.setString(1, row.batchNo());
                    statement.setString(2, row.transactionCode());
                    statement.setString(3, row.transactionDescription());
                    statement.setString(4, row.businessDomain());
                    statement.setString(5, row.sCode());
                    statement.setString(6, row.relatedCode());
                    statement.setString(7, row.replayRequired());
                    statement.setDate(8, row.latestTransactionDate() == null ? null : Date.valueOf(row.latestTransactionDate()));
                    statement.setObject(9, row.sentTransactionCount());
                    statement.setString(10, row.coverageStatus());
                    statement.setString(11, row.unsentReason());
                    statement.setString(12, row.developer());
                    statement.setString(13, row.bankOwner());
                    statement.setInt(14, row.sourceRow());
                    statement.setString(15, row.rawJson());
                    statement.setTimestamp(16, Timestamp.valueOf(importedAt));
                });
    }

    public List<ReplayDailySummaryRow> findSummaries(String batch) {
        return jdbc.query("SELECT * FROM dii_replay_daily_summary WHERE batch_no=? ORDER BY source_row,id",
                (resultSet, ignored) -> new ReplayDailySummaryRow(resultSet.getString("batch_no"),
                        resultSet.getString("domain"), longValue(resultSet.getObject("covered_interface_count")),
                        longValue(resultSet.getObject("sent_transaction_count")), longValue(resultSet.getObject("both_fail_same_code")),
                        longValue(resultSet.getObject("both_success")), longValue(resultSet.getObject("code_ignored")),
                        decimal(resultSet.getBigDecimal("match_pass_rate")), longValue(resultSet.getObject("issue_total")),
                        resultSet.getInt("source_row"), jsonValue(resultSet.getString("raw_json"))), batch);
    }

    public List<ReplayInterfaceComparisonRow> findComparisons(String batch) {
        return jdbc.query("SELECT * FROM dii_replay_interface_comparison WHERE batch_no=? ORDER BY source_row,id",
                (resultSet, ignored) -> new ReplayInterfaceComparisonRow(resultSet.getString("batch_no"),
                        ReplayDailyRowType.valueOf(resultSet.getString("row_type")), resultSet.getString("transaction_code"),
                        resultSet.getString("s_code"), resultSet.getString("transaction_description"), resultSet.getString("developer"),
                        resultSet.getString("bank_owner"), resultSet.getString("domain"), longValue(resultSet.getObject("sent_transaction_count")),
                        longValue(resultSet.getObject("c528_success_ccbs_fail")), longValue(resultSet.getObject("c528_fail_ccbs_success")),
                        longValue(resultSet.getObject("both_fail_same_code")), longValue(resultSet.getObject("both_fail_diff_code")),
                        longValue(resultSet.getObject("both_success")), longValue(resultSet.getObject("code_ignored")),
                        decimal(resultSet.getBigDecimal("transaction_success_rate")), decimal(resultSet.getBigDecimal("match_pass_rate")),
                        decimal(resultSet.getBigDecimal("c528_avg_duration")), decimal(resultSet.getBigDecimal("ccbs_avg_duration")),
                        resultSet.getInt("source_row"), jsonValue(resultSet.getString("raw_json"))), batch);
    }

    public List<ReplayCoverageSummaryRow> findCoverageSummaries(String batch) {
        return jdbc.query("SELECT * FROM dii_replay_coverage_summary WHERE batch_no=? ORDER BY source_row,id",
                (resultSet, ignored) -> new ReplayCoverageSummaryRow(resultSet.getString("batch_no"),
                        ReplayDailyRowType.valueOf(resultSet.getString("row_type")), resultSet.getString("business_domain"),
                        longValue(resultSet.getObject("full_transaction_count")), longValue(resultSet.getObject("sent_count")),
                        longValue(resultSet.getObject("unsent_count")), longValue(resultSet.getObject("excluded_count")),
                        longValue(resultSet.getObject("recent_transaction_count")), longValue(resultSet.getObject("pending_analysis_count")),
                        decimal(resultSet.getBigDecimal("coverage_rate")), resultSet.getInt("source_row"),
                        jsonValue(resultSet.getString("raw_json"))), batch);
    }

    public List<ReplayCoverageDetailRow> findCoverageDetails(String batch) {
        return jdbc.query("SELECT * FROM dii_replay_coverage_detail WHERE batch_no=? ORDER BY source_row,id",
                (resultSet, ignored) -> new ReplayCoverageDetailRow(resultSet.getString("batch_no"),
                        resultSet.getString("transaction_code"), resultSet.getString("transaction_description"),
                        resultSet.getString("business_domain"), resultSet.getString("s_code"), resultSet.getString("related_code"),
                        resultSet.getString("replay_required"), resultSet.getDate("latest_transaction_date") == null
                        ? null : resultSet.getDate("latest_transaction_date").toLocalDate(),
                        longValue(resultSet.getObject("sent_transaction_count")), resultSet.getString("coverage_status"),
                        resultSet.getString("unsent_reason"), resultSet.getString("developer"), resultSet.getString("bank_owner"),
                        resultSet.getInt("source_row"), jsonValue(resultSet.getString("raw_json"))), batch);
    }

    public List<ReplayDailyBatch> findBatchesRecentFirst() {
        List<ReplayDailyBatch> batches = jdbc.query("""
                        SELECT summary.batch_no,
                               MAX(summary.imported_at) AS imported_at,
                               MAX(snapshot.generated_at) AS generated_at,
                               MAX(mail.status) AS mail_status,
                               MAX(mail.sent_at) AS mail_sent_at,
                               MAX(mail.failure_message) AS mail_failure_message
                          FROM dii_replay_daily_summary summary
                          LEFT JOIN dii_replay_daily_report_snapshot snapshot
                            ON snapshot.batch_no = summary.batch_no
                          LEFT JOIN dii_replay_daily_report_mail mail
                            ON mail.batch_no = snapshot.batch_no
                         WHERE summary.batch_no LIKE 'RPT%' OR summary.batch_no LIKE 'DZ%'
                         GROUP BY summary.batch_no
                        """, (resultSet, ignored) -> new ReplayDailyBatch(
                resultSet.getString("batch_no"),
                batchFamily(resultSet.getString("batch_no")),
                resultSet.getTimestamp("imported_at").toLocalDateTime(),
                null,
                resultSet.getTimestamp("generated_at") != null,
                resultSet.getTimestamp("generated_at") == null
                        ? null : resultSet.getTimestamp("generated_at").toLocalDateTime(),
                resultSet.getString("mail_status") == null ? "UNSENT" : resultSet.getString("mail_status"),
                resultSet.getTimestamp("mail_sent_at") == null
                        ? null : resultSet.getTimestamp("mail_sent_at").toLocalDateTime(),
                resultSet.getString("mail_failure_message")));
        batches.removeIf(batch -> !ReplayDailyBatch.isStandardBatchNo(batch.batchNo()));

        Map<String, String> previousByBatch = new LinkedHashMap<>();
        Map<String, String> lastBatchByFamily = new HashMap<>();
        List<ReplayDailyBatch> batchesInFamilyOrder = new ArrayList<>(batches);
        batchesInFamilyOrder.sort(Comparator.comparing(ReplayDailyBatch::family)
                .thenComparing(ReplayDailyBatch::importedAt)
                .thenComparing(ReplayDailyBatch::batchNo));
        for (ReplayDailyBatch batch : batchesInFamilyOrder) {
            previousByBatch.put(batch.batchNo(), lastBatchByFamily.get(batch.family()));
            lastBatchByFamily.put(batch.family(), batch.batchNo());
        }

        batches.sort(Comparator.comparing(ReplayDailyBatch::importedAt).reversed()
                .thenComparing(ReplayDailyBatch::batchNo, Comparator.reverseOrder()));
        List<ReplayDailyBatch> recentFirst = new ArrayList<>(batches.size());
        for (ReplayDailyBatch batch : batches) {
            recentFirst.add(new ReplayDailyBatch(batch.batchNo(), batch.family(), batch.importedAt(),
                    previousByBatch.get(batch.batchNo()), batch.generated(), batch.generatedAt(),
                    batch.mailStatus(), batch.mailSentAt(), batch.mailFailureMessage()));
        }
        return recentFirst;
    }

    public List<ReplayDailyBatch> findGeneratedBatchesInFamilyOrder() {
        return findBatchesRecentFirst().stream()
                .filter(ReplayDailyBatch::generated)
                .sorted(Comparator.comparing(ReplayDailyBatch::family)
                        .thenComparing(ReplayDailyBatch::importedAt)
                        .thenComparing(ReplayDailyBatch::batchNo))
                .toList();
    }

    public List<ReplayDailyBatch> findBatchesWithDataInFamilyOrder() {
        return findBatchesRecentFirst().stream()
                .sorted(Comparator.comparing(ReplayDailyBatch::family)
                        .thenComparing(ReplayDailyBatch::importedAt)
                        .thenComparing(ReplayDailyBatch::batchNo))
                .toList();
    }

    public Optional<ReplayDailyReportSnapshot> findReportSnapshot(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return Optional.empty();
        }
        List<ReplayDailyReportSnapshot> snapshots = jdbc.query("""
                        SELECT batch_no, file_name, content_type, file_content, file_size,
                               summary_view_json, generated_at
                          FROM dii_replay_daily_report_snapshot
                         WHERE batch_no = ?
                        """, (resultSet, ignored) -> new ReplayDailyReportSnapshot(
                resultSet.getString("batch_no"),
                resultSet.getString("file_name"),
                resultSet.getString("content_type"),
                resultSet.getBytes("file_content"),
                resultSet.getLong("file_size"),
                resultSet.getString("summary_view_json"),
                resultSet.getTimestamp("generated_at").toLocalDateTime()), batchNo.trim());
        return snapshots.stream().findFirst();
    }

    public ReplayReportAttachmentOptionPage searchReportAttachmentOptions(
            String keyword, String family, int page, int size) {
        String normalizedFamily = family == null ? "ALL" : family.trim().toUpperCase();
        if (!List.of("ALL", "RPT", "DZ").contains(normalizedFamily)) {
            throw new IllegalArgumentException("日报类型错误");
        }
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.max(1, Math.min(100, size));
        String like = "%" + (keyword == null ? "" : keyword.trim().toLowerCase()) + "%";
        String familySql = "ALL".equals(normalizedFamily) ? "" : " AND batch_no LIKE ?";
        List<Object> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        if (!familySql.isEmpty()) args.add(normalizedFamily + "%");
        Long total = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM dii_replay_daily_report_snapshot
                         WHERE file_content IS NOT NULL AND file_size > 0
                           AND (LOWER(batch_no) LIKE ? OR LOWER(file_name) LIKE ?)
                        """ + familySql, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(normalizedSize);
        pageArgs.add((long) normalizedPage * normalizedSize);
        List<ReplayReportAttachmentOption> items = jdbc.query("""
                        SELECT batch_no,file_name,file_size,generated_at
                          FROM dii_replay_daily_report_snapshot
                         WHERE file_content IS NOT NULL AND file_size > 0
                           AND (LOWER(batch_no) LIKE ? OR LOWER(file_name) LIKE ?)
                        """ + familySql + " ORDER BY generated_at DESC,batch_no DESC LIMIT ? OFFSET ?",
                (resultSet, ignored) -> new ReplayReportAttachmentOption(
                        resultSet.getString("batch_no"), batchFamily(resultSet.getString("batch_no")),
                        resultSet.getString("file_name"), resultSet.getLong("file_size"),
                        resultSet.getTimestamp("generated_at").toLocalDateTime()), pageArgs.toArray());
        return new ReplayReportAttachmentOptionPage(items, normalizedPage, normalizedSize, total == null ? 0 : total);
    }

    public List<ReplayReportAttachmentOption> findReportAttachmentOptions(String keyword, String family) {
        String normalizedFamily = family == null ? "ALL" : family.trim().toUpperCase();
        if (!List.of("ALL", "RPT", "DZ").contains(normalizedFamily))
            throw new IllegalArgumentException("报告类型错误");
        String like = "%" + (keyword == null ? "" : keyword.trim().toLowerCase()) + "%";
        String familySql = "ALL".equals(normalizedFamily) ? "" : " AND batch_no LIKE ?";
        List<Object> args = new ArrayList<>(List.of(like, like));
        if (!familySql.isEmpty()) args.add(normalizedFamily + "%");
        return jdbc.query("""
                        SELECT batch_no,file_name,file_size,summary_view_json,generated_at
                          FROM dii_replay_daily_report_snapshot
                         WHERE file_content IS NOT NULL AND file_size > 0
                           AND (LOWER(batch_no) LIKE ? OR LOWER(file_name) LIKE ?)
                        """ + familySql + " ORDER BY generated_at DESC,batch_no DESC",
                (resultSet, ignored) -> new ReplayReportAttachmentOption(
                        resultSet.getString("batch_no"), batchFamily(resultSet.getString("batch_no")),
                        resultSet.getString("file_name"), resultSet.getLong("file_size"),
                        resultSet.getTimestamp("generated_at").toLocalDateTime(), ReplayReportPeriod.DAILY,
                        null, resultSet.getString("batch_no"), true,
                        resultSet.getString("summary_view_json") == null ? "LEGACY_EXCEL" : "SNAPSHOT_JSON"),
                args.toArray());
    }

    public Map<String, ReplayDailyReportSnapshot> findReportSnapshots(List<String> batchNos) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (batchNos != null) {
            batchNos.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).forEach(normalized::add);
        }
        if (normalized.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(normalized.size(), "?"));
        Map<String, ReplayDailyReportSnapshot> found = new HashMap<>();
        jdbc.query("SELECT batch_no,file_name,content_type,file_content,file_size,summary_view_json,generated_at "
                        + "FROM dii_replay_daily_report_snapshot WHERE batch_no IN (" + placeholders + ")",
                resultSet -> {
                    ReplayDailyReportSnapshot snapshot = new ReplayDailyReportSnapshot(
                            resultSet.getString("batch_no"), resultSet.getString("file_name"),
                            resultSet.getString("content_type"), resultSet.getBytes("file_content"),
                            resultSet.getLong("file_size"), resultSet.getString("summary_view_json"),
                            resultSet.getTimestamp("generated_at").toLocalDateTime());
                    found.put(snapshot.batchNo(), snapshot);
                }, normalized.toArray());
        Map<String, ReplayDailyReportSnapshot> ordered = new LinkedHashMap<>();
        normalized.forEach(batchNo -> {
            if (found.containsKey(batchNo)) ordered.put(batchNo, found.get(batchNo));
        });
        return ordered;
    }

    public void saveReportSnapshot(ReplayDailyReportSnapshot snapshot) {
        jdbc.update("""
                        INSERT INTO dii_replay_daily_report_snapshot
                               (batch_no, file_name, content_type, file_content, file_size,
                                summary_view_json, generated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                               file_name = VALUES(file_name),
                               content_type = VALUES(content_type),
                               file_content = VALUES(file_content),
                               file_size = VALUES(file_size),
                               summary_view_json = VALUES(summary_view_json),
                               generated_at = VALUES(generated_at)
                        """, snapshot.batchNo(), snapshot.fileName(), snapshot.contentType(), snapshot.content(),
                snapshot.fileSize(), snapshot.summaryViewJson(), Timestamp.valueOf(snapshot.generatedAt()));
    }

    public int deleteAllReportSnapshots() {
        return jdbc.update("DELETE FROM dii_replay_daily_report_snapshot");
    }

    public boolean batchExists(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return false;
        }
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                          FROM dii_replay_daily_summary
                         WHERE batch_no = ?
                        """, Long.class, batchNo.trim());
        return count != null && count > 0;
    }

    private static Long longValue(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static String jsonValue(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        try {
            return JSON.readValue(value, String.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }

    private static String batchFamily(String batchNo) {
        if (batchNo == null) {
            return null;
        }
        if (batchNo.startsWith("RPT")) {
            return "RPT";
        }
        if (batchNo.startsWith("DZ")) {
            return "DZ";
        }
        return "LEGACY";
    }
}
