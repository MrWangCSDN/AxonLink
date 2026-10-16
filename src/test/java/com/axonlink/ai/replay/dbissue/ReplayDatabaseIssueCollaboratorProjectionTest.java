package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseIssueCollaboratorProjectionTest {
    @Test void backfillsOriginalDisplayValuesWithoutRebuildingIdentityOrChangingSnapshots(){
        var jdbc=ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
            new ClassPathResource("db/daoindex/V78__replay_database_issue_review_projection.sql"),
            new ClassPathResource("db/daoindex/V79__replay_database_issue_repair_date_projection.sql")).execute(jdbc.getDataSource());
        String[] snapshots={"{\"cooperationPerson\":\"旧名字(editor)\",\"cooperationPersonUsername\":\"editor\",\"cooperationPersonName\":\"新名字\"}",
            "{\"cooperationPerson\":\"\"}","{}","{\"cooperationPerson\":null}","{\"cooperationPerson\":\"null\"}","invalid JSON",
            "{\"cooperationPerson\":\" 旧名字(c-old) \"}","{\"cooperationPersonUsername\":\"editor\",\"cooperationPersonName\":\"新名字\"}"};
        for(int i=0;i<snapshots.length;i++)jdbc.update("INSERT INTO dii_replay_database_issue(issue_id,table_name,domain_name,issue_level,difference_count,batch_no,batch_date,issue_status,data_snapshot) VALUES (?,?,?,?,1,'DB20261012-01','2026-10-12','打开',?)",
            "issue-"+i,"sample_table","领域","仅ccbs",snapshots[i]);
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V80__replay_database_issue_collaborator_projection.sql")).execute(jdbc.getDataSource());
        var rows=jdbc.queryForList("SELECT cooperation_person_display,data_snapshot FROM dii_replay_database_issue ORDER BY id");
        assertEquals(List.of("旧名字(editor)","","","","null",""," 旧名字(c-old) ",""),rows.stream().map(row->row.get("cooperation_person_display")).toList());
        for(int i=0;i<snapshots.length;i++)assertEquals(snapshots[i],rows.get(i).get("data_snapshot"));
    }
}
