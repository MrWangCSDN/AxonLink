package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayIssueTypeNormalizationMigrationTest {

    @Test
    void replacesLegacyTypeInCurrentRowsHistoryAndRoundSnapshots() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO dii_replay_issue (source_sheet,group_name,is_sandbox,row_order,domain,issue_id,"
                        + "issue_key,issue_type,imported_at) VALUES ('公共组','公共组',0,1,'公共组','I-1','K-1',?,CURRENT_TIMESTAMP)",
                "规则差异问题");
        long issueId = jdbc.queryForObject("SELECT id FROM dii_replay_issue WHERE issue_key='K-1'", Long.class);
        jdbc.update("INSERT INTO dii_replay_issue_history (replay_issue_id,issue_key,operation_type,operation_at,"
                        + "issue_type,before_snapshot,after_snapshot,incoming_snapshot) VALUES (?,?,?,CURRENT_TIMESTAMP,?,?,?,?)",
                issueId, "K-1", "人工保存", "规则差异问题",
                snapshot("规则差异问题"), snapshot("规则差异问题"), snapshot("规则差异问题"));
        jdbc.update("INSERT INTO dii_replay_import_round (round_code,imported_at) VALUES ('B-1',CURRENT_TIMESTAMP)");
        long roundId = jdbc.queryForObject("SELECT id FROM dii_replay_import_round WHERE round_code='B-1'", Long.class);
        jdbc.update("INSERT INTO dii_replay_issue_round (round_id,replay_issue_id,issue_key,appeared,status_after,"
                        + "action_type,recorded_at,incoming_snapshot,batch_name) VALUES (?,?,?,1,'打开','导入',CURRENT_TIMESTAMP,?,?)",
                roundId, issueId, "K-1", snapshot("规则差异问题"), "B-1");

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V55__normalize_replay_issue_rule_difference_type.sql"))
                .execute(jdbc.getDataSource());

        assertEquals("规则性差异问题", jdbc.queryForObject(
                "SELECT issue_type FROM dii_replay_issue WHERE id=?", String.class, issueId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_issue_history WHERE issue_type='规则差异问题' "
                        + "OR before_snapshot LIKE '%规则差异问题%' OR after_snapshot LIKE '%规则差异问题%' "
                        + "OR incoming_snapshot LIKE '%规则差异问题%'", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_issue_round WHERE incoming_snapshot LIKE '%规则差异问题%'", Long.class));
    }

    private static String snapshot(String issueType) {
        return "{\"issueType\":\"" + issueType + "\",\"issueDescription\":\"保留内容\"}";
    }
}
