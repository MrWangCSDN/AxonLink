package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayWeeklyLatestIssueResolverTest {
    private static final String START = "RPT20261029-000247-2239";
    private static final String END = "RPT20261104-194516-0435";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 4, 20, 0);
    private static final ObjectMapper JSON = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayWeeklyLatestIssueResolver resolver;
    private long roundSequence;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        resolver = new ReplayWeeklyLatestIssueResolver(dao);
        for (String batch : List.of(START, END)) {
            jdbc.update("INSERT INTO dii_replay_interface_comparison "
                    + "(batch_no,row_type,transaction_code,source_row,imported_at) VALUES (?,'DETAIL','6208',1,?)", batch, AT);
        }
    }

    @Test
    void latestApprovedConclusionUsesEachSelectedBatchsOwnOriginalQuantity() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528成功ccbs失败", "9"));
        observe(id, "tx", END, true, raw("tx", END, "6208", "交易级", "528成功ccbs失败", "25"));
        List<ReplayIssueRow> latest = dao.findCurrentReportIssues(START, END);

        var start = resolver.resolve(START, List.of(comparison(START, "6208", "贷款组", 6L)), latest);
        var end = resolver.resolve(END, List.of(comparison(END, "6208", "公共组", 8L)), latest);

        assertEquals(9L, only(start).affectedTransactionCount());
        assertEquals(25L, only(end).affectedTransactionCount());
        assertEquals("无需处理", only(start).issueStatus());
        assertEquals(ReplayIssueReviewStatus.APPROVED, only(start).reviewStatus());
        assertEquals("贷款组", only(start).groupName());
        assertTrue(start.noActionUnknownDomains().isEmpty());
    }

    @Test
    void lastObservationWinsAndExplicitAbsenceNeverResurrectsAnEarlierQuantity() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528成功ccbs失败", "9"));
        observe(id, "tx", START, false, null);

        var result = resolve(START, "公共组");

        assertEquals(0L, result.metricIssues().stream().mapToLong(ReplayDailyIssueStatisticRow::affectedTransactionCount).sum());
        assertTrue(result.noActionUnknownDomains().isEmpty());
    }

    @Test
    void latestSameBatchImportReplacesEarlierOriginalQuantity() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528成功ccbs失败", "9"));
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528成功ccbs失败", "11"));

        assertEquals(11L, only(resolve(START, "公共组")).affectedTransactionCount());
    }

    @Test
    void currentQuantityOnlyFallsBackForTheExactSelectedBatchAndPreservesKnownZero() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", END, "0", true, true);
        dao.upsertOccurrenceBatch(id, "tx", START, AT, ReplayIssueStatus.NO_ACTION);

        var start = resolve(START, "沙箱—贷款组");
        var end = resolve(END, "沙箱—贷款组");

        assertEquals(Set.of("沙箱-贷款组"), start.noActionUnknownDomains());
        assertEquals(Set.of("沙箱-贷款组"), start.matchUnknownDomains());
        assertEquals(0L, only(end).affectedTransactionCount());
        assertTrue(end.noActionUnknownDomains().isEmpty());
        assertFalse(start.notices().isEmpty());
    }

    @Test
    void invalidHistoricalCountsRemainUnknownInsteadOfBecomingZero() {
        for (String invalid : List.of("", "bad", "-1", "1.2", "9223372036854775808")) {
            long id = issue("tx-" + invalid, "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
            observe(id, "tx-" + invalid, START, true,
                    raw("tx-" + invalid, START, "6208", "交易级", "528成功ccbs失败", invalid));
        }

        var result = resolve(START, "公共组");

        assertEquals(Set.of("公共组"), result.noActionUnknownDomains());
        assertFalse(result.notices().isEmpty());
    }

    @Test
    void batchOwnedStatisticsAreUsableWhenNoImportRoundWasPreserved() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", START, "9", true, true);
        dao.beginBatchStatistics(START, AT);
        dao.upsertOccurrenceBatch(id, "tx", START, AT, ReplayIssueStatus.NO_ACTION);
        jdbc.update("UPDATE dii_replay_issue SET batch_no=?,affected_transaction_count='25' WHERE id=?", END, id);

        var result = resolve(START, "公共组");

        assertEquals(9L, only(result).affectedTransactionCount());
        assertTrue(result.noActionUnknownDomains().isEmpty());
    }

    @Test
    void anImportRoundOutranksAConflictingBatchStatisticsSnapshot() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", START, "25", true, true);
        dao.beginBatchStatistics(START, AT);
        dao.upsertOccurrenceBatch(id, "tx", START, AT, ReplayIssueStatus.NO_ACTION);
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528成功ccbs失败", "9"));

        assertEquals(9L, only(resolve(START, "公共组")).affectedTransactionCount());
    }

    @Test
    void mismatchedRawIdentityIsUnknownEvenWhenCurrentRowBelongsToSelectedBatch() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", START, "25", true, true);
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528失败ccbs成功", "9"));

        assertEquals(Set.of("公共组"), resolve(START, "公共组").noActionUnknownDomains());
    }

    @Test
    void normalizedFailureFieldIdentityRetainsTheBatchOriginalQuantity() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528 成功 / CCBS 失败", "9"));

        var result = resolve(START, "公共组");

        assertEquals(9L, only(result).affectedTransactionCount());
        assertTrue(result.noActionUnknownDomains().isEmpty());
    }

    @Test
    void aNonNullIncomingNumericIdMustMatchThePersistedIssueIdentity() throws Exception {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
        ObjectNode node = (ObjectNode) JSON.readTree(raw("tx", START, "6208", "交易级", "528成功ccbs失败", "9"));
        node.put("id", id + 100);
        observe(id, "tx", START, true, node.toString());

        assertEquals(Set.of("公共组"), resolve(START, "公共组").noActionUnknownDomains());
    }

    @Test
    void corruptLatestObservationDoesNotFallBackToAnOlderValidOne() {
        long id = issue("tx", "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
        observe(id, "tx", START, true, raw("tx", START, "6208", "交易级", "528成功ccbs失败", "9"));
        observe(id, "tx", START, true, "not-json");

        assertEquals(Set.of("公共组"), resolve(START, "公共组").noActionUnknownDomains());
    }

    @Test
    void unresolvedLatestFieldBlocksBonusAndAllCurrentFieldsAreCheckedAcrossBatches() {
        issue("approved", "6208", "字段级", "姓名", END, "1", true, true);
        long open = issue("open", "6208", "字段级", "地址", "RPT20261001-other", "1", false, false);
        issue("other-code", "9999", "字段级", "地址", END, "1", false, false);

        var blocked = resolve(START, "公共组");
        assertEquals(2, blocked.metricIssues().size());
        assertTrue(blocked.metricIssues().stream().anyMatch(row -> row.defectRepairDate() == null));

        jdbc.update("UPDATE dii_replay_issue SET defect_repair_date=? WHERE id=?", LocalDate.of(2026, 11, 4), open);
        var accepted = resolve(START, "公共组");
        assertEquals(2, accepted.metricIssues().size());
        assertTrue(accepted.metricIssues().stream().allMatch(row -> row.defectRepairDate() != null));
        assertTrue(accepted.matchUnknownDomains().isEmpty());
    }

    @Test
    void duplicateIssueKeysUseTheLatestConclusionRatherThanBlockingOnAnOlderVersion() {
        long id = issue("field", "6208", "字段级", "姓名", END, "1", false, false);
        ReplayIssueRow older = dao.findCurrentReportIssues(START, END).get(0);
        jdbc.update("UPDATE dii_replay_issue SET imported_at=?,issue_status='无需处理',review_status='APPROVED',"
                + "defect_repair_date=? WHERE id=?", AT.plusHours(1), LocalDate.of(2026, 11, 4), id);
        ReplayIssueRow latest = dao.findCurrentReportIssues(START, END).get(0);

        var result = resolver.resolve(START, List.of(comparison(START, "6208", "公共组", 6L)),
                List.of(latest, older));

        assertEquals(1, result.metricIssues().size());
        assertEquals(ReplayIssueReviewStatus.APPROVED, only(result).reviewStatus());
        assertEquals(LocalDate.of(2026, 11, 4), only(result).defectRepairDate());
        assertTrue(result.matchUnknownDomains().isEmpty());
    }

    @Test
    void anOlderMatchingTransactionIsNotResurrectedAfterTheLatestKeyChangedCode() {
        long id = issue("field", "6208", "字段级", "姓名", END, "1", true, true);
        ReplayIssueRow older = dao.findCurrentReportIssues(START, END).get(0);
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='9999',imported_at=? WHERE id=?", AT.plusHours(1), id);
        ReplayIssueRow latest = dao.findCurrentByIdForUpdate(id);

        var result = resolver.resolve(START, List.of(comparison(START, "6208", "公共组", 6L)),
                List.of(older, latest));

        assertTrue(result.metricIssues().isEmpty());
    }

    @Test
    void missingFieldSplitIsUnknownOnlyWhenTheLatestConclusionsQualifyForBonus() {
        issue("approved", "6208", "字段级", "姓名", END, "1", true, true);

        var result = resolver.resolve(START, List.of(comparison(START, "6208", "公共组", null)),
                dao.findCurrentReportIssues(START, END));

        assertEquals(Set.of("公共组"), result.matchUnknownDomains());
        assertTrue(result.noActionUnknownDomains().isEmpty());
    }

    @Test
    void anIncompleteDetailWithoutATransactionCodeStillMakesTheDomainSplitUnknown() {
        issue("approved", "6208", "字段级", "姓名", END, "1", true, true);

        var result = resolver.resolve(START, List.of(comparison(START, "6208", "公共组", 6L),
                        comparison(START, "", "公共组", null)), dao.findCurrentReportIssues(START, END));

        assertEquals(Set.of("公共组"), result.matchUnknownDomains());
    }

    @Test
    void quantityUncertaintyForAnOpenTransactionOnlyMasksItsFailureBreakdown() {
        long id = issue("open", "6208", "交易级", "528成功ccbs失败", END, "25", false, false);
        dao.upsertOccurrenceBatch(id, "open", START, AT, ReplayIssueStatus.OPEN);

        var result = resolve(START, "公共组");

        assertEquals(Set.of("公共组"), result.breakdownUnknownDomains());
        assertTrue(result.noActionUnknownDomains().isEmpty());
        assertTrue(result.matchUnknownDomains().isEmpty());
    }

    @Test
    void manyUnknownIssuesProduceOneBatchNoticeCoveringDomainsAndBothGapReasons() {
        jdbc.update("INSERT INTO dii_replay_interface_comparison "
                + "(batch_no,row_type,transaction_code,source_row,imported_at) VALUES (?,'DETAIL','6209',2,?)", START, AT);
        for (int index = 0; index < 50; index++) {
            long id = issue("missing-tx-" + index, index % 2 == 0 ? "6208" : "6209", "交易级",
                    "528成功ccbs失败", END, "25", true, true);
            dao.upsertOccurrenceBatch(id, "missing-tx-" + index, START, AT, ReplayIssueStatus.NO_ACTION);
        }
        issue("approved-field", "6209", "字段级", "姓名", END, "1", true, true);

        var result = resolver.resolve(START, List.of(comparison(START, "6208", "公共组", 6L),
                        comparison(START, "6209", "贷款组", null)), dao.findCurrentReportIssues(START, END));

        assertEquals(1, result.notices().size());
        String notice = result.notices().get(0);
        assertTrue(notice.contains(START));
        assertTrue(notice.contains("公共组"));
        assertTrue(notice.contains("贷款组"));
        assertTrue(notice.contains("原始影响笔数"));
        assertTrue(notice.contains("字段差异拆分"));
        assertFalse(notice.contains("missing-tx-"));
        assertFalse(notice.contains("approved-field"));
        assertEquals(Set.of("公共组", "贷款组"), result.noActionUnknownDomains());
        assertEquals(Set.of("公共组", "贷款组"), result.matchUnknownDomains());
        assertTrue(result.breakdownUnknownDomains().isEmpty());
    }

    @Test
    void dzEndpointsExcludeOtherBatchOnlyTransactionsWithTheSameCodeFromTheirUnknownMasks() {
        String dzStart = "DZ20261029-000348-4883";
        String dzEnd = "DZ20261104-201323-3518";
        for (String batch : List.of(dzStart, dzEnd)) {
            jdbc.update("INSERT INTO dii_replay_interface_comparison "
                    + "(batch_no,row_type,transaction_code,source_row,imported_at) VALUES (?,'DETAIL','6208',1,?)", batch, AT);
        }
        long startOpen = issue("start-open", "6208", "交易级", "528成功ccbs失败", dzStart, "7", false, false);
        long startAccepted = issue("start-accepted", "6208", "交易级", "528成功ccbs失败", dzStart, "3", true, true);
        long endOpen = issue("end-open", "6208", "交易级", "528成功ccbs失败", dzEnd, "12", false, false);
        long endAccepted = issue("end-accepted", "6208", "交易级", "528成功ccbs失败", dzEnd, "5", true, true);
        List<ReplayIssueRow> latest = dao.findCurrentReportIssues(dzStart, dzEnd);

        var start = resolver.resolve(dzStart, List.of(comparison(dzStart, "6208", "公共组", 6L)), latest);
        var end = resolver.resolve(dzEnd, List.of(comparison(dzEnd, "6208", "公共组", 6L)), latest);

        assertEquals(Set.of(startOpen, startAccepted), start.metricIssues().stream()
                .map(ReplayDailyIssueStatisticRow::issueId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(endOpen, endAccepted), end.metricIssues().stream()
                .map(ReplayDailyIssueStatisticRow::issueId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(10L, start.metricIssues().stream().mapToLong(ReplayDailyIssueStatisticRow::affectedTransactionCount).sum());
        assertEquals(17L, end.metricIssues().stream().mapToLong(ReplayDailyIssueStatisticRow::affectedTransactionCount).sum());
        assertTrue(start.noActionUnknownDomains().isEmpty());
        assertTrue(start.breakdownUnknownDomains().isEmpty());
        assertTrue(end.noActionUnknownDomains().isEmpty());
        assertTrue(end.breakdownUnknownDomains().isEmpty());
    }

    @Test
    void aBrokenOriginalObservationStillScopesAnIssueEvenWithoutAnOccurrenceLink() {
        long id = issue("raw-only", "6208", "交易级", "528成功ccbs失败", END, "25", true, true);
        observe(id, "raw-only", START, true, "not-json");

        var result = resolve(START, "公共组");

        assertEquals(1, result.metricIssues().size());
        assertEquals(0L, result.metricIssues().get(0).affectedTransactionCount());
        assertEquals(Set.of("公共组"), result.noActionUnknownDomains());
    }

    @Test
    void latestConclusionScopeExcludesTheOtherBatchFamilyAndIrrelevantCodes() {
        issue("rpt", "6208", "交易级", "528成功ccbs失败", END, "2", true, true);
        long dz = issue("dz", "6208", "交易级", "528成功ccbs失败", "DZ20261104-other", "99", true, true);
        dao.upsertOccurrenceBatch(dz, "dz", START, AT, ReplayIssueStatus.NO_ACTION);
        issue("unrelated", "9999", "交易级", "528成功ccbs失败", END, "99", true, true);

        var latest = dao.findCurrentReportIssues(START, END);
        assertEquals(Set.of("rpt"), latest.stream().map(ReplayIssueRow::issueKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, resolver.resolve(END, List.of(comparison(END, "6208", "公共组", 6L)), latest).metricIssues().size());
    }

    private ReplayWeeklyLatestIssueResolver.Resolution resolve(String batch, String domain) {
        return resolver.resolve(batch, List.of(comparison(batch, "6208", domain, 6L)),
                dao.findCurrentReportIssues(START, END));
    }

    private static ReplayDailyIssueStatisticRow only(ReplayWeeklyLatestIssueResolver.Resolution result) {
        assertEquals(1, result.metricIssues().size());
        return result.metricIssues().get(0);
    }

    private long issue(String key, String code, String level, String field, String batch, String count,
                       boolean approvedNoAction, boolean repaired) {
        jdbc.update("INSERT INTO dii_replay_issue (source_sheet,group_name,is_sandbox,row_order,batch_no,transaction_code,"
                        + "issue_level,field_name,issue_status,issue_type,issue_key,affected_transaction_count,imported_at,"
                        + "review_status,defect_repair_date) VALUES ('公共组','公共组',0,1,?,?,?,?,?,?,?,?,?,?,?)",
                batch, code, level, field, approvedNoAction ? "无需处理" : "打开", "规则性差异问题", key, count,
                AT, approvedNoAction ? "APPROVED" : null, repaired ? LocalDate.of(2026, 11, 4) : null);
        long id = jdbc.queryForObject("SELECT id FROM dii_replay_issue WHERE issue_key=?", Long.class, key);
        dao.upsertOccurrenceBatch(id, key, batch, AT, approvedNoAction ? ReplayIssueStatus.NO_ACTION : ReplayIssueStatus.OPEN);
        return id;
    }

    private void observe(long id, String key, String batch, boolean appeared, String snapshot) {
        long sequence = ++roundSequence;
        long round = dao.insertImportRound("round-" + sequence, AT.plusMinutes(sequence),
                new ReplayIssueOperator("tester", "测试"), 1);
        dao.insertIssueRound(round, id, key, appeared, ReplayIssueStatus.OPEN, ReplayIssueStatus.OPEN,
                appeared ? "更新" : "自动修复", "公共组", 1, AT.plusMinutes(sequence), snapshot, batch);
    }

    private static String raw(String key, String batch, String code, String level, String field, String count) {
        ObjectNode node = JSON.createObjectNode();
        node.put("issueKey", key);
        node.put("batchNo", batch);
        node.put("transactionCode", code);
        node.put("issueLevel", level);
        node.put("fieldName", field);
        node.put("affectedTransactionCount", count);
        node.put("issueStatus", "NEW");
        node.putNull("defectRepairDate");
        node.putNull("reviewStatus");
        return node.toString();
    }

    private static ReplayInterfaceComparisonRow comparison(String batch, String code, String domain, Long differences) {
        return new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL, code, null, null, null, null,
                domain, 100L, 20L, 0L, 3L, 0L, 77L, 0L, null, null, null, null, 1, null, 71L, differences);
    }
}
