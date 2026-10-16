package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbissue.ReplayDatabaseIssueDao;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseTablePersonMappingTest {
    @Test void currentRegistrationAndIssueMetadataUsePersonnelListAndClearWhenRemoved() {
        var jdbc=new JdbcTemplate(new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("table_map_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1").build());
        com.axonlink.ai.replay.dbissue.ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"),
                new ClassPathResource("db/daoindex/V70__replay_db_compare_scope.sql"),
                new ClassPathResource("db/daoindex/V71__replay_db_compare_ordering_primary_key_snapshot.sql"),
                new ClassPathResource("db/daoindex/V74__replay_db_compare_partition_num.sql"),
                new ClassPathResource("db/daoindex/V75__replay_db_compare_sample_limit.sql"),
                new ClassPathResource("db/daoindex/V81__replay_db_compare_comparison_key.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),
                new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
                new ClassPathResource("db/daoindex/V78__replay_database_issue_review_projection.sql"),
                new ClassPathResource("db/daoindex/V79__replay_database_issue_repair_date_projection.sql"),
                new ClassPathResource("db/daoindex/V80__replay_database_issue_collaborator_projection.sql"))
                .execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,table_comment,domain_name,reviser_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES ('public','KOBA_A','旧中文名','旧领域','旧修订人',CURRENT_DATE,'sys','系统',CURRENT_TIMESTAMP,'sys','系统',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,table_comment,developer_display,developer_usernames,technology_owner_display,technology_owner_emp_nos,imported_at) VALUES ('koba_a','贷款组','新中文名','张三(dev1)','dev1','王五(owner1)','2001',CURRENT_TIMESTAMP)");
        var comparison=new ReplayDatabaseComparisonDao(jdbc);
        var issue=new ReplayDatabaseIssueDao(jdbc);
        var item=comparison.search(ReplayDbCompareQuery.empty(0,50)).items().get(0);
        assertEquals("贷款组",item.domainName());
        assertEquals("张三(dev1)",item.reviserName());
        assertEquals("王五(owner1)",item.groupOwnerName());
        assertEquals("张三(dev1)",issue.metadata("KOBA_A").get("owner"));
        assertEquals("2001",issue.technologyOwners(List.of(new ReplayDatabaseIssueDao.TableIdentity("koba_a"))).get(0).identity());
        jdbc.update("INSERT INTO dii_replay_database_issue(issue_id,table_name,domain_name,issue_level,difference_count,batch_no,batch_date,issue_status,data_snapshot) VALUES ('koba_a-ccbs','KOBA_A','旧领域','仅ccbs',3,'DB20261016-01',CURRENT_DATE,'新建','{}')");
        var issueRow=((List<java.util.Map<String,Object>>)issue.list(java.util.Map.of("limit",50)).get("items")).get(0);
        assertEquals("贷款组",issueRow.get("domain"));
        assertEquals("张三(dev1)",issueRow.get("owner"));
        jdbc.update("DELETE FROM dii_replay_database_table_person");
        item=comparison.search(ReplayDbCompareQuery.empty(0,50)).items().get(0);
        assertNull(item.domainName());
        assertEquals("",item.reviserName());
        assertTrue(issue.metadata("koba_a").isEmpty());
        issueRow=((List<java.util.Map<String,Object>>)issue.list(java.util.Map.of("limit",50)).get("items")).get(0);
        assertEquals("",issueRow.get("domain"));
        assertEquals("",issueRow.get("owner"));
    }
}
