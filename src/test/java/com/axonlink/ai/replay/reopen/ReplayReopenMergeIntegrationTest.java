package com.axonlink.ai.replay.reopen;

import static org.junit.jupiter.api.Assertions.*;

import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.replay.service.*;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class ReplayReopenMergeIntegrationTest extends ReplayReopenTestSupport {
    @Test
    void normalImportHookCountsRepeatedReopensInSameBatchBeyondLegacyHistoryGate() {
        long id = issues.insertCurrent(row(ReplayIssueStatus.PENDING_VERIFICATION));
        issues.updateIssueDomain(id, "结算组");
        var merge = new ReplayIssueMergeService(issues, recorder);
        var workbook =
                new ReplayIssueExcelParser.ParsedWorkbook(
                        List.of(row(ReplayIssueStatus.OPEN)), Map.of("公共组", 1), 0, 1);
        merge.merge(workbook, LocalDate.of(2026, 10, 22), ReplayIssueOperator.system(), "first");
        merge.merge(
                workbook, LocalDate.of(2026, 10, 22), ReplayIssueOperator.system(), "duplicate");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证' WHERE id=?", id);
        merge.merge(
                workbook,
                LocalDate.of(2026, 10, 22),
                ReplayIssueOperator.system(),
                "second-reopen");
        assertEquals(2, dao.issueCounts(List.of("K")).get("K"));
        assertTrue(
                dao.events(filter()).items().stream()
                        .allMatch(event -> "结算组".equals(event.issueDomain())));
    }

    @Test
    void disabledFeatureKeepsNormalImportWorkingWithoutEventTables() {
        properties.setEnabled(false);
        issues.insertCurrent(row(ReplayIssueStatus.PENDING_VERIFICATION));
        jdbc.execute("DROP TABLE dii_replay_reopen_event");
        var workbook =
                new ReplayIssueExcelParser.ParsedWorkbook(
                        List.of(row(ReplayIssueStatus.OPEN)), Map.of("公共组", 1), 0, 1);
        new ReplayIssueMergeService(issues, recorder)
                .merge(
                        workbook,
                        LocalDate.of(2026, 10, 22),
                        ReplayIssueOperator.system(),
                        "disabled");
        assertEquals(
                ReplayIssueStatus.REOPENED,
                issues.findCurrentByIssueKeyForUpdate("K").issueStatus());
    }

    @Test
    void fullRefreshCorrectsBaselineWithoutGeneratingAnotherFactOrErasingLedger() throws Exception {
        record(1);
        issues.insertCurrent(row(ReplayIssueStatus.PENDING_VERIFICATION));
        var file =
                com.axonlink.ai.replay.ReplayIssueTestFixtures.fullRefreshWorkbook(
                        Map.of(
                                "基础数据",
                                List.of(
                                        Map.of(
                                                "领域",
                                                "公共组",
                                                "issue_id",
                                                "I",
                                                "issue_key",
                                                "K",
                                                "问题状态",
                                                "重新打开",
                                                "批次",
                                                "RPT20261022-1",
                                                "是否沙箱",
                                                "否"))));
        new ReplayIssueFullRefreshService(
                        new ReplayIssueFullRefreshExcelParser(),
                        issues,
                        new com.axonlink.ai.user.persistence.SysUserDao(jdbc),
                        new ReplayIssueImportGate())
                .fullRefresh(file, ReplayIssueOperator.system());
        assertEquals(
                ReplayIssueStatus.REOPENED,
                issues.findCurrentByIssueKeyForUpdate("K").issueStatus());
        assertEquals(1, dao.issueCounts(List.of("K")).get("K"));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_round", Long.class));
    }

    @Test
    void failedFactInsertRollsBackCurrentIssueAndImportRound() {
        issues.insertCurrent(row(ReplayIssueStatus.PENDING_VERIFICATION));
        jdbc.execute(
                "ALTER TABLE dii_replay_reopen_event ADD CONSTRAINT reject_fact"
                        + " CHECK(occurrence_no<1)");
        var merge = new ReplayIssueMergeService(issues, recorder);
        var workbook =
                new ReplayIssueExcelParser.ParsedWorkbook(
                        List.of(row(ReplayIssueStatus.OPEN)), Map.of("公共组", 1), 0, 1);
        assertThrows(
                RuntimeException.class,
                () ->
                        merge.merge(
                                workbook,
                                LocalDate.of(2026, 10, 22),
                                ReplayIssueOperator.system(),
                                "failure"));
        assertEquals(
                ReplayIssueStatus.PENDING_VERIFICATION,
                issues.findCurrentByIssueKeyForUpdate("K").issueStatus());
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_import_round", Long.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_counter", Long.class));
    }
}
