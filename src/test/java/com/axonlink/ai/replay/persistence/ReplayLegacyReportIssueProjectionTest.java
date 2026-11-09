package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.service.ReplayWeeklyLatestIssueResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayLegacyReportIssueProjectionTest {
    private static final String START = "RPT20261029-000247-2239";
    private static final String END = "RPT20261104-194516-0435";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 4, 20, 0);
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
    }

    @Test
    void exactBatchMembersIncludeCurrentRowsWithoutOccurrenceLinksAndNeverDuplicateThem() {
        long historical = issue("historical", END, "25", "打开");
        long current = issue("current", " " + START + " ", "9", "打开");
        long linkedCurrent = issue("both-links", START, "3", "打开");
        issue("unrelated-same-code", END, "99", "打开");
        dao.upsertOccurrenceBatch(historical, "historical", " " + START + " ", AT, ReplayIssueStatus.OPEN);
        jdbc.update("UPDATE dii_replay_issue_occurrence_batch SET batch_name=? WHERE replay_issue_id=?", " " + START + " ", historical);
        dao.upsertOccurrenceBatch(linkedCurrent, "both-links", START, AT, ReplayIssueStatus.OPEN);

        List<ReplayIssueRow> members = dao.findCurrentIssueMembersByBatch(" " + START + " ");
        var statistics = dao.findLegacyReportIssueStatistics(START);

        assertEquals(List.of(historical, current, linkedCurrent), members.stream().map(ReplayIssueRow::id).toList());
        assertEquals(Set.of(historical, current, linkedCurrent), statistics.stream()
                .map(row -> row.issueId()).collect(Collectors.toSet()));
        assertEquals(3, statistics.size());
    }

    @Test
    void statisticsProjectionNeverParsesDirtyCurrentAffectedCounts() {
        for (String count : List.of("not-a-number", "-7", "9223372036854775808")) {
            long id = issue("dirty-" + count, END, count, "打开");
            dao.upsertOccurrenceBatch(id, "dirty-" + count, START, AT, ReplayIssueStatus.OPEN);
        }

        var statistics = dao.findLegacyReportIssueStatistics(START);

        assertEquals(3, statistics.size());
        assertTrue(statistics.stream().allMatch(row -> row.affectedTransactionCount() == 0L));
        assertTrue(statistics.stream().allMatch(row -> "代码问题".equals(row.issueType())));
        assertTrue(statistics.stream().allMatch(row -> "打开".equals(row.issueStatus())));
    }

    @Test
    void statisticsPreserveCurrentClassificationReviewAndTheLegacyReopenedFlag() {
        long id = issue("reviewed", START, "bad-count", "新建");
        jdbc.update("UPDATE dii_replay_issue SET group_name='贷款组',is_sandbox=1,review_status='APPROVED',"
                + "defect_repair_date='2026-11-04' WHERE id=?", id);
        jdbc.update("INSERT INTO dii_replay_issue_history "
                + "(replay_issue_id,issue_key,operation_type,operation_at,occurrence_batch_name) VALUES (?,?,'已修复问题重新新建',?,?)",
                id, "reviewed", AT, "RPT20261001-previous");

        var row = dao.findLegacyReportIssueStatistics(START).get(0);

        assertEquals("贷款组", row.groupName());
        assertTrue(row.sandbox());
        assertEquals("代码问题", row.issueType());
        assertEquals("交易级", row.issueLevel());
        assertEquals("528成功ccbs失败", row.fieldName());
        assertEquals("新建", row.issueStatus());
        assertTrue(row.reopenedAfterFixed());
        assertEquals(ReplayIssueReviewStatus.APPROVED, row.reviewStatus());
        assertEquals(LocalDate.of(2026, 11, 4), row.defectRepairDate());
        assertEquals("6208", row.transactionCode());
        assertEquals(0L, row.affectedTransactionCount());
    }

    @Test
    void memberRowsReuseTheSelectedBatchOriginalQuantityRatherThanCurrentOtherBatchQuantity() {
        long id = issue("approved", END, "25", "无需处理");
        dao.upsertOccurrenceBatch(id, "approved", START, AT, ReplayIssueStatus.NO_ACTION);
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED' WHERE id=?", id);
        long round = dao.insertImportRound("historical-import", AT, ReplayIssueOperator.system(), 1);
        dao.insertIssueRound(round, id, "approved", true, ReplayIssueStatus.OPEN, ReplayIssueStatus.OPEN,
                "导入更新", "公共组", 1, AT,
                "{\"issueKey\":\"approved\",\"batchNo\":\"" + START + "\",\"transactionCode\":\"6208\","
                        + "\"issueLevel\":\"交易级\",\"fieldName\":\"528成功ccbs失败\",\"affectedTransactionCount\":\"9\"}", START);

        var resolved = new ReplayWeeklyLatestIssueResolver(dao).resolve(START, List.of(comparison()),
                dao.findCurrentIssueMembersByBatch(START));

        assertEquals(9L, resolved.metricIssues().get(0).affectedTransactionCount());
        assertTrue(resolved.noActionUnknownDomains().isEmpty());
        assertEquals(0L, dao.findLegacyReportIssueStatistics(START).get(0).affectedTransactionCount());
    }

    @Test
    void memberWithoutOriginalEvidenceNeverBorrowsTheCurrentOtherBatchQuantity() {
        long id = issue("approved", END, "25", "无需处理");
        dao.upsertOccurrenceBatch(id, "approved", START, AT, ReplayIssueStatus.NO_ACTION);
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED' WHERE id=?", id);

        var resolved = new ReplayWeeklyLatestIssueResolver(dao).resolve(START, List.of(comparison()),
                dao.findCurrentIssueMembersByBatch(START));

        assertEquals(0L, resolved.metricIssues().get(0).affectedTransactionCount());
        assertEquals(Set.of("公共组"), resolved.noActionUnknownDomains());
        assertFalse(resolved.notices().isEmpty());
    }

    private long issue(String key, String batch, String count, String status) {
        jdbc.update("INSERT INTO dii_replay_issue (source_sheet,group_name,is_sandbox,row_order,batch_no,transaction_code,"
                        + "issue_level,field_name,issue_status,issue_type,issue_key,affected_transaction_count,imported_at) "
                        + "VALUES ('公共组','公共组',0,1,?,'6208','交易级','528成功ccbs失败',?,'代码问题',?,?,?)",
                batch, status, key, count, AT);
        return jdbc.queryForObject("SELECT id FROM dii_replay_issue WHERE issue_key=?", Long.class, key);
    }

    private static ReplayInterfaceComparisonRow comparison() {
        return new ReplayInterfaceComparisonRow(START, ReplayDailyRowType.DETAIL, "6208", null, null, null, null,
                "公共组", 100L, 19L, 0L, 7L, 0L, 74L, 0L, null, null, null, null, 1, "{}", null, null);
    }
}
