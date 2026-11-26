package com.axonlink.ai.replay.dbcompare.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercises the actual migration and current-read lock using an isolated loopback MySQL schema. */
@EnabledIfEnvironmentVariable(named = "REPORT_MYSQL_REGRESSION", matches = "true")
class ReplayDatabaseComparisonCodeAllocatorMysqlTest {
    @Test
    void migrationAndRepeatableReadAllocationPreserveOldIdentityAndPreventImplicitSiblings() throws Exception {
        String address = System.getenv().getOrDefault("REPORT_MYSQL_ADDRESS", "127.0.0.1:3306");
        assertTrue(address.matches("(?:127\\.0\\.0\\.1|localhost):[0-9]+"));
        String user = System.getenv().getOrDefault("REPORT_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("REPORT_MYSQL_PASSWORD", "");
        String database = "codex_test_multi_config_" + UUID.randomUUID().toString().replace("-", "");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        var admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://" + address + "/" + options, user, password));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var dataSource = new DriverManagerDataSource("jdbc:mysql://" + address + "/" + database + options, user, password);
            var jdbc = new JdbcTemplate(dataSource);
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                    new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                    new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
                    new ClassPathResource("db/daoindex/V91__replay_db_compare_custom_sql.sql")) .execute(dataSource);
            jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,domain_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES('base','legacy','领域',CURRENT_DATE,'test','test',NOW(),'test','test',NOW())");
            jdbc.update("INSERT INTO dii_replay_db_compare_version_table(version_id,source_registration_id,source_registration_version,schema_name,table_name,domain_name,registered_date) VALUES(1,1,0,'base','legacy','领域',CURRENT_DATE)");
            new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V99__replay_db_compare_configuration_identity.sql")).execute(dataSource);
            assertEquals("legacy", jdbc.queryForObject("SELECT source_table_name FROM dii_replay_db_compare_registration", String.class));
            assertEquals("legacy", jdbc.queryForObject("SELECT source_table_name FROM dii_replay_db_compare_version_table", String.class));
            assertEquals("", jdbc.queryForObject("SELECT configuration_name FROM dii_replay_db_compare_registration", String.class));

            var allocator = new ReplayDatabaseComparisonCodeAllocator(jdbc, mock(ReplayBaseMetadataService.class));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var gate = new CyclicBarrier(2);
            var firstCreates = java.util.stream.IntStream.range(0, 2).mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                try {
                    return transactions.execute(status -> {
                        assertFalse(allocator.hasSource("base", "first"));
                        try { gate.await(10, TimeUnit.SECONDS); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                        return allocator.allocate("base", "first", false);
                    });
                } catch (ReplayDatabaseComparisonGenerationException exception) {
                    assertEquals("DUPLICATE_REGISTRATION", exception.errorCode());
                    return "rejected";
                }
            })).toList();
            var outcomes = List.of(firstCreates.get(0).get(20, TimeUnit.SECONDS), firstCreates.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("first"::equals).count());
            assertEquals(1, outcomes.stream().filter("rejected"::equals).count());

            var additional = java.util.stream.IntStream.range(0, 4).mapToObj(index -> CompletableFuture.supplyAsync(() ->
                    transactions.execute(status -> allocator.allocate("base", "a", true)))).toList();
            var codes = new java.util.HashSet<String>();
            for (var future : additional) codes.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(Set.of("a", "a_1", "a_2", "a_3"), codes);
            assertEquals("a_4", transactions.execute(status -> allocator.allocate("base", "a", true)));
            jdbc.update("INSERT INTO dii_replay_database_issue(issue_id,table_name,domain_name,issue_level,difference_count,batch_no,batch_date,issue_status,data_snapshot) VALUES('a_5-amount','a_5','领域','字段级',1,'batch',CURRENT_DATE,'打开','{}')");
            jdbc.update("INSERT INTO dii_replay_database_issue_table_state VALUES('a_6',CURRENT_DATE,'batch')");
            jdbc.update("INSERT INTO dii_replay_db_compare_audit_event(registration_id,schema_name,table_name,operation,registration_version,change_count,operator_emp_no,operator_name,operated_at) VALUES(99,'base','a_7','CREATE',0,0,'test','test',NOW())");
            assertEquals("a_8", transactions.execute(status -> allocator.allocate("base", "a", true)));
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }
}
