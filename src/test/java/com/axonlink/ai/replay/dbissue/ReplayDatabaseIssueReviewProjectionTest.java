package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

public class ReplayDatabaseIssueReviewProjectionTest {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    // H2 lacks MySQL's JSON helpers; these aliases let the actual migration execute against fixture rows.
    public static int jsonValid(String value){try{return JSON.readTree(value)==null?0:1;}catch(Exception e){return 0;}}
    public static String jsonExtract(String value,String path)throws Exception{var node=JSON.readTree(value).get(path.substring(2));return node==null?null:node.toString();}
    public static String jsonUnquote(String value)throws Exception{return value==null?null:JSON.readTree(value).asText();}
    public static String jsonType(String value)throws Exception{return value==null?null:JSON.readTree(value).isNull()?"NULL":"STRING";}
    public static void installJsonHelpers(org.springframework.jdbc.core.JdbcTemplate jdbc){
        String type=ReplayDatabaseIssueReviewProjectionTest.class.getName();
        jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_VALID FOR '"+type+".jsonValid'");
        jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR '"+type+".jsonExtract'");
        jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR '"+type+".jsonUnquote'");
        jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_TYPE FOR '"+type+".jsonType'");
    }
    @Test void backfillsExistingReviewDataWithoutConvertingUnexplainedApprovalsToPending(){
        var jdbc=ReplayIssueTestFixtures.newJdbc();installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V77__replay_database_issues.sql")).execute(jdbc.getDataSource());
        String[] snapshots={"{\"reviewStatus\":\"已审核\",\"reviewReason\":\"\"}","{\"reviewStatus\":\"待审核\",\"reviewReason\":\"\"}","{\"reviewStatus\":\"已审核\",\"reviewReason\":\"旧审核原因\"}","{}","invalid JSON","{\"reviewStatus\":\"已审核\",\"reviewReason\":\"null\"}","{\"reviewStatus\":\"已审核\",\"reviewReason\":null}"};
        for(int i=0;i<snapshots.length;i++)jdbc.update("INSERT INTO dii_replay_database_issue(issue_id,table_name,domain_name,issue_level,difference_count,batch_no,batch_date,issue_status,data_snapshot) VALUES (?,?,?,?,1,'DB20261012-01','2026-10-12','无需处理',?)","issue-"+i,"sample_table","领域","仅ccbs",snapshots[i]);
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V78__replay_database_issue_review_projection.sql")).execute(jdbc.getDataSource());
        var rows=jdbc.queryForList("SELECT review_status,review_reason,data_snapshot FROM dii_replay_database_issue ORDER BY id");
        assertEquals("已审核",rows.get(0).get("review_status"));assertEquals("",rows.get(0).get("review_reason"));
        assertEquals("待审核",rows.get(1).get("review_status"));assertEquals("已审核",rows.get(2).get("review_status"));assertEquals("旧审核原因",rows.get(2).get("review_reason"));
        assertEquals("",rows.get(3).get("review_status"));assertEquals("",rows.get(4).get("review_status"));
        assertEquals("null",rows.get(5).get("review_reason"));assertEquals("",rows.get(6).get("review_reason"));
        for(int i=0;i<snapshots.length;i++)assertEquals(snapshots[i],rows.get(i).get("data_snapshot"));
    }
}
