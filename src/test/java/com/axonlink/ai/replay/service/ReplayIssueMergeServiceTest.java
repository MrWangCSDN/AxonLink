package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueMergeServiceTest {
    private ReplayIssueDao dao;
    private ReplayIssueMergeService merge;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        merge = new ReplayIssueMergeService(dao);
    }

    @Test
    void createsNewIssueAsOpenWithManualFieldsEmptyAndHistory() {
        merge.merge(workbook(row("K1", "new")), LocalDate.of(2026, 8, 5), new ReplayIssueOperator("u", "n"));
        Map<String, Object> current = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)).get(0);
        assertEquals("打开", current.get("issue_status"));
        assertEquals("", current.get("issue_type"));
        assertEquals(1L, dao.countHistory("K1"));
    }

    @Test
    void reopensPendingAndRetainsManualFieldsWhileRefreshingSourceFields() {
        ReplayIssueRow seed = lifecycle(row("K1", "old"), ReplayIssueStatus.PENDING_VERIFICATION, "代码问题", "analysis", "solution", "alice");
        dao.insertCurrent(seed);
        merge.merge(workbook(row("K1", "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());
        Map<String, Object> current = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)).get(0);
        assertEquals("重新打开", current.get("issue_status"));
        assertEquals("new", current.get("issue_description"));
        assertEquals("代码问题", current.get("issue_type"));
        assertEquals("analysis", current.get("initial_analysis"));
    }

    @Test
    void rejectsBlankAndDuplicateKeysBeforeTransaction() {
        assertThrows(IllegalArgumentException.class, () -> merge.merge(workbook(row("", "bad")), LocalDate.now(), ReplayIssueOperator.system()));
        assertThrows(IllegalArgumentException.class, () -> merge.merge(workbook(row("K1", "one"), row("K1", "two")), LocalDate.now(), ReplayIssueOperator.system()));
    }

    @Test
    void fixesMissingPendingAndReopensFixedWithNewImportDate() {
        dao.insertCurrent(lifecycle(row("MISSING", "old"), ReplayIssueStatus.PENDING_VERIFICATION, "代码问题", "a", "s", "alice"));
        dao.insertCurrent(lifecycle(row("FIXED", "old"), ReplayIssueStatus.FIXED, "代码问题", "a", "s", "alice"));
        merge.merge(workbook(row("FIXED", "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());
        List<Map<String, Object>> rows = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null));
        Map<String, Object> missing = rows.stream().filter(r -> "MISSING".equals(r.get("issue_key"))).findFirst().orElseThrow();
        Map<String, Object> fixed = rows.stream().filter(r -> "FIXED".equals(r.get("issue_key"))).findFirst().orElseThrow();
        assertEquals("已修复", missing.get("issue_status"));
        assertEquals("打开", fixed.get("issue_status"));
        assertEquals("new", fixed.get("issue_description"));
        assertEquals(1L, dao.countHistory("FIXED"));
    }

    private ReplayIssueExcelParser.ParsedWorkbook workbook(ReplayIssueRow... rows) {
        return new ReplayIssueExcelParser.ParsedWorkbook(List.of(rows), Map.of("公共组", rows.length), 0, rows.length);
    }

    private ReplayIssueRow row(String key, String description) {
        return new ReplayIssueRow(null, "公共组", "公共组", false, 1, "公共组", "1", "B", "6208", "交易", "交易级",
                "2026-08-05", "字段", description, "负责人", "", "", "", "", "", "", "S", "", "", "1", "I", key,
                "0", "", "", LocalDateTime.of(2026, 8, 5, 1, 0), ReplayIssueStatus.OPEN, LocalDate.of(2026, 8, 5), null, null, null);
    }

    private ReplayIssueRow lifecycle(ReplayIssueRow row, ReplayIssueStatus status, String type, String analysis,
                                     String solution, String user) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), type, analysis, solution, row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(),
                row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), status, row.importDate(), null, user, "Alice");
    }
}
