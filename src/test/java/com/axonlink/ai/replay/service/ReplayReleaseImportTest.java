package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayReleaseImportTest {
    private ReplayIssueDao dao;
    private ReplayIssueImportService service;
    private JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc=ReplayIssueTestFixtures.newJdbc();ReplayIssueTestFixtures.createSchema(jdbc);
        dao=new ReplayIssueDao(jdbc);
        service=new ReplayIssueImportService(new ReplayIssueExcelParser(),dao,
                Clock.fixed(Instant.parse("2026-10-15T02:00:00Z"),ZoneId.of("Asia/Shanghai")),new ReplayIssueImportGate());
    }
    @Test void explicitReleaseAtProtectsOldVersionAndRetainsExistingManualSaveSemantics() throws Exception {
        checkBoundary("2026-08-19T12:00:01","2026-08-19 12:00:00",ReplayIssueStatus.PENDING_VERIFICATION);
    }
    @Test void saveExactlyAtReleaseCanReopen() throws Exception {
        checkBoundary("2026-08-19T12:00:00","2026-08-19 12:00:00",ReplayIssueStatus.REOPENED);
    }
    @Test void missingParameterUsesBatchPreviousNoonInsteadOfSystemDate() throws Exception {
        checkBoundary("2026-08-19T12:00:01",null,ReplayIssueStatus.PENDING_VERIFICATION);
    }
    @Test void laterReleaseOnSameBatchDayAllowsVerification() throws Exception {
        checkBoundary("2026-08-20T09:00:00","2026-08-20 10:00:00",ReplayIssueStatus.REOPENED);
    }
    private void checkBoundary(String saveAt,String releaseAt,ReplayIssueStatus expected) throws Exception {
        service.importFile(ReplayIssueTestFixtures.validWorkbook(1));
        var row=dao.list(new ReplayIssueQuery(10,0,null,null,null,null,null)).get(0);
        long id=((Number)row.get("id")).longValue();String key=(String)row.get("issue_key");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证',remark='保留备注' WHERE id=?",id);
        dao.insertHistory(id,key,"人工保存",LocalDateTime.parse(saveAt),ReplayIssueOperator.system(),
                LocalDate.of(2026,8,19),null,null,null,"{}","{}",null);
        service=new ReplayIssueImportService(new ReplayIssueExcelParser(),dao,
                Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"),ZoneId.of("Asia/Shanghai")),new ReplayIssueImportGate());
        service.importFile(ReplayIssueTestFixtures.validWorkbook(1),ReplayIssueImportMode.QUERY,releaseAt);
        assertEquals(expected,dao.findCurrentByIdForUpdate(id).issueStatus());
        assertEquals("保留备注",dao.findCurrentByIdForUpdate(id).remark());
    }
    @Test void malformedReleaseHasNoBusinessWrites() {
        assertThrows(IllegalArgumentException.class,()->service.importFile(
                ReplayIssueTestFixtures.validWorkbook(1),ReplayIssueImportMode.QUERY,"2026-02-30 12:00:00"));
        assertTrue(dao.listImportRounds().isEmpty());
    }
}
