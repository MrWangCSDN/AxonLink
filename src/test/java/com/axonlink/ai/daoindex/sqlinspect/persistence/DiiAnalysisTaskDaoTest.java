package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiiAnalysisTaskDaoTest {

    private static final ThreadLocal<Set<String>> AGGREGATED_ROWS = ThreadLocal.withInitial(HashSet::new);

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DiiAnalysisTaskDao dao;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("task_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .build();
        jdbc = new JdbcTemplate(database);
        dao = new DiiAnalysisTaskDao(jdbc);
        jdbc.execute("SET COLLATION ENGLISH STRENGTH PRIMARY");
        jdbc.execute("""
                CREATE TABLE dii_analysis_task (
                    id BIGINT PRIMARY KEY, task_no VARCHAR(64), env VARCHAR(16), status VARCHAR(16),
                    total_sqls INT DEFAULT 42, analyzed_sqls INT DEFAULT 3,
                    failed_sqls INT DEFAULT 2, skipped_sqls INT DEFAULT 1,
                    trigger_type VARCHAR(16) DEFAULT 'SCHEDULED', owner VARCHAR(64) DEFAULT 'test-owner',
                    error_msg VARCHAR(1000), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, inspect_done_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE dii_analysis_item (
                    task_id BIGINT, explain_error VARCHAR(1000), llm_status VARCHAR(16),
                    llm_suggestions_json VARCHAR(1000), llm_pending TINYINT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE dii_sql_pool (
                    env VARCHAR(16), explain_error VARCHAR(1000), llm_status VARCHAR(16),
                    llm_suggestions_json VARCHAR(1000)
                )
                """);
    }

    @AfterEach
    void tearDown() {
        AGGREGATED_ROWS.remove();
        database.shutdown();
    }

    @Test
    void limitsAggregationToTheReturnedPage() {
        insertTask(4, "sit", "RUNNING");
        insertTask(3, "dev", "DONE");
        insertTask(2, "dev", "RUNNING");
        insertTask(1, "uat", "PENDING");
        jdbc.update("""
                INSERT INTO dii_analysis_item (task_id, explain_error) VALUES
                    (1, 'history'), (2, 'other task in page environment'), (3, 'returned task'),
                    (4, 'prior page'), (NULL, 'single analysis')
                """);
        jdbc.update("""
                INSERT INTO dii_sql_pool (env, explain_error) VALUES
                    ('DEV', 'returned environment'), ('sit', 'prior page'), ('uat', 'history')
                """);
        recordRowsReadByAggregates();

        List<Map<String, Object>> page = dao.list(1, 1);

        assertEquals(List.of(3L), taskIds(page));
        assertEquals(2L, ((Number) page.get(0).get("explain_err")).longValue());
        assertEquals(Set.of("item:3", "pool:DEV"), AGGREGATED_ROWS.get());
    }

    @Test
    void combinesTaskAndEnvironmentCountsUsingDatabaseCollation() {
        insertTask(5, "dev", "RUNNING");
        insertTask(4, "DEV", "PENDING");
        insertTask(3, "sit", "DONE");
        insertTask(2, null, "FAILED");
        insertTask(1, "", "FAILED");
        jdbc.update("""
                INSERT INTO dii_analysis_item
                    (task_id, explain_error, llm_status, llm_suggestions_json, llm_pending)
                VALUES
                    (5, 'explain error', 'DONE', '[1]', 0),
                    (5, '', 'DONE', '[]', 0),
                    (5, NULL, 'DONE', '', 0),
                    (5, NULL, 'DONE', NULL, 0),
                    (5, NULL, 'FAILED', '[1]', 0),
                    (5, NULL, 'PENDING', NULL, 1),
                    (5, NULL, 'SKIPPED', NULL, 1),
                    (4, '', 'FAILED', NULL, 0)
                """);
        jdbc.update("""
                INSERT INTO dii_sql_pool (env, explain_error, llm_status, llm_suggestions_json)
                VALUES
                    ('DEV', 'pool error', 'DONE', '[1]'),
                    ('dev', '', 'DONE', '[]'),
                    ('dev', NULL, 'DONE', ''),
                    ('dev', NULL, 'DONE', NULL),
                    ('dev', NULL, 'FAILED', '[1]'),
                    ('dev', NULL, 'PENDING', NULL),
                    ('sit', NULL, 'DONE', '[1]'),
                    (NULL, 'unassigned', 'PENDING', NULL),
                    ('', 'unassigned', 'PENDING', NULL),
                    ('uat', 'off-page', 'PENDING', NULL)
                """);

        List<Map<String, Object>> page = dao.list(5, 0);

        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), taskIds(page));
        assertEquals(List.of(2L, 2L, 2L, 3L, 6L), statistics(page.get(0)));
        assertEquals(List.of(1L, 1L, 2L, 1L, 6L), statistics(page.get(1)));
        assertEquals(List.of(0L, 1L, 0L, 0L, 1L), statistics(page.get(2)));
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), statistics(page.get(3)));
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), statistics(page.get(4)));
        assertEquals(Set.of(
                "id", "task_no", "env", "status", "total_sqls", "analyzed_sqls",
                "failed_sqls", "skipped_sqls", "trigger_type", "owner", "error_msg",
                "created_at", "updated_at", "inspect_done_at", "explain_err",
                "llm_done", "llm_failed", "llm_running", "pool_count"
        ), page.get(0).keySet());
        assertEquals(42, page.get(0).get("total_sqls"));
    }

    @Test
    void appliesTheSameFiltersBeforePaginationAndCounting() {
        insertTask(6, "dev", "DONE");
        insertTask(5, "sit", "RUNNING");
        insertTask(4, "dev", "PENDING");
        insertTask(3, "dev", "FAILED");
        insertTask(2, "dev", "RUNNING");
        insertTask(1, "sit", "DONE");

        assertEquals(List.of(4L), taskIds(dao.list(1, 0, " dev ", " running_or_pending ")));
        assertEquals(List.of(2L), taskIds(dao.list(1, 1, " dev ", " running_or_pending ")));
        assertEquals(2, dao.countAll(" dev ", " running_or_pending "));
        assertEquals(List.of(6L, 1L), taskIds(dao.list(10, 0, " ", " DONE ")));
        assertEquals(2, dao.countAll(" ", " DONE "));
        assertEquals(List.of(6L, 4L, 3L, 2L), taskIds(dao.list(10, 0, " dev ", " ")));
        assertEquals(4, dao.countAll(" dev ", " "));
    }

    @Test
    void clampsPageSizeAndNegativeOffset() {
        jdbc.update("""
                INSERT INTO dii_analysis_task (id, task_no, env, status)
                SELECT task_id, CONCAT('TASK-', task_id), 'dev', 'DONE'
                FROM SYSTEM_RANGE(1, 501) AS task_ids(task_id)
                """);

        assertEquals(List.of(501L), taskIds(dao.list(0, -4)));
        List<Long> capped = taskIds(dao.list(600, 0));
        assertEquals(500, capped.size());
        assertEquals(501L, capped.get(0));
        assertEquals(2L, capped.get(499));
        assertEquals(501, dao.countAll(null, null));
    }

    @Test
    void doesNotQueryDetailsForAnEmptyPage() {
        insertTask(1, "dev", "DONE");
        jdbc.execute("DROP TABLE dii_analysis_item");
        jdbc.execute("DROP TABLE dii_sql_pool");

        assertTrue(assertDoesNotThrow(() -> dao.list(1, 1)).isEmpty());
    }

    private void insertTask(long id, String env, String status) {
        jdbc.update("INSERT INTO dii_analysis_task (id, task_no, env, status) VALUES (?, ?, ?, ?)",
                id, "TASK-" + id, env, status);
    }

    private static List<Long> taskIds(List<Map<String, Object>> tasks) {
        return tasks.stream().map(task -> ((Number) task.get("id")).longValue()).toList();
    }

    private static List<Long> statistics(Map<String, Object> task) {
        return List.of("explain_err", "llm_done", "llm_failed", "llm_running", "pool_count")
                .stream().map(key -> ((Number) task.get(key)).longValue()).toList();
    }

    private void recordRowsReadByAggregates() {
        jdbc.execute("CREATE ALIAS record_detail_row FOR '" + getClass().getName() + ".recordDetailRow'");
        jdbc.execute("ALTER TABLE dii_analysis_item RENAME TO item_data");
        jdbc.execute("""
                CREATE VIEW dii_analysis_item AS
                SELECT task_id, record_detail_row('item:' || COALESCE(CAST(task_id AS VARCHAR), 'null'),
                           explain_error) AS explain_error, llm_status, llm_suggestions_json, llm_pending
                FROM item_data
                """);
        jdbc.execute("ALTER TABLE dii_sql_pool RENAME TO pool_data");
        jdbc.execute("""
                CREATE VIEW dii_sql_pool AS
                SELECT env, record_detail_row('pool:' || CAST(env AS VARCHAR), explain_error) AS explain_error,
                       llm_status, llm_suggestions_json
                FROM pool_data
                """);
    }

    // H2 invokes this only for rows whose explain error is evaluated by the query.
    public static String recordDetailRow(String row, String value) {
        AGGREGATED_ROWS.get().add(row);
        return value;
    }
}
