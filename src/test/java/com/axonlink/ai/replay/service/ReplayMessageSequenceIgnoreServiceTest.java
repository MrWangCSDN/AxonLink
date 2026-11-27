package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayMessageSequenceIgnoreDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayMessageSequenceIgnoreServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("dev", "开发", "dev-no");
    private static final ReplayConfigOperator REVIEWER = new ReplayConfigOperator("reviewer", "敬恒杰", "review-no");
    private JdbcTemplate jdbc;
    private ReplayMessageSequenceIgnoreService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        ReplayConfigTestFixtures.insertServiceMappings(jdbc, "0126", "S1&sop");
        jdbc.update("INSERT INTO dii_replay_transaction_person (domain,old_transaction_code,old_transaction_name,developer,developer_usernames,bank_owner,bank_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?,?)",
                "公共组", "0126", "查询", "开发", "dev", "敬恒杰", "review-no", Timestamp.valueOf(LocalDateTime.now()));
        service = new ReplayMessageSequenceIgnoreService(
                new ReplayMessageSequenceIgnoreDao(jdbc), new ReplayConfigPersonResolver(jdbc));
    }

    @Test
    void validatesCommaSeparatedSequences() {
        assertEquals(List.of("seq1", "seq2"), ReplayConfigValidation.requireMessageSequences(" seq1,seq2 "));
        assertThrows(IllegalArgumentException.class,
                () -> ReplayConfigValidation.requireMessageSequences("seq1,,seq2"));
        assertThrows(ReplayConfigConflictException.class,
                () -> ReplayConfigValidation.requireMessageSequences("seq1,seq1"));
        assertEquals("QUERY", ReplayConfigValidation.requireMessageSequenceCategory("QUERY"));
        assertEquals("ACCOUNTING", ReplayConfigValidation.requireMessageSequenceCategory("ACCOUNTING"));
    }

    @Test
    void createsRowsAtomicallyAndEnforcesCategorySequenceUniqueness() {
        assertEquals(3, service.create(new ReplayMessageSequenceIgnoreCreateRequest(
                "S1&sop", "QUERY", "a,b,c", "原因"), OPERATOR).size());
        assertEquals(1, service.create(new ReplayMessageSequenceIgnoreCreateRequest(
                "S1&sop", "ACCOUNTING", "a", "原因"), OPERATOR).size());
        assertThrows(ReplayConfigConflictException.class, () -> service.create(
                new ReplayMessageSequenceIgnoreCreateRequest("S9&sop", "QUERY", "a,new", "原因"), OPERATOR));
        assertEquals(4, service.list(10, 0, null, null, null, null,
                null, null, null, OPERATOR).total());
    }

    @Test
    void reviewUsesServiceCodeOwnerAndUpdateResetsApproval() {
        ReplayMessageSequenceIgnoreRow created = service.create(new ReplayMessageSequenceIgnoreCreateRequest(
                "S1&sop", "QUERY", "q1", "原因"), OPERATOR).get(0);
        assertThrows(ReplayConfigReviewForbiddenException.class, () -> service.review(created.id(), OPERATOR));
        ReplayMessageSequenceIgnoreRow reviewed = service.review(created.id(), REVIEWER);
        assertEquals(1, reviewed.reviewStatus());
        ReplayMessageSequenceIgnoreRow updated = service.update(created.id(),
                new ReplayMessageSequenceIgnoreUpdateRequest("S1&sop", "QUERY", "q2", "新原因"), OPERATOR);
        assertEquals(0, updated.reviewStatus());
    }

    @Test
    void combinesOwnershipAndEmptyReasonFilters() {
        ReplayMessageSequenceIgnoreRow row = service.create(new ReplayMessageSequenceIgnoreCreateRequest(
                "S1&sop", "QUERY", "empty", "原因"), OPERATOR).get(0);
        jdbc.update("UPDATE dii_replay_message_sequence_ignore SET ignore_reason=NULL WHERE id=?", row.id());
        assertEquals(0, service.list(10, 0, "QUERY", null, null, null,
                true, true, true, REVIEWER).total());
        jdbc.update("UPDATE dii_replay_transaction_person SET developer_usernames=? WHERE old_transaction_code=?",
                "dev,reviewer", "0126");
        assertEquals(1, service.list(10, 0, "QUERY", null, null, null,
                true, true, true, REVIEWER).total());
        assertEquals(0, service.list(10, 0, "QUERY", null, null, 1,
                true, null, null, REVIEWER).total());
    }

    @Test
    void reasonOnlyUpdateKeepsApprovalButBusinessUpdateResetsIt() {
        ReplayMessageSequenceIgnoreRow created = service.create(new ReplayMessageSequenceIgnoreCreateRequest(
                "S1&sop", "QUERY", "q1", "原因"), OPERATOR).get(0);
        jdbc.update("UPDATE dii_replay_message_sequence_ignore SET review_status=1 WHERE id=?", created.id());

        ReplayMessageSequenceIgnoreRow reasonUpdated = service.update(created.id(),
                new ReplayMessageSequenceIgnoreUpdateRequest("S1&sop", "QUERY", "q1", "新原因"), OPERATOR);
        assertEquals(1, reasonUpdated.reviewStatus());

        ReplayMessageSequenceIgnoreRow businessUpdated = service.update(created.id(),
                new ReplayMessageSequenceIgnoreUpdateRequest("S1&sop", "ACCOUNTING", "q1", "再次修改"), OPERATOR);
        assertEquals(0, businessUpdated.reviewStatus());
    }
}
