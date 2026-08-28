package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回放清单导入汇总信息持久化（dii_replay_issue_summary）。
 *
 * <p>与其它 replay DAO 一致，注入结果库 JdbcTemplate（diiResultJdbcTemplate，
 * 构造器参数名匹配 bean 名），建表 SQL 见 db/daoindex/V47__dii_replay_issue_summary.sql。
 */
@Repository
public class ReplayIssueSummaryDao {

    private static final String INSERT_SQL = """
            INSERT INTO dii_replay_issue_summary (
                round_code, batch_no, domain,
                covered_interface_count, sent_transaction_count,
                c528_success_ccbs_fail, ccbs_failure_detail, c528_fail_ccbs_success,
                both_fail_same_code, both_fail_diff_code, both_success, code_ignored,
                success_rate, match_pass_rate, raw_json, imported_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public ReplayIssueSummaryDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /**
     * 批量写入一轮导入的汇总信息（同 roundCode）。
     *
     * @param rows       解析出的汇总行
     * @param roundCode  导入轮次号（关联 dii_replay_import_round）
     * @param importedAt 导入时间
     */
    public void insertAll(String roundCode, List<ReplayIssueSummaryRow> rows, LocalDateTime importedAt) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_SQL, rows, rows.size(), (statement, row) -> {
            statement.setString(1, roundCode);
            statement.setString(2, row.batchNo());
            statement.setString(3, row.domain());
            statement.setObject(4, row.coveredInterfaceCount());
            statement.setObject(5, row.sentTransactionCount());
            statement.setObject(6, row.c528SuccessCcbsFail());
            statement.setObject(7, row.ccbsFailureDetail());
            statement.setObject(8, row.c528FailCcbsSuccess());
            statement.setObject(9, row.bothFailSameCode());
            statement.setObject(10, row.bothFailDiffCode());
            statement.setObject(11, row.bothSuccess());
            statement.setObject(12, row.codeIgnored());
            statement.setObject(13, row.successRate());
            statement.setObject(14, row.matchPassRate());
            statement.setString(15, row.rawJson());
            statement.setTimestamp(16, java.sql.Timestamp.valueOf(importedAt));
            statement.setTimestamp(17, java.sql.Timestamp.valueOf(importedAt));
        });
    }

    /** 按导入轮次查询汇总记录（测试/审计用）。 */
    public List<ReplayIssueSummaryRow> findByRound(String roundCode) {
        return jdbc.query("""
                        SELECT batch_no, domain, covered_interface_count, sent_transaction_count,
                               c528_success_ccbs_fail, ccbs_failure_detail, c528_fail_ccbs_success,
                               both_fail_same_code, both_fail_diff_code, both_success, code_ignored,
                               success_rate, match_pass_rate, raw_json
                          FROM dii_replay_issue_summary
                         WHERE round_code = ?
                         ORDER BY id
                        """, (resultSet, ignored) -> new ReplayIssueSummaryRow(
                resultSet.getString("batch_no"),
                resultSet.getString("domain"),
                toLong(resultSet.getObject("covered_interface_count")),
                toLong(resultSet.getObject("sent_transaction_count")),
                toLong(resultSet.getObject("c528_success_ccbs_fail")),
                toLong(resultSet.getObject("ccbs_failure_detail")),
                toLong(resultSet.getObject("c528_fail_ccbs_success")),
                toLong(resultSet.getObject("both_fail_same_code")),
                toLong(resultSet.getObject("both_fail_diff_code")),
                toLong(resultSet.getObject("both_success")),
                toLong(resultSet.getObject("code_ignored")),
                toDouble(resultSet.getObject("success_rate")),
                toDouble(resultSet.getObject("match_pass_rate")),
                ReplayIssueSummaryRow.Part.UPPER,
                resultSet.getString("raw_json")), roundCode);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.doubleValue() : Double.valueOf(value.toString());
    }
}
