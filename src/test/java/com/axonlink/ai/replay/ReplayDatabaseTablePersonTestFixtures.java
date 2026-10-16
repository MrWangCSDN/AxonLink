package com.axonlink.ai.replay;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Locale;

/** Keeps older tests' registration fixtures explicit under the new personnel source. */
public final class ReplayDatabaseTablePersonTestFixtures {
    private ReplayDatabaseTablePersonTestFixtures() {}

    public static void sync(JdbcTemplate jdbc, String tableName) {
        String key=tableName.toLowerCase(Locale.ROOT);
        var rows=jdbc.queryForList("SELECT domain_name,table_comment,reviser_name,reviser_username,reviser_emp_no,group_owner_name,group_owner_emp_no FROM dii_replay_db_compare_registration WHERE LOWER(table_name)=? AND deleted=0",key);
        jdbc.update("DELETE FROM dii_replay_database_table_person WHERE table_name=?",key);
        if(rows.size()!=1)return;
        var row=rows.get(0);
        String developerName=value(row.get("reviser_name"));
        String developerUsername=value(row.get("reviser_username"));
        String developerEmpNo=value(row.get("reviser_emp_no"));
        String technologyName=value(row.get("group_owner_name"));
        String technologyEmpNo=value(row.get("group_owner_emp_no"));
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,table_comment,developer_display,developer_usernames,developer_emp_nos,technology_owner_display,technology_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                key,value(row.get("domain_name")),value(row.get("table_comment")),display(developerName,!developerUsername.isBlank()?developerUsername:developerEmpNo),
                developerUsername,developerEmpNo,display(technologyName,technologyEmpNo),technologyEmpNo);
    }
    private static String value(Object value){return value==null?"":value.toString().trim();}
    private static String display(String name,String id){return name.isBlank()?id:id.isBlank()?name:name+"("+id+")";}
}
