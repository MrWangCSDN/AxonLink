package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayIssueUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueEditServiceTest {
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayIssueEditService service;
    private long issueId;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        createUsers(jdbc);
        dao = new ReplayIssueDao(jdbc);
        issueId = seedCurrent(dao);
        service = new ReplayIssueEditService(dao, new SysUserDao(jdbc));
    }

    @Test
    void rejectsEditingAnIssueThatIsAlreadyFixed() {
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '已修复' WHERE id = ?", issueId);
        ReplayIssueRow fixed = dao.findCurrentByIdForUpdate(issueId);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.update(issueId,
                new ReplayIssueUpdateRequest(ReplayIssueStatus.ANALYZING, "代码问题", "analysis", "solution", null),
                new ReplayIssueOperator("editor", "编辑人")));

        assertEquals("已修复的问题不可编辑", exception.getMessage());
        assertEquals(0L, dao.countHistory(fixed.issueKey()));
        assertEquals(ReplayIssueStatus.FIXED, dao.findCurrentByIdForUpdate(issueId).issueStatus());
    }

    @Test
    void updatesSixFieldsAndWritesOneHistoryEvent() {
        ReplayIssueRow updated = service.update(issueId,
                new ReplayIssueUpdateRequest(ReplayIssueStatus.PENDING_VERIFICATION, "代码问题", "analysis", "solution", "sunhy1", "需要联调"),
                new ReplayIssueOperator("editor", "编辑人"));

        assertEquals(ReplayIssueStatus.PENDING_VERIFICATION, updated.issueStatus());
        assertEquals("代码问题", updated.issueType());
        assertEquals("analysis", updated.initialAnalysis());
        assertEquals("solution", updated.finalSolution());
        assertEquals("sunhy1", updated.cooperationPersonUsername());
        assertEquals("孙海英", updated.cooperationPersonRealName());
        assertEquals("需要联调", updated.remark());
        assertEquals(1L, dao.countHistory(updated.issueKey()));
    }

    @Test
    void preservesCurrentStatusWhenSavingOnlyTheRemark() {
        ReplayIssueRow updated = service.update(issueId,
                new ReplayIssueUpdateRequest(null, "代码问题", "", "", null, "111"),
                new ReplayIssueOperator("editor", "编辑人"));

        assertEquals(ReplayIssueStatus.OPEN, updated.issueStatus());
        assertEquals("111", updated.remark());
        assertEquals(1L, dao.countHistory(updated.issueKey()));
    }

    @Test
    void allowsChangingADeferredIssueBackToOpenWithReasonableDifferenceType() {
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '延后修复' WHERE id = ?", issueId);

        ReplayIssueRow updated = service.update(issueId,
                new ReplayIssueUpdateRequest(ReplayIssueStatus.OPEN, "合理差异", "analysis", "solution", null),
                new ReplayIssueOperator("editor", "编辑人"));

        assertEquals(ReplayIssueStatus.OPEN, updated.issueStatus());
        assertEquals("合理差异", updated.issueType());
        assertEquals(1L, dao.countHistory(updated.issueKey()));
    }

    @Test
    void associatesMultipleManualEditsWithTheLatestFormalRound() {
        long roundId = dao.insertImportRound("20260811-001", LocalDateTime.of(2026, 8, 11, 9, 0),
                ReplayIssueOperator.system(), 1);
        ReplayIssueRow issue = dao.findCurrentByIdForUpdate(issueId);
        dao.insertIssueRound(roundId, issueId, issue.issueKey(), true, ReplayIssueStatus.OPEN,
                ReplayIssueStatus.OPEN, "保持", issue.sourceSheet(), issue.rowOrder() + 1,
                LocalDateTime.of(2026, 8, 11, 9, 0));

        service.update(issueId, new ReplayIssueUpdateRequest(ReplayIssueStatus.DEFERRED,
                "代码问题", "a", "s", null, "first"), new ReplayIssueOperator("editor", "编辑人"));
        service.update(issueId, new ReplayIssueUpdateRequest(ReplayIssueStatus.PENDING_VERIFICATION,
                "代码问题", "a", "s", null, "second"), new ReplayIssueOperator("editor", "编辑人"));

        var history = dao.findHistoryByIssueId(issueId, 10);
        assertEquals(2, history.size());
        assertEquals(roundId, history.get(0).contextRoundId());
        assertEquals(roundId, history.get(1).contextRoundId());
        assertEquals(ReplayIssueStatus.PENDING_VERIFICATION, history.get(0).issueStatus());
    }

    @Test
    void associatesManualEditsWithTheLatestRoundForThatIssue() {
        ReplayIssueRow issue = dao.findCurrentByIdForUpdate(issueId);
        long firstRoundId = dao.insertImportRound("20260811-001", LocalDateTime.of(2026, 8, 11, 9, 0),
                ReplayIssueOperator.system(), 1);
        dao.insertIssueRound(firstRoundId, issueId, issue.issueKey(), true, ReplayIssueStatus.OPEN,
                ReplayIssueStatus.OPEN, "保持", issue.sourceSheet(), issue.rowOrder() + 1,
                LocalDateTime.of(2026, 8, 11, 9, 0));
        long secondRoundId = dao.insertImportRound("20260811-002", LocalDateTime.of(2026, 8, 11, 10, 0),
                ReplayIssueOperator.system(), 1);

        service.update(issueId, new ReplayIssueUpdateRequest(null,
                "代码问题", "a", "s", null, "first"), new ReplayIssueOperator("editor", "编辑人"));

        assertEquals(firstRoundId, dao.findHistoryByIssueId(issueId, 10).get(0).contextRoundId());

        dao.insertIssueRound(secondRoundId, issueId, issue.issueKey(), true, ReplayIssueStatus.OPEN,
                ReplayIssueStatus.OPEN, "保持", issue.sourceSheet(), issue.rowOrder() + 1,
                LocalDateTime.of(2026, 8, 11, 10, 0));
        service.update(issueId, new ReplayIssueUpdateRequest(null,
                "代码问题", "a", "s", null, "second"), new ReplayIssueOperator("editor", "编辑人"));

        assertEquals(secondRoundId, dao.findHistoryByIssueId(issueId, 10).get(0).contextRoundId());
    }

    @Test
    void rejectsSystemStatusesUnknownTypesAndUnknownUsers() {
        assertThrows(IllegalArgumentException.class, () -> service.update(issueId,
                new ReplayIssueUpdateRequest(ReplayIssueStatus.ANALYZING, "错误类型", "", "", null),
                new ReplayIssueOperator("editor", "编辑人")));
        assertThrows(IllegalArgumentException.class, () -> service.update(issueId,
                new ReplayIssueUpdateRequest(ReplayIssueStatus.ANALYZING, "", "", "", "missing"),
                new ReplayIssueOperator("editor", "编辑人")));
    }

    @Test
    void historyFailureRollsBackAllFiveFields() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        createUsers(jdbc);
        ReplayIssueDao failingDao = new ReplayIssueDao(jdbc) {
            @Override
            public void insertHistoryForRound(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                                              ReplayIssueOperator operator, LocalDate importDate, String coverageRound,
                                              String sourceSheet, Integer sourceRow, String beforeSnapshot,
                                              String afterSnapshot, String incomingSnapshot, Long contextRoundId) {
                throw new IllegalStateException("history unavailable");
            }
        };
        long id = seedCurrent(failingDao);
        ReplayIssueEditService failingService = new ReplayIssueEditService(failingDao, new SysUserDao(jdbc));

        assertThrows(IllegalStateException.class, () -> failingService.update(id,
                new ReplayIssueUpdateRequest(ReplayIssueStatus.PENDING_VERIFICATION, "代码问题", "analysis", "solution", "sunhy1"),
                new ReplayIssueOperator("editor", "编辑人")));
        ReplayIssueRow unchanged = failingDao.findCurrentByIdForUpdate(id);
        assertEquals(ReplayIssueStatus.OPEN, unchanged.issueStatus());
        assertEquals("数据差异", unchanged.issueType());
    }

    private static void createUsers(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128), real_name VARCHAR(128), emp_no VARCHAR(64), email VARCHAR(128), phone VARCHAR(64), department VARCHAR(128), status INT, remark VARCHAR(255), creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.update("INSERT INTO ccbs_ai_sys_user (username, real_name, status) VALUES (?,?,?)", "sunhy1", "孙海英", 1);
    }

    private static long seedCurrent(ReplayIssueDao targetDao) {
        targetDao.replaceAll(java.util.List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")),
                LocalDateTime.of(2026, 8, 5, 9, 0));
        return ((Number) targetDao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(1, 0, null, null, null, null, null))
                .get(0).get("id")).longValue();
    }
}
