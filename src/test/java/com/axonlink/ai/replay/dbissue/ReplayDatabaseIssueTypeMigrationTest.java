package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseIssueTypeMigrationTest {
    @Test
    void migratesCurrentRowsAndSnapshotsButLeavesAuditHistoryUntouched() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"))
                .execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO dii_replay_database_issue (issue_id,table_name,table_comment,domain_name,owner_display,issue_level,field_name,difference_count,batch_no,batch_date,issue_status,issue_type,version,data_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "legacy-code", "legacy_table", "", "", "", "字段级", "amount", 1L, "DB20261012-01", java.sql.Date.valueOf("2026-10-12"), "打开", "代码问题", 0L, "{\"issueType\":\"代码问题\"}");
        long id = jdbc.queryForObject("SELECT id FROM dii_replay_database_issue WHERE issue_id='legacy-code'", Long.class);
        jdbc.update("INSERT INTO dii_replay_database_issue_history (issue_id,batch_no,operation_type,operation_at,import_event,before_snapshot,after_snapshot) VALUES (?,?,?,?,?,?,?)",
                id, "DB20261012-01", "旧版导入", "2026-10-12 10:00:00", true, null, "{\"issueType\":\"代码问题\"}");

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V84__normalize_replay_database_issue_types.sql"))
                .execute(jdbc.getDataSource());

        assertEquals("修改代码", jdbc.queryForObject("SELECT issue_type FROM dii_replay_database_issue WHERE id=?", String.class, id));
        assertTrue(jdbc.queryForObject("SELECT data_snapshot FROM dii_replay_database_issue WHERE id=?", String.class, id).contains("修改代码"));
        assertTrue(jdbc.queryForObject("SELECT after_snapshot FROM dii_replay_database_issue_history WHERE issue_id=?", String.class, id).contains("代码问题"));
    }
}
