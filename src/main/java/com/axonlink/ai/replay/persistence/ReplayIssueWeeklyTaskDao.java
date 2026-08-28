package com.axonlink.ai.replay.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Persistence boundary for the replace-only current weekly-task batch set. */
@Repository
public class ReplayIssueWeeklyTaskDao {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ReplayIssueWeeklyTaskDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException("Replay weekly task requires a DataSource");
        }
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public List<String> currentBatchNames() {
        return jdbc.queryForList("SELECT batch_name FROM dii_replay_weekly_task_batch ORDER BY batch_name", String.class);
    }

    public List<String> availableBatchNames() {
        return jdbc.queryForList("SELECT DISTINCT TRIM(batch_name) AS batch_name "
                + "FROM dii_replay_issue_occurrence_batch "
                + "WHERE TRIM(COALESCE(batch_name,''))<>'' ORDER BY batch_name", String.class);
    }

    public void replaceBatchNames(List<String> batchNames) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM dii_replay_weekly_task_batch");
            if (!batchNames.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO dii_replay_weekly_task_batch(batch_name) VALUES (?)",
                        batchNames.stream().map(value -> new Object[]{value}).toList());
            }
        });
    }

    public long currentIssueCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(DISTINCT ob.replay_issue_id) "
                + "FROM dii_replay_issue_occurrence_batch ob "
                + "JOIN dii_replay_weekly_task_batch wt ON wt.batch_name=ob.batch_name", Long.class);
        return value == null ? 0L : value;
    }
}
