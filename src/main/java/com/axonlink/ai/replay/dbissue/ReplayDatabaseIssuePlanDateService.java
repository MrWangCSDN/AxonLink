package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateService;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import static com.axonlink.ai.replay.dbissue.ReplayDatabaseIssueDao.text;

/** Shares replay plan-date policy while keeping database-issue dates and audit identities separate. */
@Service
public class ReplayDatabaseIssuePlanDateService {
    private final ReplayDatabaseIssueDao dao;
    private final ReplayIssuePlanDateService policy;
    private final SysUserDao users;
    private final Clock clock;

    @Autowired
    public ReplayDatabaseIssuePlanDateService(ReplayDatabaseIssueDao dao,ReplayIssuePlanDateService policy,SysUserDao users){
        this(dao,policy,users,Clock.systemDefaultZone());
    }
    ReplayDatabaseIssuePlanDateService(ReplayDatabaseIssueDao dao,ReplayIssuePlanDateService policy,SysUserDao users,Clock clock){
        this.dao=dao;this.policy=policy;this.users=users;this.clock=clock;
    }

    public void project(List<Map<String,Object>> rows,ReplayIssueOperator actor){
        var permissions=policy.permissions(actor);
        var user=actor==null?null:users.findActiveByUsername(actor.username());
        var tables=new LinkedHashSet<String>();rows.forEach(row->tables.add(text(row.get("tableName"))));
        Set<String> owned=user==null||text(user.getEmpNo()).isBlank()?Set.of():dao.developerTables(user.getUsername(),tables);
        for(var row:rows){
            String domain=text(row.get("domain"));
            boolean bypass=permissions.dateLimitBypassGroups().contains(domain);
            boolean allowed=permissions.editableGroups().contains(domain)||owned.contains(text(row.get("tableName")));
            row.put("canEditPlanDate",text(row.get("defectRepairDate")).isBlank()&&allowed);
            row.put("canBypassPlanDateLimit",bypass);
        }
    }

    public Map<String,Object> update(long id,Map<String,Object> request,ReplayIssueOperator actor){
        if(actor==null||text(actor.username()).isBlank())throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("请先登录");
        return dao.writing(()->{
            var before=required(id);
            if(!Objects.equals(text(before.get("version")),text(request.get("version"))))throw new ReplayDatabaseIssueConflictException();
            if(!text(before.get("defectRepairDate")).isBlank())throw new IllegalArgumentException(ReplayIssuePlanDateService.REPAIRED_DATE_LOCK_MESSAGE);
            var access=new LinkedHashMap<>(before);
            access.put("domain",dao.metadata(text(before.get("tableName"))).getOrDefault("domain",""));
            project(List.of(access),actor);
            if(!Boolean.TRUE.equals(access.get("canEditPlanDate")))throw new SecurityException("没有计划验证日期编辑权限");
            var date=ReplayIssuePlanDateService.parse(text(request.get("plannedCompletionDate")));
            if(text(before.get("plannedCompletionDate")).equals(date==null?"":date.toString()))return access;
            if(!Boolean.TRUE.equals(access.get("canBypassPlanDateLimit")))
                ReplayIssuePlanDateService.validateOccurrenceBoundary(text(before.get("firstOccurredDate")),date);
            var after=new LinkedHashMap<>(before);after.put("plannedCompletionDate",date==null?"":date.toString());
            var at=LocalDateTime.now(clock);
            dao.update(after);
            dao.insertPlanDateChange(id,date,actor,at);
            after.put("plannedCompletionDateChangeCount",((Number)before.get("plannedCompletionDateChangeCount")).longValue()+1);
            dao.history(before,after,null,text(after.get("batchNo")),"修改计划验证日期",at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),actor.username(),actor.realName(),false);
            after.put("domain",access.get("domain"));project(List.of(after),actor);return after;
        });
    }

    public Map<String,Object> changes(long id){
        return dao.writing(()->{var row=required(id);return Map.of("changeCount",row.get("plannedCompletionDateChangeCount"),"items",dao.planDateChanges(id));});
    }
    private Map<String,Object> required(long id){var row=dao.find(id);if(row==null)throw new IllegalArgumentException("数据库回放问题不存在");return row;}
}
