package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayDatabaseTablePersonRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class ReplayDatabaseTablePersonDao {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ReplayDatabaseTablePersonDao(JdbcTemplate diiResultJdbcTemplate) {
        jdbc = diiResultJdbcTemplate;
        tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    public void replaceAll(List<ReplayDatabaseTablePersonRow> rows) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM dii_replay_database_table_person");
            jdbc.batchUpdate("INSERT INTO dii_replay_database_table_person (domain_name,table_name,table_comment,developer_display,developer_usernames,developer_emp_nos,technology_owner_display,technology_owner_usernames,technology_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    rows, 500, (ps, row) -> {
                        ps.setString(1,row.domain()); ps.setString(2,row.tableName()); ps.setString(3,row.tableComment());
                        ps.setString(4,row.developer()); ps.setString(5,row.developerUsernames());
                        ps.setString(6,row.developerEmpNos()); ps.setString(7,row.technologyOwner());
                        ps.setString(8,row.technologyOwnerUsernames()); ps.setString(9,row.technologyOwnerEmpNos());
                        ps.setTimestamp(10,Timestamp.valueOf(row.importedAt()));
                    });
        });
    }
    public ReplayDatabaseTablePersonRow findByTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) return null;
        var rows = jdbc.query("SELECT * FROM dii_replay_database_table_person WHERE table_name=?", this::map, normalize(tableName));
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<ReplayDatabaseTablePersonRow> list(String keyword,int limit,int offset) {
        return query(keyword,Math.max(1,Math.min(limit,200)),Math.max(0,offset));
    }
    public List<ReplayDatabaseTablePersonRow> listAll(String keyword) {
        return query(keyword,Integer.MAX_VALUE,0);
    }
    private List<ReplayDatabaseTablePersonRow> query(String keyword,int limit,int offset) {
        var args=new ArrayList<Object>();
        String where=where(keyword,args);
        args.add(limit);args.add(offset);
        return jdbc.query("SELECT * FROM dii_replay_database_table_person"+where+" ORDER BY domain_name,table_name LIMIT ? OFFSET ?",this::map,args.toArray());
    }
    public long count(String keyword) {
        var args=new ArrayList<Object>();
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_table_person"+where(keyword,args),Long.class,args.toArray());
        return count == null ? 0 : count;
    }
    private String where(String keyword,List<Object> args) {
        if(keyword==null||keyword.isBlank())return "";
        String value="%"+keyword.trim()+"%";
        for(int i=0;i<5;i++)args.add(value);
        return " WHERE (domain_name LIKE ? OR table_name LIKE ? OR table_comment LIKE ? OR developer_display LIKE ? OR technology_owner_display LIKE ?)";
    }
    private ReplayDatabaseTablePersonRow map(ResultSet rs,int row)throws SQLException {
        return new ReplayDatabaseTablePersonRow(rs.getLong("id"),rs.getString("domain_name"),rs.getString("table_name"),
                rs.getString("table_comment"),rs.getString("developer_display"),rs.getString("developer_usernames"),
                rs.getString("developer_emp_nos"),
                rs.getString("technology_owner_display"),rs.getString("technology_owner_usernames"),
                rs.getString("technology_owner_emp_nos"),rs.getTimestamp("imported_at").toLocalDateTime());
    }
    public static String normalize(String name) {return name==null?"":name.trim().toLowerCase(Locale.ROOT);}
}
