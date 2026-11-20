package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayOneSideErrorCodeIgnoreDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayOneSideErrorCodeIgnoreServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("dev", "开发", "dev-no");
    private static final ReplayConfigOperator REVIEWER = new ReplayConfigOperator("reviewer", "敬恒杰", "review-no");
    private JdbcTemplate jdbc;
    private ReplayOneSideErrorCodeIgnoreService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO znzx_service (application_name,esf_service_code,flow_id,tran_code,function_desc,group_name) VALUES (?,?,?,?,?,?)",
                "app", "S1", "flow", "0126", "查询", "公共组");
        jdbc.update("INSERT INTO dii_replay_transaction_person (domain,old_transaction_code,old_transaction_name,developer,developer_usernames,bank_owner,bank_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?,?)",
                "公共组", "0126", "查询", "开发", "dev", "敬恒杰", "review-no", Timestamp.valueOf(LocalDateTime.now()));
        service = new ReplayOneSideErrorCodeIgnoreService(
                new ReplayOneSideErrorCodeIgnoreDao(jdbc), new ReplayConfigPersonResolver(jdbc));
    }

    @Test
    void createsCommaSeparatedCodesWithCategoryAndOwnershipUniqueness() {
        assertEquals(3, service.create(new ReplayOneSideErrorCodeIgnoreCreateRequest(
                "S1&sop", "QUERY", "528", "E001,E002, E003", "原因"), OPERATOR).size());
        assertEquals(1, service.create(new ReplayOneSideErrorCodeIgnoreCreateRequest(
                "S1&sop", "ACCOUNTING", "528", "E001", "原因"), OPERATOR).size());
        assertThrows(ReplayConfigConflictException.class, () -> service.create(
                new ReplayOneSideErrorCodeIgnoreCreateRequest("S9&sop", "QUERY", "528", "E001,E004", "原因"), OPERATOR));
    }

    @Test
    void reasonOnlyUpdateKeepsApprovalButBusinessUpdateResetsIt() {
        ReplayOneSideErrorCodeIgnoreRow created = service.create(new ReplayOneSideErrorCodeIgnoreCreateRequest(
                "S1&sop", "QUERY", "528", "E010", "原因"), OPERATOR).get(0);
        jdbc.update("UPDATE dii_replay_one_side_error_code_ignore SET review_status=1 WHERE id=?", created.id());
        ReplayOneSideErrorCodeIgnoreRow reasonUpdated = service.update(created.id(),
                new ReplayOneSideErrorCodeIgnoreUpdateRequest("S1&sop", "QUERY", "528", "E010", "新原因"), OPERATOR);
        assertEquals(1, reasonUpdated.reviewStatus());
        ReplayOneSideErrorCodeIgnoreRow businessUpdated = service.update(created.id(),
                new ReplayOneSideErrorCodeIgnoreUpdateRequest("S1&sop", "QUERY", "ccbs", "E010", "再次修改"), OPERATOR);
        assertEquals(0, businessUpdated.reviewStatus());
    }

    @Test
    void listsAllReviewStatusesWhenReviewStatusIsNotProvided() {
        service.create(new ReplayOneSideErrorCodeIgnoreCreateRequest(
                "S1&sop", "QUERY", "528", "E015", "原因"), OPERATOR);

        assertEquals(1, service.list(10, 0, null, null, null, null,
                null, null, null, null, OPERATOR).total());
    }

    @Test
    void exportsOnlyReviewedCodesInCategoryOwnershipGroups() {
        List<ReplayOneSideErrorCodeIgnoreRow> rows = service.create(new ReplayOneSideErrorCodeIgnoreCreateRequest(
                "S1&sop", "QUERY", "528", "E020,E021", "原因"), OPERATOR);
        rows.forEach(row -> jdbc.update("UPDATE dii_replay_one_side_error_code_ignore SET review_status=1 WHERE id=?", row.id()));
        assertEquals(2, service.list(10, 0, "QUERY", null, "528", null,
                null, null, null, null, OPERATOR).total());
    }
}
