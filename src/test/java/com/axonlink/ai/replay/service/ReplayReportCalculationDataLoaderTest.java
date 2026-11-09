package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayReportCalculationDataLoaderTest {
    private static final String START = "RPT20261029-000247-2239";
    private static final String END = "RPT20261104-194516-0435";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 4, 20, 0);
    private JdbcTemplate jdbc;
    private ReplayDailyDataDao dailyDao;
    private ReplayIssueDao issueDao;
    private ReplayReportCalculationDataLoader loader;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dailyDao = new ReplayDailyDataDao(jdbc);
        issueDao = new ReplayIssueDao(jdbc);
        loader = new ReplayReportCalculationDataLoader(dailyDao, issueDao);
    }

    @Test
    void legacyMembershipRestoresProblemStatisticsWithoutParsingItsTextQuantity() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, null, null)));
        long id = approvedTransaction(START, "旧版影响笔数说明");

        var result = loader.loadHistorical(START);

        assertTrue(result.availability().issueStatisticsAvailable(), "缺V88不代表该批旧问题成员不存在");
        assertEquals(1, result.historicalIssues().size());
        assertEquals(id, result.historicalIssues().get(0).issueId());
        assertEquals(0L, result.historicalIssues().get(0).affectedTransactionCount(), "旧问题统计与交易量隔离");
        assertTrue(result.availability().noActionUnknownDomains().contains("公共组"));
        assertTrue(result.notices().get(0).contains("当前处理结论"));
    }

    @Test
    void historicalMissingSnapshotRetainsItsOwnRawDataAndSelectsLegacyRateBasis() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, null, null)));

        var result = loader.loadHistorical(START);

        assertEquals(START, result.summaries().get(0).batchNo());
        assertEquals(new BigDecimal("0.81"), result.summaries().get(0).matchPassRate());
        assertEquals(1, result.comparisons().size());
        assertTrue(result.historicalIssues().isEmpty());
        assertTrue(result.metricIssues().isEmpty());
        assertFalse(result.availability().issueStatisticsAvailable());
        assertEquals(Set.of("公共组"), result.availability().noActionUnknownDomains());
        assertEquals(Set.of("公共组"), result.availability().breakdownUnknownDomains());
        assertEquals(Set.of("公共组"), result.availability().matchUnknownDomains());
        assertEquals(Set.of("公共组"), result.availability().legacyRateDomains());
        assertEquals(1, result.notices().size());
        assertTrue(result.notices().get(0).contains(START));
        assertTrue(result.notices().get(0).contains("沿用原算法"));
        assertTrue(result.notices().get(0).contains("已确认审核通过的本批笔数参与扣除"));
        assertTrue(result.notices().get(0).contains("无法确认部分不参与调整"));
    }

    @Test
    void historicalAvailableSnapshotKeepsItsApprovedQuantityWhileMissingSplitUsesLegacy() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, null, null)));
        long id = approvedTransaction(START, "9");
        issueDao.beginBatchStatistics(START, AT);
        issueDao.upsertOccurrenceBatch(id, "approved-tx", START, AT, ReplayIssueStatus.NO_ACTION);

        var result = loader.loadHistorical(START);

        assertTrue(result.availability().issueStatisticsAvailable());
        assertEquals(9L, result.metricIssues().get(0).affectedTransactionCount());
        assertEquals(ReplayIssueReviewStatus.APPROVED, result.metricIssues().get(0).reviewStatus());
        assertTrue(result.availability().noActionUnknownDomains().isEmpty());
        assertEquals(Set.of("公共组"), result.availability().legacyRateDomains());
    }

    @Test
    void realZeroFieldDifferenceCountsEnableTheNewFormula() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 74L, 0L)));
        issueDao.beginBatchStatistics(START, AT);

        var result = loader.loadHistorical(START);

        assertTrue(result.availability().issueStatisticsAvailable());
        assertTrue(result.availability().legacyRateDomains().isEmpty());
        assertTrue(result.availability().noActionUnknownDomains().isEmpty());
        assertTrue(result.notices().isEmpty());
    }

    @Test
    void completeNewFieldsUseTheNewFormulaEvenWhenHistoricalIssueEvidenceIsMissing() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 60L, 14L)));

        var result = loader.loadHistorical(START);

        assertFalse(result.availability().issueStatisticsAvailable());
        assertEquals(Set.of("公共组"), result.availability().noActionUnknownDomains());
        assertEquals(Set.of("公共组"), result.availability().matchUnknownDomains());
        assertTrue(result.availability().legacyRateDomains().isEmpty());
        assertEquals(1, result.notices().size());
        assertFalse(result.notices().get(0).contains("沿用原算法"));
    }

    @Test
    void realZeroNewFieldCountsStayNewEvenWhenHistoricalIssueEvidenceIsMissing() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 74L, 0L)));

        var result = loader.loadHistorical(START);

        assertFalse(result.availability().issueStatisticsAvailable());
        assertEquals(Set.of("公共组"), result.availability().noActionUnknownDomains());
        assertTrue(result.availability().legacyRateDomains().isEmpty());
    }

    @Test
    void aTotalAndBlankTransactionCodeDoNotEstablishAnInterfaceDomainScope() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.TOTAL, 74L, 0L),
                comparison(START, " ", ReplayDailyRowType.DETAIL, 74L, 0L)));
        issueDao.beginBatchStatistics(START, AT);

        var result = loader.loadHistorical(START);

        assertTrue(result.availability().issueStatisticsAvailable());
        assertEquals(Set.of("公共组"), result.availability().noActionUnknownDomains());
        assertEquals(Set.of("公共组"), result.availability().breakdownUnknownDomains());
        assertEquals(Set.of("公共组"), result.availability().matchUnknownDomains());
        assertTrue(result.availability().legacyRateDomains().isEmpty());
    }

    @Test
    void aDomainWithNoSentTransactionsRemainsKnownZeroWithoutHistoricalEvidence() {
        save(START, 0L, List.of());

        var result = loader.loadHistorical(START);

        assertTrue(result.availability().issueStatisticsAvailable(), "原始零问题汇总能确认零统计");
        assertTrue(result.availability().noActionUnknownDomains().isEmpty());
        assertTrue(result.availability().breakdownUnknownDomains().isEmpty());
        assertTrue(result.availability().matchUnknownDomains().isEmpty());
        assertEquals(Set.of("公共组"), result.availability().legacyRateDomains());
    }

    @Test
    void anExplicitZeroIssueSummaryRestoresZeroProblemStatisticsWithoutInventingTransactionQuantities() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 60L, 14L)));
        jdbc.update("UPDATE dii_replay_daily_summary SET issue_total=0 WHERE batch_no=?", START);

        var result = loader.loadHistorical(START);

        assertTrue(result.availability().issueStatisticsAvailable());
        assertTrue(result.historicalIssues().isEmpty());
        assertEquals(Set.of("公共组"), result.availability().noActionUnknownDomains());
        assertTrue(result.notices().get(0).contains("零问题统计"));
    }

    @Test
    void databaseFailureIsNotMistakenForAnIncompleteHistoricalSnapshot() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 74L, 0L)));
        jdbc.execute("DROP TABLE dii_replay_issue_statistics_batch");

        assertThrows(DataAccessException.class, () -> loader.loadHistorical(START));
    }

    @Test
    void weeklyLatestConclusionCanUseItsOwnOriginalQuantityEvenWithoutHistoricalStatistics() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 74L, 0L)));
        save(END, 200L, List.of(comparison(END, "6208", ReplayDailyRowType.DETAIL, 74L, 0L)));
        long id = approvedTransaction(END, "25");
        long round = issueDao.insertImportRound("old-round", AT, ReplayIssueOperator.system(), 1);
        issueDao.insertIssueRound(round, id, "approved-tx", true, ReplayIssueStatus.NEW, ReplayIssueStatus.OPEN,
                "导入更新", "公共组", 1, AT,
                "{\"issueKey\":\"approved-tx\",\"batchNo\":\"" + START + "\",\"transactionCode\":\"6208\","
                        + "\"issueLevel\":\"交易级\",\"fieldName\":\"528成功ccbs失败\",\"affectedTransactionCount\":\"9\"}", START);

        var result = loader.loadLatest(START, issueDao.findCurrentReportIssues(START, END));

        assertFalse(result.availability().issueStatisticsAvailable());
        assertTrue(result.historicalIssues().isEmpty());
        assertEquals(9L, result.metricIssues().get(0).affectedTransactionCount());
        assertTrue(result.availability().noActionUnknownDomains().isEmpty());
        assertTrue(result.availability().legacyRateDomains().isEmpty());
    }

    @Test
    void weeklyUnknownOriginalQuantityKeepsNewFieldFormulaWithoutBorrowingTheCurrentBatchCount() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 60L, 14L)));
        save(END, 200L, List.of(comparison(END, "6208", ReplayDailyRowType.DETAIL, 60L, 14L)));
        long id = approvedTransaction(END, "25");
        issueDao.upsertOccurrenceBatch(id, "approved-tx", START, AT, ReplayIssueStatus.NO_ACTION);

        var result = loader.loadLatest(START, issueDao.findCurrentReportIssues(START, END));

        assertEquals(0L, result.metricIssues().get(0).affectedTransactionCount());
        assertEquals(Set.of("公共组"), result.availability().noActionUnknownDomains());
        assertEquals(Set.of("公共组"), result.availability().matchUnknownDomains());
        assertTrue(result.availability().legacyRateDomains().isEmpty());
        assertEquals(1, result.notices().size());
        assertTrue(result.notices().get(0).contains("已确认审核通过的本批笔数参与扣除"));
        assertTrue(result.notices().get(0).contains("无法确认部分不参与调整"));
        assertFalse(result.notices().get(0).contains("沿用原算法"));
        assertFalse(result.notices().get(0).contains("approved-tx"));
    }

    @Test
    void anUnknownApprovedQuantityDoesNotDiscardAnotherConfirmedQuantityInTheSameNewFieldDomain() {
        save(START, 100L, List.of(comparison(START, "6208", ReplayDailyRowType.DETAIL, 60L, 14L)));
        save(END, 200L, List.of(comparison(END, "6208", ReplayDailyRowType.DETAIL, 60L, 14L)));
        approvedTransaction("known-tx", START, "9");
        long unknownId = approvedTransaction("unknown-tx", END, "25");
        issueDao.upsertOccurrenceBatch(unknownId, "unknown-tx", START, AT, ReplayIssueStatus.NO_ACTION);

        var result = loader.loadLatest(START, issueDao.findCurrentReportIssues(START, END));

        assertEquals(9L, result.metricIssues().stream().mapToLong(row -> row.affectedTransactionCount()).sum());
        assertEquals(Set.of("公共组"), result.availability().noActionUnknownDomains());
        assertTrue(result.availability().legacyRateDomains().isEmpty());
        assertEquals(1, result.notices().size());
        assertTrue(result.notices().get(0).contains("已确认审核通过的本批笔数参与扣除"));
        assertTrue(result.notices().get(0).contains("无法确认部分不参与调整"));
        assertFalse(result.notices().get(0).contains("比例采用原始汇总基数"));
    }

    private void save(String batch, long sent, List<ReplayInterfaceComparisonRow> comparisons) {
        var summary = new ReplayDailySummaryRow(batch, "公共组", 1L, sent, sent == 0 ? 0L : 7L, sent == 0 ? 0L : 74L, 0L,
                new BigDecimal(sent == 0 ? "0" : "0.81"), sent == 0 ? 0L : 3L, 1, "{}");
        dailyDao.replaceBatch(new ReplayDailyWorkbookData(batch, List.of(summary), comparisons, List.of(), List.of()), AT);
    }

    private long approvedTransaction(String batch, String count) {
        return approvedTransaction("approved-tx", batch, count);
    }

    private long approvedTransaction(String key, String batch, String count) {
        jdbc.update("INSERT INTO dii_replay_issue (source_sheet,group_name,is_sandbox,row_order,batch_no,transaction_code,"
                        + "issue_level,field_name,issue_status,issue_type,issue_key,affected_transaction_count,imported_at,"
                        + "review_status,defect_repair_date) VALUES ('公共组','公共组',0,1,?,'6208','交易级',"
                        + "'528成功ccbs失败','无需处理','规则性差异问题',?,?,?,'APPROVED','2026-11-04')",
                batch, key, count, AT);
        long id = jdbc.queryForObject("SELECT id FROM dii_replay_issue WHERE issue_key=?", Long.class, key);
        issueDao.upsertOccurrenceBatch(id, key, batch, AT, ReplayIssueStatus.NO_ACTION);
        return id;
    }

    private static ReplayInterfaceComparisonRow comparison(String batch, String code, ReplayDailyRowType type,
                                                           Long noDifference, Long withDifference) {
        return new ReplayInterfaceComparisonRow(batch, type, code, null, null, null, null, "公共组", 100L,
                19L, 0L, 7L, 0L, 74L, 0L, null, null, null, null, 1, "{}", noDifference, withDifference);
    }
}
