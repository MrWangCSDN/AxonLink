package com.axonlink.ai.replay.dbissue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class ReplayDatabaseIssueDao {
    private record HeaderFilter(String field,String parameter,String column,boolean numeric) {
        String columnSql(){return field.equals("domain")?DOMAIN_SQL:field.equals("owner")?OWNER_SQL:"i."+column;}
        String valueSql(){return field.equals("reviewStatus")?REVIEW_DISPLAY_SQL:numeric?"CONCAT('',"+columnSql()+")":"COALESCE("+columnSql()+",'')";}
    }
    private static final String REVIEW_DISPLAY_SQL="CASE WHEN i.review_status='待审核' THEN '待审核' WHEN i.review_status='已审核' AND TRIM(COALESCE(i.review_reason,''))='' THEN '已审核（未填写原因）' WHEN i.review_status='已审核' THEN '已审核（已填写原因）' ELSE '' END";
    private static final List<HeaderFilter> HEADER_FILTERS=List.of(
            new HeaderFilter("issueId","issueIds","issue_id",false),
            new HeaderFilter("tableName","tableNames","table_name",false),
            new HeaderFilter("tableComment","tableComments","table_comment",false),
            new HeaderFilter("domain","domains","domain_name",false),
            new HeaderFilter("owner","owners","owner_display",false),
            new HeaderFilter("cooperationPerson","cooperationPersons","cooperation_person_display",false),
            new HeaderFilter("issueLevel","issueLevels","issue_level",false),
            new HeaderFilter("fieldName","fieldNames","field_name",false),
            new HeaderFilter("differenceCount","differenceCounts","difference_count",true),
            new HeaderFilter("defectRepairDate","defectRepairDates","defect_repair_date",false),
            new HeaderFilter("batchNo","batchNos","batch_no",false),
            new HeaderFilter("issueStatus","issueStatuses","issue_status",false),
            new HeaderFilter("reviewStatus","reviewStatuses","review_status",false),
            new HeaderFilter("issueType","issueTypes","issue_type",false));
    public record TableIdentity(String tableName) {
        static TableIdentity of(Map<String,Object> row){return new TableIdentity(text(row.get("tableName")).toLowerCase(Locale.ROOT));}
    }
    public record TechnologyOwnerRegistration(TableIdentity table,String identity,String name,String domain,String owner,String tableComment) {}
    private static final int MAX_FILTER_CANDIDATES=500;
    private static final String DOMAIN_SQL="COALESCE(registration.domain_name,'')";
    private static final String OWNER_SQL="COALESCE(registration.developer_display,'')";
    private static final String ISSUE_FROM=" FROM dii_replay_database_issue i LEFT JOIN dii_replay_database_table_person registration ON registration.table_name=LOWER(i.table_name)";
    // Automatic repairs retain the last actual batch_no, so it also covers rows without import history.
    private static final String OCCURRENCES_SQL="SELECT issue_id AS issue_row_id,batch_no FROM dii_replay_database_issue_history WHERE import_event=1 AND incoming_snapshot IS NOT NULL UNION SELECT id AS issue_row_id,batch_no FROM dii_replay_database_issue";
    private record QueryWhere(String sql,List<Object> args) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();
    public ReplayDatabaseIssueDao(JdbcTemplate diiResultJdbcTemplate){
        jdbc=diiResultJdbcTemplate;tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    public <T>T writing(Supplier<T> work){return tx.execute(status->{jdbc.queryForObject("SELECT id FROM dii_replay_database_issue_write_lock WHERE id=1 FOR UPDATE",Integer.class);return work.get();});}
    public Map<String,Object> find(long id){var rows=jdbc.query("SELECT * FROM dii_replay_database_issue WHERE id=?",this::row,id);return rows.isEmpty()?null:rows.get(0);}
    public Map<String,Object> findByIssueId(String issueId){var rows=jdbc.query("SELECT * FROM dii_replay_database_issue WHERE issue_id=?",this::row,issueId);return rows.isEmpty()?null:rows.get(0);}
    public List<Map<String,Object>> current(){return jdbc.query("SELECT * FROM dii_replay_database_issue ORDER BY id",this::row);}
    public void insert(Map<String,Object> r){
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{var ps=connection.prepareStatement("INSERT INTO dii_replay_database_issue(issue_id,table_name,table_comment,domain_name,owner_display,issue_level,field_name,difference_count,batch_no,batch_date,issue_status,issue_type,review_status,review_reason,defect_repair_date,cooperation_person_display,data_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            Object[] values=values(r);for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},keys);
        r.put("id",Objects.requireNonNull(keys.getKey()).longValue());r.put("version",0L);
    }
    public void update(Map<String,Object> r){
        Object[] values=values(r);var args=new ArrayList<>(Arrays.asList(values));args.add(r.get("id"));args.add(r.get("version"));
        int changed=jdbc.update("UPDATE dii_replay_database_issue SET issue_id=?,table_name=?,table_comment=?,domain_name=?,owner_display=?,issue_level=?,field_name=?,difference_count=?,batch_no=?,batch_date=?,issue_status=?,issue_type=?,review_status=?,review_reason=?,defect_repair_date=?,cooperation_person_display=?,data_snapshot=?,version=version+1 WHERE id=? AND version=?",args.toArray());
        if(changed!=1)throw new ReplayDatabaseIssueConflictException();
        r.put("version",((Number)r.get("version")).longValue()+1);
    }
    private Object[] values(Map<String,Object> r){return new Object[]{r.get("issueId"),r.get("tableName"),r.get("tableComment"),r.get("domain"),r.get("owner"),r.get("issueLevel"),r.get("fieldName"),r.get("differenceCount"),r.get("batchNo"),java.sql.Date.valueOf(ReplayDatabaseIssueExcelParser.batchDate((String)r.get("batchNo"))),r.get("issueStatus"),r.get("issueType"),text(r.get("reviewStatus")),text(r.get("reviewReason")),text(r.get("defectRepairDate")),text(r.get("cooperationPerson")),encode(r)};}
    private Map<String,Object> row(ResultSet rs,int n)throws SQLException{var r=decode(rs.getString("data_snapshot"));r.put("id",rs.getLong("id"));r.put("version",rs.getLong("version"));r.put("differenceCount",rs.getLong("difference_count"));r.put("reviewStatus",text(rs.getString("review_status")));r.put("reviewReason",text(rs.getString("review_reason")));r.put("defectRepairDate",text(rs.getString("defect_repair_date")));r.put("cooperationPerson",text(rs.getString("cooperation_person_display")));return r;}
    private Map<String,Object> joinedRow(ResultSet rs,int n)throws SQLException{var r=row(rs,n);r.put("domain",rs.getString("mapped_domain"));r.put("owner",rs.getString("mapped_owner"));return r;}
    public static String reviewDisplay(Map<String,Object> row){String state=text(row.get("reviewStatus"));return state.equals("待审核")?state:state.equals("已审核")?(text(row.get("reviewReason")).trim().isEmpty()?"已审核（未填写原因）":"已审核（已填写原因）"):"";}
    public Map<String,Object> list(Map<String,?> criteria){
        String order=orderBy(criteria);var where=where(criteria,null);var args=new ArrayList<>(where.args());
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+ISSUE_FROM+where.sql(),Long.class,args.toArray()));
        int limit=Math.max(1,Math.min(integer(first(criteria.get("limit")),50),200)),offset=Math.max(0,integer(first(criteria.get("offset")),0));
        args.add(limit);args.add(offset);
        var items=jdbc.query("SELECT i.*,"+DOMAIN_SQL+" AS mapped_domain,"+OWNER_SQL+" AS mapped_owner"+ISSUE_FROM+where.sql()+" ORDER BY "+order+" LIMIT ? OFFSET ?",this::joinedRow,args.toArray());
        addOccurrenceBatches(items);
        items.forEach(item->item.put("reviewStatusDisplay",reviewDisplay(item)));
        return Map.of("items",items,"total",total,"limit",limit,"offset",offset);
    }
    private static String orderBy(Map<String,?> criteria){
        String direction=text(first(criteria.get("differenceCountOrder"))).trim().toUpperCase(Locale.ROOT);
        if(direction.isEmpty())return "i.id DESC";
        if(!Set.of("ASC","DESC").contains(direction))throw new IllegalArgumentException("差异笔数排序只支持ASC或DESC");
        return "i.difference_count "+direction+", i.id DESC";
    }
    private void addOccurrenceBatches(List<Map<String,Object>> items){
        if(items.isEmpty())return;
        var byId=new LinkedHashMap<Long,SortedSet<String>>();
        for(var item:items){var batches=new TreeSet<String>(Comparator.reverseOrder());batches.add(text(item.get("batchNo")));byId.put(((Number)item.get("id")).longValue(),batches);}
        String placeholders=String.join(",",Collections.nCopies(byId.size(),"?"));
        jdbc.query("SELECT issue_id,batch_no FROM dii_replay_database_issue_history WHERE import_event=1 AND incoming_snapshot IS NOT NULL AND issue_id IN ("+placeholders+")",rs->{
            byId.get(rs.getLong("issue_id")).add(text(rs.getString("batch_no")));
        },byId.keySet().toArray());
        for(var item:items)item.put("occurrenceBatches",List.copyOf(byId.get(((Number)item.get("id")).longValue())));
    }
    public Map<String,Object> filterOptions(String field,Map<String,?> criteria){
        var filter=HEADER_FILTERS.stream().filter(candidate->candidate.field().equals(field)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("不支持的筛选字段"));
        var scoped=new LinkedHashMap<String,Object>(criteria);scoped.remove("keyword");
        var where=where(scoped,field);
        String keyword=text(first(criteria.get("keyword")));
        if(field.equals("batchNo"))return occurrenceOptions(where,keyword);
        var sql=new StringBuilder(where.sql());var args=new ArrayList<>(where.args());
        boolean table=field.equals("tableName");
        if(!keyword.isBlank()&&!table) {
            sql.append(" AND LOWER(").append(filter.valueSql()).append(") LIKE ? ESCAPE '!'");
            args.add(like(keyword.toLowerCase(Locale.ROOT)));
        }
        String tableComments=table?",GROUP_CONCAT(DISTINCT NULLIF(i.table_comment,'') ORDER BY NULLIF(i.table_comment,'') SEPARATOR '、') AS table_comments":"";
        String grouped="SELECT "+filter.valueSql()+" AS candidate_value,COUNT(*) AS issue_count"+tableComments+ISSUE_FROM+sql+" GROUP BY "+filter.valueSql();
        if(!keyword.isBlank()&&table){
            grouped+=" HAVING MAX(CASE WHEN LOWER("+filter.valueSql()+") LIKE ? ESCAPE '!' OR LOWER(COALESCE(i.table_comment,'')) LIKE ? ESCAPE '!' THEN 1 ELSE 0 END)=1";
            String search=like(keyword.toLowerCase(Locale.ROOT));args.add(search);args.add(search);
        }
        var totals=jdbc.queryForMap("SELECT COUNT(*) AS candidate_count,COALESCE(SUM(issue_count),0) AS matched_count FROM ("+grouped+") candidates",args.toArray());
        args.add(MAX_FILTER_CANDIDATES);
        var items=jdbc.query(grouped+" ORDER BY candidate_value LIMIT ?",(rs,n)->{
            var item=new LinkedHashMap<String,Object>();String value=rs.getString("candidate_value");item.put("value",value);item.put("count",rs.getLong("issue_count"));
            if(table){String comments=text(rs.getString("table_comments"));item.put("label",value+(comments.isEmpty()?"":"\n"+comments));}return item;
        },args.toArray());
        return candidates(items,totals);
    }
    private Map<String,Object> occurrenceOptions(QueryWhere where,String keyword){
        var args=new ArrayList<>(where.args());
        String scoped="SELECT i.id AS issue_row_id,occurrence.batch_no AS candidate_value"+ISSUE_FROM+" JOIN ("+OCCURRENCES_SQL+") occurrence ON occurrence.issue_row_id=i.id"+where.sql();
        if(!keyword.isBlank()){scoped+=" AND LOWER(occurrence.batch_no) LIKE ? ESCAPE '!'";args.add(like(keyword.toLowerCase(Locale.ROOT)));}
        var totals=jdbc.queryForMap("SELECT COUNT(DISTINCT candidate_value) AS candidate_count,COUNT(DISTINCT issue_row_id) AS matched_count FROM ("+scoped+") candidates",args.toArray());
        args.add(MAX_FILTER_CANDIDATES);
        var items=jdbc.query("SELECT candidate_value,COUNT(DISTINCT issue_row_id) AS issue_count FROM ("+scoped+") candidates GROUP BY candidate_value ORDER BY candidate_value DESC LIMIT ?",
                (rs,n)->Map.<String,Object>of("value",rs.getString("candidate_value"),"count",rs.getLong("issue_count")),args.toArray());
        return candidates(items,totals);
    }
    private Map<String,Object> candidates(List<? extends Map<String,Object>> items,Map<String,Object> totals){
        long candidateCount=((Number)totals.get("candidate_count")).longValue(),matchedIssueCount=((Number)totals.get("matched_count")).longValue();
        return Map.of("items",items,"candidateCount",candidateCount,"matchedIssueCount",matchedIssueCount,"truncated",candidateCount>MAX_FILTER_CANDIDATES);
    }
    private QueryWhere where(Map<String,?> criteria,String excludedField){
        var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();
        for(var filter:HEADER_FILTERS) {
            if(filter.field().equals(excludedField))continue;
            var values=selected(criteria.get(filter.parameter()));
            if(values.isEmpty())continue;
            if(filter.field().equals("batchNo")){
                String placeholders=String.join(",",Collections.nCopies(values.size(),"?"));
                where.append(" AND (i.batch_no IN (").append(placeholders).append(") OR EXISTS (SELECT 1 FROM dii_replay_database_issue_history occurrence_filter WHERE occurrence_filter.issue_id=i.id AND occurrence_filter.import_event=1 AND occurrence_filter.incoming_snapshot IS NOT NULL AND occurrence_filter.batch_no IN (").append(placeholders).append(")))");
                args.addAll(values);args.addAll(values);continue;
            }
            where.append(" AND ").append(filter.numeric()?filter.columnSql():filter.valueSql()).append(" IN (")
                    .append(String.join(",",Collections.nCopies(values.size(),"?"))).append(")");
            for(String value:values) {
                if(filter.numeric()) {
                    try {long count=Long.parseLong(value);if(count<0)throw new NumberFormatException();args.add(count);}
                    catch(NumberFormatException e){throw new IllegalArgumentException("差异数筛选值不是有效的非负整数");}
                } else args.add(value);
            }
        }
        var columns=Map.of("domain","domain_name","issueLevel","issue_level","issueStatus","issue_status","issueType","issue_type","batchNo","batch_no");
        columns.forEach((key,col)->{if(key.equals(excludedField))return;String value=text(first(criteria.get(key)));if(!value.isBlank()){where.append(" AND ").append(key.equals("domain")?DOMAIN_SQL:"i."+col).append("=?");args.add(value);}});
        for(String key:List.of("keyword","owner")){
            if(key.equals(excludedField))continue;
            String value=text(first(criteria.get(key)));if(value.isBlank())continue;
            String escaped=like(value);
            if(key.equals("owner")){where.append(" AND ").append(OWNER_SQL).append(" LIKE ? ESCAPE '!'");args.add(escaped);}
            else{where.append(" AND (i.issue_id LIKE ? ESCAPE '!' OR i.table_name LIKE ? ESCAPE '!' OR i.table_comment LIKE ? ESCAPE '!')");args.addAll(List.of(escaped,escaped,escaped));}
        }
        return new QueryWhere(where.toString(),args);
    }
    private static Object first(Object value){return value instanceof Collection<?> values?values.stream().findFirst().orElse(null):value;}
    private static List<String> selected(Object raw){
        if(raw==null)return List.of();
        Collection<?> values=raw instanceof Collection<?> collection?collection:List.of(raw);
        if(values.size()>MAX_FILTER_CANDIDATES)throw new IllegalArgumentException("单个字段最多选择500个筛选值");
        var result=new LinkedHashSet<String>();
        for(Object value:values){String selected=text(value);if(selected.length()>1024)throw new IllegalArgumentException("筛选值不能超过1024个字符");result.add(selected);}
        return new ArrayList<>(result);
    }
    private static String like(String value){
        if(value.length()>256)throw new IllegalArgumentException("查询条件不能超过256个字符");
        return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    public List<String> distinct(String column){
        String expression=switch(column){case "domain_name"->DOMAIN_SQL;case "owner_display"->OWNER_SQL;case "batch_no"->"i.batch_no";default->throw new IllegalArgumentException();};
        return jdbc.queryForList("SELECT DISTINCT "+expression+" AS candidate_value"+ISSUE_FROM+" WHERE TRIM(COALESCE("+expression+",''))<>'' ORDER BY candidate_value",String.class);
    }
    public Map<String,String> metadata(String table){
        var rows=technologyOwners(List.of(new TableIdentity(table.toLowerCase(Locale.ROOT))));
        if(rows.size()!=1)return Map.of();
        var row=rows.get(0);return Map.of("tableComment",text(row.tableComment()),"domain",text(row.domain()),"owner",text(row.owner()));
    }
    public List<TechnologyOwnerRegistration> technologyOwners(Collection<TableIdentity> tables){
        if(tables.isEmpty())return List.of();
        var names=new LinkedHashSet<String>();tables.forEach(table->names.add(table.tableName()));
        return jdbc.query("SELECT table_name,domain_name,technology_owner_emp_nos,technology_owner_display,developer_display,table_comment FROM dii_replay_database_table_person WHERE table_name IN ("+String.join(",",Collections.nCopies(names.size(),"?"))+")",
                (rs,n)->new TechnologyOwnerRegistration(new TableIdentity(rs.getString("table_name")),text(rs.getString("technology_owner_emp_nos")),text(rs.getString("technology_owner_display")),text(rs.getString("domain_name")),text(rs.getString("developer_display")),text(rs.getString("table_comment"))),names.toArray());
    }
    public void checkTableDates(Set<String> tables,java.time.LocalDate date){
        for(String table:tables){var dates=jdbc.queryForList("SELECT batch_date FROM dii_replay_database_issue_table_state WHERE table_name=?",java.sql.Date.class,table);
            if(!dates.isEmpty()&&dates.get(0).toLocalDate().isAfter(date))throw new IllegalArgumentException("当前表已有更新批次，不允许倒序导入："+table);}
    }
    public void updateTableDates(Set<String> tables,String batch){
        for(String table:tables){jdbc.update("DELETE FROM dii_replay_database_issue_table_state WHERE table_name=?",table);jdbc.update("INSERT INTO dii_replay_database_issue_table_state(table_name,batch_date,batch_no) VALUES (?,?,?)",table,java.sql.Date.valueOf(ReplayDatabaseIssueExcelParser.batchDate(batch)),batch);}
    }
    public void batch(String batch,String filename,String at,String username,String name,int input,int created,int updated,int repaired){
        jdbc.update("DELETE FROM dii_replay_database_issue_batch WHERE batch_no=?",batch);
        jdbc.update("INSERT INTO dii_replay_database_issue_batch(batch_no,batch_date,filename,imported_at,operator_username,operator_name,input_rows,created_rows,updated_rows,auto_repaired_rows) VALUES (?,?,?,?,?,?,?,?,?,?)",batch,java.sql.Date.valueOf(ReplayDatabaseIssueExcelParser.batchDate(batch)),filename,Timestamp.valueOf(at),username,name,input,created,updated,repaired);
    }
    public void history(Map<String,Object> before,Map<String,Object> after,Map<String,Object> incoming,String batch,String operation,String at,String username,String name,boolean importEvent){
        // A same-batch absence must preserve any earlier actual appearance used by occurrence filters.
        if(importEvent)jdbc.update("DELETE FROM dii_replay_database_issue_history WHERE issue_id=? AND batch_no=? AND import_event=1"+(incoming==null?" AND incoming_snapshot IS NULL":""),after.get("id"),batch);
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,operator_username,operator_name,import_event,before_snapshot,after_snapshot,incoming_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?)",
                after.get("id"),batch,operation,Timestamp.valueOf(at),username,name,importEvent?1:0,before==null?null:encode(before),encode(after),incoming==null?null:encode(incoming));
    }
    public List<Map<String,Object>> history(long id){return jdbc.query("SELECT * FROM dii_replay_database_issue_history WHERE issue_id=? ORDER BY batch_no DESC,operation_at DESC,id DESC",(rs,n)->{
        Map<String,Object> r=new LinkedHashMap<>();r.put("id",rs.getLong("id"));r.put("batchNo",rs.getString("batch_no"));r.put("operationType",rs.getString("operation_type"));r.put("operationAt",rs.getTimestamp("operation_at").toLocalDateTime().toString().replace('T',' '));r.put("operatorUsername",rs.getString("operator_username"));r.put("operatorName",rs.getString("operator_name"));r.put("before",decode(rs.getString("before_snapshot")));r.put("after",decode(rs.getString("after_snapshot")));r.put("incoming",decode(rs.getString("incoming_snapshot")));return r;
    },id);}
    public String encode(Map<String,Object> r){try{return json.writeValueAsString(r);}catch(Exception e){throw new IllegalStateException("生成数据库问题快照失败",e);}}
    private Map<String,Object> decode(String s){try{return s==null?new LinkedHashMap<>():json.readValue(s,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("读取数据库问题快照失败",e);}}
    private static int integer(Object v,int fallback){if(v==null||v.toString().isBlank())return fallback;try{return Integer.parseInt(v.toString());}catch(Exception e){throw new IllegalArgumentException("分页参数不是有效整数");}}
    public static String text(Object v){return v==null?"":v.toString();}
}
