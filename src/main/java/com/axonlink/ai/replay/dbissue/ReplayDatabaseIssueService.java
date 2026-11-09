package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.replay.service.ReplayReleaseTime;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.axonlink.ai.replay.dbissue.ReplayDatabaseIssueDao.text;

@Service
public class ReplayDatabaseIssueService {
    private final ReplayDatabaseIssueExcelParser parser;private final ReplayDatabaseIssueDao dao;
    private final SysUserDao users;private final Clock clock;
    private ReplayDatabaseIssuePlanDateService planDates;
    @Autowired public void setPlanDates(ReplayDatabaseIssuePlanDateService planDates){this.planDates=planDates;}
    private static final Set<String> REPAIRABLE=Set.of("新建","打开","重新打开","延后修复","修复待验证");
    private static final String NOT_COMPARED="不参与比对";
    private static final Map<String,String> CHANGES=Map.ofEntries(
            Map.entry("plannedCompletionDate","计划验证日期"),Map.entry("issueStatus","问题状态"),Map.entry("issueLevel","问题级别"),Map.entry("fieldName","字段英文名"),
            Map.entry("differenceCount","差异笔数"),Map.entry("total528","528总量"),Map.entry("totalCcbs","ccbs总量"),
            Map.entry("only528Count","仅528存在数量"),Map.entry("onlyCcbsCount","仅ccbs存在数量"),
            Map.entry("issueType","问题类型"),Map.entry("cooperationPerson","需协同人"),Map.entry("initialAnalysis","初步问题分析"),
            Map.entry("finalSolution","最终处理方案"),Map.entry("remark","备注"),Map.entry("reviewReason","审核原因"));
    @Autowired public ReplayDatabaseIssueService(ReplayDatabaseIssueExcelParser parser,ReplayDatabaseIssueDao dao,SysUserDao users){this(parser,dao,users,Clock.systemDefaultZone());}
    public ReplayDatabaseIssueService(ReplayDatabaseIssueExcelParser parser,ReplayDatabaseIssueDao dao,SysUserDao users,Clock clock){this.parser=parser;this.dao=dao;this.users=users;this.clock=clock;}
    public Map<String,Object> list(Map<String,?> criteria,ReplayIssueOperator actor){
        var page=new LinkedHashMap<String,Object>(dao.list(criteria));
        @SuppressWarnings("unchecked")var items=(List<Map<String,Object>>)page.get("items");
        page.put("items",project(items,actor,false));return page;
    }
    public Map<String,Object> importFile(MultipartFile file,String batchNo,ReplayIssueOperator actor){
        return importFile(file,batchNo,actor,null);
    }
    public Map<String,Object> importFile(MultipartFile file,String batchNo,ReplayIssueOperator actor,String releaseAt){
        return importFile(file, batchNo, actor, releaseAt, null);
    }
    public Map<String,Object> importFile(MultipartFile file, String batchNo, ReplayIssueOperator actor,
                                         String releaseAt, String registrationVersionNo) {
        requireActor(actor);var parsed=parser.parse(file,batchNo);String at=now();
        LocalDateTime releaseTime=ReplayReleaseTime.resolve(releaseAt,parsed.batchDate());
        return dao.writing(()->{
            var registrationVersion = resolveRegistrationVersion(parsed.batchNo(), registrationVersionNo, parsed.tables());
            dao.checkTableDates(parsed.tables(),parsed.batchDate());
            var current=dao.current();
            for(var row:current)if(parsed.tables().contains(text(row.get("tableName")))&&ReplayDatabaseIssueExcelParser.batchDate(text(row.get("batchNo"))).isAfter(parsed.batchDate()))throw new IllegalArgumentException("当前表已有更新批次，不允许倒序导入："+row.get("tableName"));
            var known=new HashMap<String,Map<String,Object>>();current.forEach(r->known.put(text(r.get("issueId")),r));
            var incomingIds=new HashSet<String>();int created=0,updated=0,repaired=0;
            var metadata=new HashMap<String,Map<String,String>>();
            for(var input:parsed.issues()){
                incomingIds.add(input.issueId());var before=known.get(input.issueId());
                Map<String,Object> after=before==null?blank():new LinkedHashMap<>(before);
                var meta=metadata.computeIfAbsent(input.tableName(),dao::metadata);
                var raw=new LinkedHashMap<String,Object>();raw.put("issueId",input.issueId());raw.put("tableName",input.tableName());raw.put("tableComment",input.tableComment().isEmpty()?meta.getOrDefault("tableComment",""):input.tableComment());raw.put("domain",meta.getOrDefault("domain",""));raw.put("owner",meta.getOrDefault("owner",""));raw.put("issueLevel",input.issueLevel());raw.put("fieldName",input.fieldName());raw.put("differenceCount",input.differenceCount());raw.put("total528",input.total528());raw.put("totalCcbs",input.totalCcbs());raw.put("only528Count",input.only528());raw.put("onlyCcbsCount",input.onlyCcbs());raw.put("sameKeyDifferentRowCount",input.sameKeyDifferentRowCount());raw.put("batchNo",parsed.batchNo());
                after.putAll(raw);after.put("importedAt",at);after.put("lastOccurredDate",parsed.batchDate().toString());
                String operation="基础数据覆盖，人工内容继承";
                boolean hasDifference=input.issueLevel().equals("表级") ? input.only528()+input.onlyCcbs()>0 : input.differenceCount()>0;
                if(before==null){after.put("issueStatus",hasDifference?"新建":"已修复");after.put("firstOccurredDate",parsed.batchDate().toString());operation=hasDifference?"导入新增":"导入无差异";}
                else{
                    String status=text(before.get("issueStatus"));
                    if(!hasDifference){after.put("issueStatus","已修复");operation="导入无差异";}
                    else if(status.equals("已修复")||status.equals(NOT_COMPARED)){after.put("issueStatus","新建");after.put("firstOccurredDate",parsed.batchDate().toString());clearHuman(after);operation=status.equals(NOT_COMPARED)?"重新参与比对并新建":"已修复问题重新新建";}
                    else if(status.equals("修复待验证")&&ReplayReleaseTime.canReopen(
                            text(before.get("lastManualSaveAt")).isEmpty()?null:LocalDateTime.parse(text(before.get("lastManualSaveAt")).replace(' ','T')),
                            releaseTime)){after.put("issueStatus","重新打开");operation="修复待验证问题重新打开";}
                }
                importRepairDate(after,parsed.batchDate());
                if(before==null){dao.insert(after);created++;}else{dao.update(after);updated++;}
                dao.history(before,after,raw,parsed.batchNo(),operation,at,actor.username(),actor.realName(),true);
            }
            for(var before:current){
                if(!incomingIds.contains(text(before.get("issueId")))&&parsed.tables().contains(text(before.get("tableName")))&&(REPAIRABLE.contains(text(before.get("issueStatus")))||text(before.get("issueStatus")).equals("已修复"))){
                    var after=new LinkedHashMap<>(before);after.put("issueStatus","已修复");after.put("defectRepairDate",parsed.batchDate().toString());
                    var meta=metadata.computeIfAbsent(text(before.get("tableName")),dao::metadata);
                    after.put("domain",meta.getOrDefault("domain",""));after.put("owner",meta.getOrDefault("owner",""));
                    clearReview(after);dao.update(after);dao.history(before,after,null,parsed.batchNo(),"问题自动修复",at,actor.username(),actor.realName(),true);repaired++;
                }
            }
            for(var before:current){
                if(!parsed.tables().contains(text(before.get("tableName")))){
                    var after=new LinkedHashMap<>(before);after.put("issueStatus",NOT_COMPARED);after.put("defectRepairDate",parsed.batchDate().toString());clearReview(after);
                    dao.update(after);dao.history(before,after,null,parsed.batchNo(),"不参与比对",at,actor.username(),actor.realName(),true);
                }
            }
            dao.batch(parsed.batchNo(),text(file.getOriginalFilename()),at,actor.username(),actor.realName(),parsed.issues().size(),created,updated,repaired,
                    registrationVersion == null ? null : registrationVersion.id());
            dao.updateTableDates(parsed.tables(),parsed.batchNo());
            return Map.of("batchNo",parsed.batchNo(),"inputRows",parsed.issues().size(),"createdRows",created,"updatedRows",updated,"autoRepairedRows",repaired,
                    "registrationVersionNo", registrationVersion == null ? "" : registrationVersion.versionNo());
        });
    }
    public Map<String,Object> importContext(String batchNo) {
        String batch = text(batchNo).trim();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("batchNo", batch);
        result.put("registrationVersion", null);
        if (batch.isEmpty()) return result;
        ReplayDatabaseIssueExcelParser.batchDate(batch);
        Long versionId = dao.batchRegistrationVersionId(batch);
        if (versionId == null) return result;
        var version = requireRegistrationVersion(dao.registrationVersion(versionId), "批次关联的登记版本不存在");
        result.put("registrationVersion", Map.of("versionNo", version.versionNo(), "generatedAt", version.generatedAt(),
                "generatedName", version.generatedName(), "tableCount", version.tableCount(), "fieldCount", version.fieldCount()));
        return result;
    }
    private ReplayDatabaseIssueDao.RegistrationVersion resolveRegistrationVersion(
            String batchNo, String requestedVersionNo, Set<String> tables) {
        String requested = text(requestedVersionNo).trim();
        Long boundVersionId = dao.lockBatchRegistrationVersionId(batchNo);
        ReplayDatabaseIssueDao.RegistrationVersion version = null;
        if (boundVersionId != null) {
            version = requireRegistrationVersion(dao.registrationVersion(boundVersionId), "批次关联的登记版本不存在");
            if (!requested.isEmpty() && !requested.equals(version.versionNo())) {
                throw new RegistrationVersionConflictException("该批次已关联登记版本 " + version.versionNo() + "，请沿用此版本导入");
            }
        } else if (!requested.isEmpty()) {
            version = requireRegistrationVersion(dao.registrationVersion(requested), "登记版本不存在：" + requested);
        }
        if (version == null) return null;
        Map<String,Long> matches = dao.registrationVersionTableCounts(version.id(), tables);
        List<String> missing = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        tables.stream().sorted().forEach(table -> {
            long count = matches.getOrDefault(table.toLowerCase(Locale.ROOT), 0L);
            if (count == 0) missing.add(table);
            else if (count > 1) ambiguous.add(table);
        });
        List<String> reasons = new ArrayList<>();
        if (!missing.isEmpty()) reasons.add("缺少表 " + String.join("、", missing));
        if (!ambiguous.isEmpty()) reasons.add("存在同名多份表配置 " + String.join("、", ambiguous));
        if (!reasons.isEmpty()) throw new IllegalArgumentException("登记版本 " + version.versionNo() + " " + String.join("，", reasons));
        return version;
    }
    private static ReplayDatabaseIssueDao.RegistrationVersion requireRegistrationVersion(
            ReplayDatabaseIssueDao.RegistrationVersion version, String message) {
        if (version == null) throw new RegistrationVersionNotFoundException(message);
        return version;
    }
    public static final class RegistrationVersionNotFoundException extends RuntimeException {
        public RegistrationVersionNotFoundException(String message) { super(message); }
    }
    public static final class RegistrationVersionConflictException extends RuntimeException {
        public RegistrationVersionConflictException(String message) { super(message); }
    }
    public Map<String,Object> update(long id,Map<String,Object> request,ReplayIssueOperator actor){
        requireActor(actor);return dao.writing(()->{
            var before=required(id);checkVersion(before,request);
            String oldStatus=text(before.get("issueStatus"));if(oldStatus.equals("已修复")||oldStatus.equals(NOT_COMPARED))throw new IllegalArgumentException(oldStatus+"的问题不可编辑");
            if(oldStatus.equals("无需处理")&&text(before.get("reviewStatus")).equals("已审核")&&!isTechnologyOwner(before,actor))throw new SecurityException("该问题仅限表人员清单中的科技负责人编辑");
            String status=request.containsKey("issueStatus")?text(request.get("issueStatus")):oldStatus;
            if(status.isBlank())throw new IllegalArgumentException("请选择有效问题状态");
            if(!status.equals(oldStatus)&&!ReplayIssueStatus.fromDisplayValue(status).isManuallySelectable())throw new IllegalArgumentException("该问题状态不能手工选择");
            if(oldStatus.equals("重新打开")&&status.equals("打开"))throw new IllegalArgumentException("重新打开的问题不能改回打开状态");
            String type=text(request.get("issueType")).trim();
            if(status.equals("无需处理")&&!ReplayDatabaseIssueTypes.NO_ACTION.contains(type))throw new IllegalArgumentException("无需处理的问题类型只能选择：不参与比对、规则性差异");
            if(!ReplayDatabaseIssueTypes.ALL.contains(type))throw new IllegalArgumentException("请选择有效问题类型");
            if(!status.equals("无需处理")&&!ReplayDatabaseIssueTypes.ACTIONABLE.contains(type))throw new IllegalArgumentException("其他状态的问题类型只能选择：修改比对规则、修改迁移规则、修改代码、前后交易差异、其他问题");
            var after=new LinkedHashMap<>(before);after.put("issueStatus",status);after.put("issueType",type);
            for(String field:List.of("initialAnalysis","finalSolution","remark")){String value=text(request.get(field));if(value.length()>500)throw new IllegalArgumentException("分析、方案和备注各不能超过500个字符");after.put(field,value);}
            String username=text(request.get("cooperationPersonUsername")).trim();String name="";
            if(!username.isEmpty()){var user=users.findByUsername(username);if(user==null)throw new IllegalArgumentException("需协同人不存在，请从搜索结果选择");username=user.getUsername();name=text(user.getRealName());}
            after.put("cooperationPersonUsername",username);after.put("cooperationPersonName",name);after.put("cooperationPerson",person(name,username));
            if(status.equals("无需处理")){if(!oldStatus.equals(status)||!text(before.get("reviewStatus")).equals("已审核")){clearReview(after);after.put("reviewStatus","待审核");after.put("defectRepairDate","");}}
            else{clearReview(after);after.put("defectRepairDate","");}
            if(after.equals(before))return projected(before,actor);
            String at=now();after.put("lastManualSaveAt",at);dao.update(after);dao.history(before,after,null,text(after.get("batchNo")),"人工保存",at,actor.username(),actor.realName(),false);return projected(after,actor);
        });
    }
    public Map<String,Object> approve(long id,Map<String,Object> request,ReplayIssueOperator actor){
        requireActor(actor);return dao.writing(()->{
            var before=required(id);checkVersion(before,request);
            if(!text(before.get("issueStatus")).equals("无需处理")||!Set.of("待审核","已审核").contains(text(before.get("reviewStatus"))))throw new IllegalArgumentException("当前问题无需审核");
            if(!isTechnologyOwner(before,actor))throw new SecurityException("没有审核权限，请联系表人员清单中的科技负责人");
            String reason=text(request.get("reason")).trim();if(reason.isEmpty()||reason.length()>500)throw new IllegalArgumentException("审核原因必填且不能超过500个字符");
            boolean first=text(before.get("reviewStatus")).equals("待审核");
            if(!first&&reason.equals(text(before.get("reviewReason"))))return projected(before,actor);
            var after=new LinkedHashMap<>(before);String at=now();after.put("reviewStatus","已审核");after.put("reviewReason",reason);after.put("reviewer",person(actor.realName(),actor.username()));after.put("reviewedAt",at);
            if(first)after.put("defectRepairDate",at.substring(0,10));
            dao.update(after);dao.history(before,after,null,text(after.get("batchNo")),first?"审核通过":"更新审核原因",at,actor.username(),actor.realName(),false);return projected(after,actor);
        });
    }
    public Map<String,Object> options(ReplayIssueOperator actor){
        var result=new LinkedHashMap<String,Object>();result.put("domains",dao.distinct("domain_name"));result.put("owners",dao.distinct("owner_display"));result.put("batches",dao.distinct("batch_no"));result.put("issueLevels",List.of("字段级","表级"));result.put("issueTypes",ReplayDatabaseIssueTypes.ALL);result.put("issueStatuses",List.of("新建","打开","无需处理","延后修复","修复待验证","重新打开","已修复"));
        result.put("reviewStatuses",List.of("待审核","已审核（未填写原因）","已审核（已填写原因）"));return result;
    }
    private record TechnologyOwner(String username,String name,String display,boolean active,String domain,String owner) {}
    private Map<ReplayDatabaseIssueDao.TableIdentity,TechnologyOwner> technologyOwners(List<Map<String,Object>> rows){
        var keys=new LinkedHashSet<ReplayDatabaseIssueDao.TableIdentity>();rows.forEach(row->keys.add(ReplayDatabaseIssueDao.TableIdentity.of(row)));
        var registrations=new LinkedHashMap<ReplayDatabaseIssueDao.TableIdentity,List<ReplayDatabaseIssueDao.TechnologyOwnerRegistration>>();
        for(var registration:dao.technologyOwners(keys))registrations.computeIfAbsent(registration.table(),key->new ArrayList<>()).add(registration);
        var identities=new LinkedHashSet<String>();registrations.values().forEach(list->list.forEach(row->{identities.addAll(technologyOwnerIdentities(row.identity()));}));
        var matches=new HashMap<String,Map<String,SysUser>>();
        for(var user:users.findByUsernamesOrEmpNos(identities)){
            for(String identity:List.of(text(user.getUsername()),text(user.getEmpNo()))){
                if(identities.contains(identity))matches.computeIfAbsent(identity,key->new LinkedHashMap<>()).put(text(user.getUsername()),user);
            }
        }
        var result=new LinkedHashMap<ReplayDatabaseIssueDao.TableIdentity,TechnologyOwner>();
        for(var key:keys){
            var rowsForTable=registrations.getOrDefault(key,List.of());
            if(rowsForTable.size()!=1){result.put(key,new TechnologyOwner("","","",false,"",""));continue;}
            var registration=rowsForTable.get(0);
            var usernames=new LinkedHashSet<String>();var names=new LinkedHashSet<String>();
            for(String identity:technologyOwnerIdentities(registration.identity())){
                var candidates=matches.getOrDefault(identity,Map.of());
                // Resolve each person separately; ambiguous or disabled identities never grant access.
                if(candidates.size()!=1)continue;
                var user=candidates.values().iterator().next();String username=text(user.getUsername()).trim();
                if(username.isBlank()||!Objects.equals(user.getStatus(),1))continue;
                usernames.add(username);names.add(text(user.getRealName()));
            }
            // Display is personnel-list data, independent of whether every account is still active.
            result.put(key,new TechnologyOwner(String.join("、",usernames),String.join("、",names),registration.name(),!usernames.isEmpty(),registration.domain(),registration.owner()));
        }
        return result;
    }
    private static List<String> technologyOwnerIdentities(String value){
        return Arrays.stream(text(value).split("[、,，;；\\n]+"))
                .map(String::trim).filter(identity->!identity.isBlank()).distinct().toList();
    }
    private boolean isTechnologyOwner(Map<String,Object> row,ReplayIssueOperator actor){return authorized(technologyOwners(List.of(row)).get(ReplayDatabaseIssueDao.TableIdentity.of(row)),actor);}
    private static boolean authorized(TechnologyOwner owner,ReplayIssueOperator actor){return actor!=null&&owner!=null&&owner.active()&&technologyOwnerIdentities(owner.username()).contains(text(actor.username()).trim());}
    private Map<String,Object> projected(Map<String,Object> row,ReplayIssueOperator actor){return project(List.of(row),actor,true).get(0);}
    private List<Map<String,Object>> project(List<Map<String,Object>> rows,ReplayIssueOperator actor,boolean mapRegistration){
        var owners=technologyOwners(rows);var result=new ArrayList<Map<String,Object>>();boolean loggedIn=actor!=null&&!text(actor.username()).isBlank();
        for(var row:rows){
            var owner=owners.get(ReplayDatabaseIssueDao.TableIdentity.of(row));var item=new LinkedHashMap<String,Object>(row);
            if(mapRegistration){item.put("domain",owner.domain());item.put("owner",owner.owner());}
            boolean authorized=authorized(owner,actor),noAction=text(row.get("issueStatus")).equals("无需处理"),approved=text(row.get("reviewStatus")).equals("已审核");
            item.put("technologyOwnerUsername",owner.username());item.put("technologyOwnerName",owner.name());item.put("technologyOwnerDisplay",owner.display());item.put("technologyOwner",owner.display());
            item.put("canReview",noAction&&Set.of("待审核","已审核").contains(text(row.get("reviewStatus")))&&authorized);
            item.put("canEdit",loggedIn&&!Set.of("已修复",NOT_COMPARED).contains(text(row.get("issueStatus")))&&(!noAction||!approved||authorized));
            item.put("reviewStatusDisplay",ReplayDatabaseIssueDao.reviewDisplay(row));result.add(item);
        }
        if(planDates!=null)planDates.project(result,actor);
        return result;
    }
    @SuppressWarnings("unchecked") public List<Map<String,Object>> tracking(long id){
        required(id);var groups=new LinkedHashMap<String,Map<String,Object>>();
        for(var event:dao.history(id)){
            String batch=text(event.get("batchNo"));var group=groups.computeIfAbsent(batch,b->{var r=new LinkedHashMap<String,Object>();r.put("batchNo",b);r.put("originalData",Map.of());r.put("events",new ArrayList<Map<String,Object>>());return r;});
            var incoming=(Map<String,Object>)event.remove("incoming");if(!incoming.isEmpty()&&((Map<?,?>)group.get("originalData")).isEmpty())group.put("originalData",incoming);
            var before=(Map<String,Object>)event.remove("before");var after=(Map<String,Object>)event.remove("after");var changes=new ArrayList<Map<String,String>>();
            CHANGES.forEach((key,label)->{String oldValue=text(before.get(key)),newValue=text(after.get(key));if(!oldValue.equals(newValue))changes.add(Map.of("field",label,"before",oldValue,"after",newValue));});
            event.put("changes",changes);((List<Map<String,Object>>)group.get("events")).add(event);
        }return List.copyOf(groups.values());
    }
    public Map<String,Object> required(long id){var row=dao.find(id);if(row==null)throw new IllegalArgumentException("数据库回放问题不存在");return row;}
    private static void checkVersion(Map<String,Object> before,Map<String,Object> request){if(!Objects.equals(text(before.get("version")),text(request.get("version"))))throw new ReplayDatabaseIssueConflictException();}
    private static Map<String,Object> blank(){var r=new LinkedHashMap<String,Object>();for(String key:List.of("issueType","initialAnalysis","finalSolution","remark","cooperationPersonUsername","cooperationPersonName","cooperationPerson","defectRepairDate","lastManualSaveAt","reviewStatus","reviewReason","reviewer","reviewedAt"))r.put(key,"");return r;}
    private static void clearReview(Map<String,Object> r){for(String key:List.of("reviewStatus","reviewReason","reviewer","reviewedAt"))r.put(key,"");}
    private String now(){return LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));}
    private static void requireActor(ReplayIssueOperator actor){if(actor==null||text(actor.username()).isBlank())throw new SecurityException("无法获取当前登录人");}
    private static void clearHuman(Map<String,Object> row){row.put("issueType","");row.put("cooperationPersonUsername","");row.put("cooperationPersonName","");row.put("cooperationPerson","");row.put("initialAnalysis","");row.put("finalSolution","");row.put("remark","");clearReview(row);}
    private static void importRepairDate(Map<String,Object> row,LocalDate batchDate){
        String status=text(row.get("issueStatus"));
        if(status.equals("已修复")||status.equals(NOT_COMPARED))row.put("defectRepairDate",batchDate.toString());
        else if(!status.equals("无需处理")||!text(row.get("reviewStatus")).equals("已审核"))row.put("defectRepairDate","");
    }
    private static String person(String name,String username){String n=text(name),u=text(username);return n.isEmpty()?u:u.isEmpty()?n:n+"("+u+")";}
}
