package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueReplayType;
import com.axonlink.ai.replay.service.ReplayIssueDomainService;
import com.axonlink.ai.user.entity.SysUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;
import static com.axonlink.ai.replay.reopen.ReplayReopenWorkflowDtos.*;

/** Permanent facts are immutable; this service only changes their effective attribution. */
@Service
public class ReplayReopenService {
    private final ReplayReopenDao events;
    private final ReplayReopenWorkflowDao store;
    private final ReplayReopenPermissionService permissions;
    private final ReplayReopenRecorder recorder;
    private final ReplayReopenProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public ReplayReopenService(ReplayReopenDao events, ReplayReopenWorkflowDao store,
                               ReplayReopenPermissionService permissions, ReplayReopenRecorder recorder,
                               ReplayReopenProperties properties) {
        this(events, store, permissions, recorder, properties, Clock.systemDefaultZone());
    }

    ReplayReopenService(ReplayReopenDao events, ReplayReopenWorkflowDao store,
                        ReplayReopenPermissionService permissions, ReplayReopenRecorder recorder,
                        ReplayReopenProperties properties, Clock clock) {
        this.events=events; this.store=store; this.permissions=permissions;
        this.recorder=recorder; this.properties=properties; this.clock=clock;
    }

    public Permissions permissions(ReplayIssueOperator operator) {
        SysUser current=permissions.active(operator);
        if(!properties.isEnabled()) return new Permissions(false,permissions.option(current),false,List.of(),List.of(),ReplayIssueDomainService.ALLOWED_DOMAINS);
        List<Policy> policies=store.policies();
        return new Permissions(true,permissions.option(current),permissions.canManage(operator),
                policies.stream().map(Policy::issueDomain).filter(domain->permissions.isLeader(domain,operator)).toList(),
                policies,issueDomains());
    }

    public Map<String,List<String>> filters(String replayType) {
        enabled();
        return Map.of("batchNames",events.batchNames(replayType(replayType)),"issueDomains",issueDomains());
    }

    public List<UserOption> developers(String keyword,int limit) {
        enabled(); return permissions.developers(keyword,limit);
    }

    public Page<EventItem> events(Filter filter,ReplayIssueOperator operator) {
        enabled();
        Page<EventRow> page=events.events(normalize(filter,operator));
        return new Page<>(page.items().stream().map(event->item(event,operator)).toList(),page.total(),page.page(),page.pageSize());
    }

    public EventItem event(long id,ReplayIssueOperator operator) {
        enabled(); return item(requireEvent(id),operator);
    }

    public Page<AuditEntry> history(long id,int page,int pageSize) {
        enabled(); requireEvent(id); return store.history(id,page,pageSize);
    }

    public Statistics statistics(Filter filter,ReplayIssueOperator operator) {
        enabled(); return events.statistics(normalize(filter,operator));
    }

    public Map<String,Long> issueCounts(List<String> keys) {
        enabled();
        if(keys==null || keys.size()>200) throw ReplayReopenException.invalid("每次最多查询200个问题的重开次数");
        return events.issueCounts(keys.stream().map(key->required(key,"issue_key",1024)).distinct().toList());
    }

    public Page<AppealView> appeals(Filter filter,String status,boolean onlyMine,boolean assignedToMe,ReplayIssueOperator operator) {
        enabled();
        String normalizedStatus=optional(status,16);
        if(normalizedStatus!=null && !Set.of("PENDING","APPROVED","REJECTED","WITHDRAWN","INVALID").contains(normalizedStatus)) throw ReplayReopenException.invalid("申请状态不合法");
        Filter scope=normalize(filter,operator);
        SysUser actor=permissions.active(operator);
        if((onlyMine||assignedToMe||scope.onlyMine())&&actor==null) return new Page<>(List.of(),0,scope.page(),scope.pageSize());
        String applicant=onlyMine||scope.onlyMine()?actor.getUsername():null;
        String leader=assignedToMe?actor.getUsername():null;
        Filter appealScope=new Filter(scope.issueKey(),scope.batchName(),scope.issueDomain(),scope.replayType(),
                filter==null?null:optional(filter.ownerUsername(),128),scope.eventId(),onlyMine||scope.onlyMine(),scope.page(),scope.pageSize(),scope.sourceRoundId());
        Page<AppealRow> page=store.appeals(appealScope,normalizedStatus,applicant,leader);
        return new Page<>(page.items().stream().map(a->view(a,operator)).toList(),page.total(),page.page(),page.pageSize());
    }

    public AppealView appeal(long id,ReplayIssueOperator operator) {
        enabled(); return view(requireAppeal(id,false),operator);
    }

    public AppealView createAppeal(CreateAppealRequest body,ReplayIssueOperator operator) {
        enabled();
        SysUser initialActor=permissions.requireActive(operator);
        CreateAppealRequest request=normalizeRequest(body);
        String fingerprint=fingerprint(request,initialActor.getUsername());
        try {
            return events.inWriteTransaction(()->{
                SysUser actor=permissions.requireActive(operator);
                AppealRow repeated=store.findByRequestId(request.requestId());
                if(repeated!=null) return sameRequest(repeated,actor,fingerprint,operator);
                List<EventRow> selected=lockAll(request.eventIds());
                for(EventRow event:selected) {
                    if(event.pendingAppealId()!=null) {
                        AppealRow pending=store.findAppeal(event.pendingAppealId(),true);
                        if(pending!=null&&pending.requestId().equals(request.requestId())) return sameRequest(pending,actor,fingerprint,operator);
                        throw ReplayReopenException.conflict("选中的重开已有待领导审批的申请");
                    }
                }
                String domain=selected.get(0).issueDomain();
                boolean needsRecheck=false;
                for(EventRow event:selected) {
                    if(!Objects.equals(event.currentOwnerUsername(),actor.getUsername())) throw ReplayReopenException.forbidden("只能对本人承担的重开申请责任调整");
                    if(!Objects.equals(domain,event.issueDomain())) throw ReplayReopenException.invalid("单份申请只能选择同一领域的重开记录");
                    if(!Objects.equals(request.expectedVersions().get(String.valueOf(event.id())),event.version())) throw ReplayReopenException.conflict("重开版本已变化，请刷新后重新选择");
                    needsRecheck|=store.hasDecision(event.id());
                }
                if(needsRecheck&&!"RECHECK".equals(request.kind())) throw ReplayReopenException.invalid("已裁定的重开须补充新证据申请复核");
                if(!needsRecheck&&"RECHECK".equals(request.kind())) throw ReplayReopenException.invalid("该重开尚未裁定，请先发起申诉");
                SysUser target=permissions.requireDeveloper(request.suggestedOwnerUsername());
                if(actor.getUsername().equals(target.getUsername())) throw ReplayReopenException.invalid("请重新选择其他开发负责人");
                String evidenceHash=evidenceHash(request.evidence());
                if(selected.stream().anyMatch(event->store.hasEvidenceHash(event.id(),evidenceHash))) throw ReplayReopenException.invalid("复核须补充新证据，不能重复使用历史裁定依据");
                SysUser leader=routeLeader(domain,selected,actor.getUsername(),target.getUsername());
                LocalDateTime at=now();
                long id=store.insertAppeal(request,fingerprint,domain,actor,target,leader,evidenceHash,at);
                for(EventRow event:selected) {
                    store.insertItem(id,event);
                    events.setPendingAppeal(event.id(),id);
                    audit(event,id,"APPEAL_SUBMITTED",actor,at,request.reason(),request.evidence(),event.currentOwnerUsername(),event.currentOwnerName(),target.getUsername(),target.getRealName());
                }
                return view(requireAppeal(id,false),operator);
            });
        } catch(DuplicateKeyException ex) {
            // A duplicate UUID on disjoint event locks may race; read after rollback.
            AppealRow existing=store.findByRequestId(request.requestId());
            if(existing!=null) return sameRequest(existing,permissions.requireActive(operator),fingerprint,operator);
            throw ReplayReopenException.conflict("申请已发生并发变化，请刷新重试");
        }
    }

    public AppealView decide(long id,DecisionRequest body,ReplayIssueOperator operator) {
        enabled();
        if(body==null) throw ReplayReopenException.invalid("请填写审批信息");
        long expected=version(body.expectedVersion());
        String reason=required(body.reason(),"审批原因 / 裁定说明",1000);
        String decision=required(body.decision(),"审批结论",16).toUpperCase(Locale.ROOT);
        if(!Set.of("APPROVE","REJECT").contains(decision)) throw ReplayReopenException.invalid("审批结论不合法");
        return events.inWriteTransaction(()->{
            SysUser actor=permissions.requireActive(operator);
            LockedAppeal locked=lockAppeal(id);
            AppealRow appeal=locked.appeal();
            permissions.requireLeader(appeal.issueDomain(),operator);
            if(!actor.getUsername().equals(appeal.leaderUsername())) throw ReplayReopenException.forbidden("只有本申请指定的授权领导可以审批");
            requirePendingVersion(appeal,expected);
            List<AppealItem> items=store.items(id);
            List<EventRow> selected=locked.events();
            String finalUsername="APPROVE".equals(decision)?required(body.finalOwnerUsername(),"最终开发负责人",128):null;
            if(involved(appeal,selected,actor.getUsername(),finalUsername)) throw ReplayReopenException.forbidden("涉及本人责任的申请须由其他授权领导审批");
            SysUser target=finalUsername==null?null:permissions.requireDeveloper(finalUsername);
            if(target!=null&&Objects.equals(target.getUsername(),appeal.applicantUsername())) throw ReplayReopenException.invalid("请重新选择其他最终开发负责人");
            boolean valid=selected.stream().allMatch(event->items.stream().anyMatch(item->item.eventId()==event.id()
                    &&item.expectedVersion()==event.version()&&Objects.equals(item.beforeOwnerUsername(),event.currentOwnerUsername()))
                    &&Objects.equals(event.pendingAppealId(),appeal.id()));
            LocalDateTime at=now();
            if(!valid) {
                store.closeAppeal(id,expected,"INVALID","重开归属或版本已变化，旧申请失效，未执行调整",at);
                for(EventRow event:selected) {
                    if(Objects.equals(event.pendingAppealId(),id)) events.setPendingAppeal(event.id(),null);
                    audit(event,id,"APPEAL_INVALIDATED",actor,at,"重开归属或版本已变化，未执行调整",appeal.evidence(),event.currentOwnerUsername(),event.currentOwnerName(),event.currentOwnerUsername(),event.currentOwnerName());
                }
                return view(requireAppeal(id,false),operator);
            }
            for(EventRow event:selected) {
                if(target!=null) events.updateAttribution(event.id(),target.getUsername(),target.getRealName(),event.version());
                events.setPendingAppeal(event.id(),null);
                audit(event,id,target==null?"APPEAL_REJECTED":"APPEAL_APPROVED",actor,at,reason,appeal.evidence(),
                        event.currentOwnerUsername(),event.currentOwnerName(),target==null?event.currentOwnerUsername():target.getUsername(),target==null?event.currentOwnerName():target.getRealName());
            }
            store.decideAppeal(id,expected,target==null?"REJECTED":"APPROVED",target==null?null:target.getUsername(),target==null?null:target.getRealName(),reason,at);
            return view(requireAppeal(id,false),operator);
        });
    }

    public AppealView withdraw(long id,WithdrawRequest body,ReplayIssueOperator operator) {
        enabled();
        if(body==null) throw ReplayReopenException.invalid("请填写撤回信息");
        long expected=version(body.expectedVersion());String reason=required(body.reason(),"撤回原因",1000);
        return events.inWriteTransaction(()->{
            SysUser actor=permissions.requireActive(operator);LockedAppeal locked=lockAppeal(id);AppealRow appeal=locked.appeal();
            if(!actor.getUsername().equals(appeal.applicantUsername())) throw ReplayReopenException.forbidden("只有原申请人可以撤回待审申请");
            requirePendingVersion(appeal,expected);
            List<EventRow> selected=locked.events();LocalDateTime at=now();
            store.closeAppeal(id,expected,"WITHDRAWN",reason,at);
            for(EventRow event:selected) {
                if(Objects.equals(event.pendingAppealId(),id)) events.setPendingAppeal(event.id(),null);
                audit(event,id,"APPEAL_WITHDRAWN",actor,at,reason,null,event.currentOwnerUsername(),event.currentOwnerName(),event.currentOwnerUsername(),event.currentOwnerName());
            }
            return view(requireAppeal(id,false),operator);
        });
    }

    public EventItem assign(long id,AssignRequest body,ReplayIssueOperator operator) {
        enabled();
        if(body==null) throw ReplayReopenException.invalid("请填写负责人确认信息");
        long expected=version(body.expectedVersion());String reason=required(body.reason(),"确认原因",1000);
        return events.inWriteTransaction(()->{
            SysUser actor=permissions.requireActive(operator);EventRow event=lockAll(List.of(id)).get(0);
            permissions.requireLeader(event.issueDomain(),operator);
            if(event.currentOwnerUsername()!=null&&!event.currentOwnerUsername().isBlank()) throw ReplayReopenException.conflict("该次重开已有负责人，请通过申诉审批调整");
            if(event.pendingAppealId()!=null||event.version()!=expected) throw ReplayReopenException.conflict("记录已变化，请刷新后确认");
            SysUser target=permissions.requireDeveloper(required(body.targetOwnerUsername(),"开发负责人",128));
            if(actor.getUsername().equals(target.getUsername())) throw ReplayReopenException.forbidden("不能自行确认涉及本人的重开责任");
            events.updateAttribution(id,target.getUsername(),target.getRealName(),expected);
            audit(event,null,"OWNER_ASSIGNED",actor,now(),reason,null,null,null,target.getUsername(),target.getRealName());
            return item(requireEvent(id),operator);
        });
    }

    public AppealView reassign(long id,ReassignRequest body,ReplayIssueOperator operator) {
        enabled();
        if(body==null) throw ReplayReopenException.invalid("请填写转派信息");
        long expected=version(body.expectedVersion());String reason=required(body.reason(),"转派原因",1000);
        String username=required(body.leaderUsername(),"审批领导",128);
        return events.inWriteTransaction(()->{
            SysUser actor=permissions.requireAdmin(operator);LockedAppeal locked=lockAppeal(id);AppealRow appeal=locked.appeal();requirePendingVersion(appeal,expected);
            SysUser target=permissions.active(new ReplayIssueOperator(username,null));
            Policy policy=store.findPolicy(appeal.issueDomain(),true);
            if(target==null||policy==null||!policy.leaderUsernames().contains(username)) throw ReplayReopenException.invalid("请选择该领域当前有效的授权领导");
            List<EventRow> selected=locked.events();
            if(involved(appeal,selected,username,null)) throw ReplayReopenException.forbidden("涉及本人责任的领导必须回避");
            if(username.equals(appeal.leaderUsername())) throw ReplayReopenException.invalid("请选择其他审批领导");
            LocalDateTime at=now();store.reassignAppeal(id,expected,target.getUsername(),target.getRealName(),at);
            for(EventRow event:selected) audit(event,id,"APPEAL_REASSIGNED",actor,at,reason,
                    "审批领导："+appeal.leaderUsername()+" → "+target.getUsername(),event.currentOwnerUsername(),event.currentOwnerName(),event.currentOwnerUsername(),event.currentOwnerName());
            return view(requireAppeal(id,false),operator);
        });
    }

    public List<Policy> policies(ReplayIssueOperator operator) {
        enabled();permissions.requireAdmin(operator);return store.policies();
    }

    public Policy savePolicy(String domain,PolicyRequest body,ReplayIssueOperator operator) {
        enabled();String normalizedDomain=required(domain,"领域",64);
        if(body==null||body.expectedVersion()==null||body.expectedVersion()<0) throw ReplayReopenException.invalid("请提供当前授权版本");
        String reason=required(body.reason(),"授权调整原因",1000);
        if(body.leaderUsernames()==null||body.leaderUsernames().size()>50) throw ReplayReopenException.invalid("授权领导最多50人");
        List<String> leaders=body.leaderUsernames().stream().map(name->required(name,"领导账号",128)).distinct().toList();
        String primary=optional(body.primaryLeaderUsername(),128);
        if((!leaders.isEmpty()&&(primary==null||!leaders.contains(primary)))||(leaders.isEmpty()&&primary!=null)) throw ReplayReopenException.invalid("主审批领导须包含在当前授权集合内");
        return events.inWriteTransaction(()->{
            SysUser actor=permissions.requireAdmin(operator);
            if(!issueDomains().contains(normalizedDomain)) throw ReplayReopenException.invalid("请选择已存在的问题所属领域");
            for(String leader:leaders) if(permissions.active(new ReplayIssueOperator(leader,null))==null) throw ReplayReopenException.invalid("授权领导必须是有效账号");
            LocalDateTime at=now();Policy result=store.savePolicy(normalizedDomain,leaders,primary==null?"":primary,body.expectedVersion(),actor.getUsername(),at);
            events.appendAudit(new AuditEntry(0,null,null,"POLICY_UPDATED",actor.getUsername(),actor.getRealName(),at,reason,writeJson(result),null,null,null,null),normalizedDomain);
            return result;
        });
    }

    public BackfillResult backfill(long afterRoundEntryId,int limit,ReplayIssueOperator operator) {
        enabled();
        if(afterRoundEntryId<0||limit<1||limit>1000) throw ReplayReopenException.invalid("历史回填游标或条数不合法");
        return events.inWriteTransaction(()->{permissions.requireAdmin(operator);return recorder.backfill(afterRoundEntryId,limit,operator);});
    }

    private EventItem item(EventRow event,ReplayIssueOperator operator) {
        SysUser actor=permissions.active(operator);
        boolean owner=actor!=null&&Objects.equals(actor.getUsername(),event.currentOwnerUsername());
        return new EventItem(event,owner&&event.pendingAppealId()==null,store.hasDecision(event.id()),
                (event.currentOwnerUsername()==null||event.currentOwnerUsername().isBlank())&&event.pendingAppealId()==null&&permissions.isLeader(event.issueDomain(),operator),store.latestAppeal(event.id()));
    }

    private AppealView view(AppealRow a,ReplayIssueOperator operator) {
        SysUser actor=permissions.active(operator);List<AppealItem> items=store.items(a.id());
        boolean pending="PENDING".equals(a.status());String username=actor==null?null:actor.getUsername();
        boolean canDecide=pending&&username!=null&&username.equals(a.leaderUsername())&&permissions.isLeader(a.issueDomain(),operator)
                &&!Objects.equals(username,a.applicantUsername())&&!Objects.equals(username,a.suggestedOwnerUsername())
                &&items.stream().noneMatch(i->Objects.equals(username,i.beforeOwnerUsername())||Objects.equals(username,requireEvent(i.eventId()).defaultOwnerUsername()));
        return new AppealView(a.id(),a.version(),a.kind(),a.status(),a.issueDomain(),items,a.applicantUsername(),a.applicantName(),
                a.suggestedOwnerUsername(),a.suggestedOwnerName(),a.finalOwnerUsername(),a.finalOwnerName(),a.leaderUsername(),a.leaderName(),
                a.reason(),a.evidence(),a.submittedAt(),a.decidedAt(),a.decisionReason(),a.closedAt(),a.closeReason(),canDecide,
                pending&&username!=null&&username.equals(a.applicantUsername()),pending&&permissions.canManage(operator));
    }

    private SysUser routeLeader(String domain,List<EventRow> selected,String applicant,String suggested) {
        SysUser primary=permissions.primaryLeader(domain);
        Set<String> involved=new HashSet<>(List.of(applicant,suggested));
        selected.forEach(event->{if(event.defaultOwnerUsername()!=null)involved.add(event.defaultOwnerUsername());});
        if(!involved.contains(primary.getUsername())) return primary;
        Policy policy=store.findPolicy(domain,false);
        return policy.leaderUsernames().stream().filter(name->!involved.contains(name))
                .map(name->permissions.active(new ReplayIssueOperator(name,null))).filter(Objects::nonNull).findFirst()
                .orElseThrow(()->ReplayReopenException.conflict("当前领导涉及本人责任，请管理员配置其他授权领导"));
    }

    private boolean involved(AppealRow appeal,List<EventRow> selected,String username,String finalOwner) {
        return Objects.equals(username,appeal.applicantUsername())||Objects.equals(username,appeal.suggestedOwnerUsername())
                ||Objects.equals(username,finalOwner)||selected.stream().anyMatch(e->Objects.equals(username,e.currentOwnerUsername())||Objects.equals(username,e.defaultOwnerUsername()));
    }

    private AppealView sameRequest(AppealRow existing,SysUser actor,String fingerprint,ReplayIssueOperator operator) {
        if(!actor.getUsername().equals(existing.applicantUsername())) throw ReplayReopenException.forbidden("该请求标识不属于当前申请人");
        if(!fingerprint.equals(existing.requestFingerprint())) throw ReplayReopenException.conflict("同一请求标识不能提交不同内容");
        return view(existing,operator);
    }

    private void audit(EventRow event,Long appealId,String action,SysUser actor,LocalDateTime at,String reason,String evidence,
                       String from,String fromName,String to,String toName) {
        events.appendAudit(new AuditEntry(0,event.id(),appealId,action,actor.getUsername(),actor.getRealName(),at,reason,evidence,from,fromName,to,toName),event.issueDomain());
    }

    private List<EventRow> lockAll(List<Long> ids) {
        List<EventRow> selected=events.lockEvents(ids);
        if(ids.isEmpty()||selected.size()!=ids.size()) throw ReplayReopenException.notFound("选中的重开记录不存在");
        return selected;
    }

    private EventRow requireEvent(long id) {
        EventRow event=events.findEvent(id);
        if(event==null) throw ReplayReopenException.notFound("重开记录不存在");return event;
    }

    private AppealRow requireAppeal(long id,boolean lock) {
        AppealRow a=store.findAppeal(id,lock);
        if(a==null) throw ReplayReopenException.notFound("调整申请不存在");return a;
    }

    private record LockedAppeal(AppealRow appeal,List<EventRow> events) {}

    private LockedAppeal lockAppeal(long id) {
        // Every write locks events -> appeal -> policy; a retry uses the same order.
        requireAppeal(id,false);
        List<EventRow> selected=lockAll(store.items(id).stream().map(AppealItem::eventId).toList());
        return new LockedAppeal(requireAppeal(id,true),selected);
    }

    private void requirePendingVersion(AppealRow a,long expected) {
        if(!"PENDING".equals(a.status())||a.version()!=expected) throw ReplayReopenException.conflict("申请已处理或版本已变化，请刷新");
    }

    private CreateAppealRequest normalizeRequest(CreateAppealRequest body) {
        if(body==null||body.eventIds()==null||body.eventIds().isEmpty()||body.eventIds().size()>50) throw ReplayReopenException.invalid("单份申请须选择1至50次重开");
        if(body.eventIds().stream().anyMatch(id->id==null||id<=0)||body.eventIds().stream().distinct().count()!=body.eventIds().size()) throw ReplayReopenException.invalid("重开记录不能重复或为空");
        String requestId=required(body.requestId(),"请求标识",128);
        try { if(!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException ex) {throw ReplayReopenException.invalid("请求标识必须是有效UUID");}
        List<Long> ids=body.eventIds().stream().sorted().toList();Map<String,Long> versions=new TreeMap<>();
        for(long id:ids) versions.put(String.valueOf(id),version(body.expectedVersions()==null?null:body.expectedVersions().get(String.valueOf(id))));
        String kind=required(body.kind(),"申请类型",16).toUpperCase(Locale.ROOT);
        if(!Set.of("APPEAL","RECHECK").contains(kind)) throw ReplayReopenException.invalid("申请类型不合法");
        return new CreateAppealRequest(requestId.toLowerCase(Locale.ROOT),ids,versions,kind,required(body.suggestedOwnerUsername(),"建议开发负责人",128),required(body.reason(),"调整原因",1000),required(body.evidence(),"相关证据",4000));
    }

    private Filter normalize(Filter filter,ReplayIssueOperator operator) {
        Filter f=filter==null?new Filter(null,null,null,"ALL",null,null,false,1,20):filter;
        SysUser actor=f.onlyMine()?permissions.active(operator):null;
        if(f.eventId()!=null&&f.eventId()<=0) throw ReplayReopenException.invalid("重开记录编号不合法");
        if(f.sourceRoundId()!=null&&f.sourceRoundId()<=0) throw ReplayReopenException.invalid("来源导入轮次必须是正整数");
        return new Filter(optional(f.issueKey(),1024),optional(f.batchName(),128),optional(f.issueDomain(),64),replayType(f.replayType()),
                f.onlyMine()?(actor==null?null:actor.getUsername()):optional(f.ownerUsername(),128),f.eventId(),f.onlyMine(),Math.max(1,f.page()),f.pageSize()<=0?20:Math.min(200,f.pageSize()),f.sourceRoundId());
    }

    private List<String> issueDomains() {
        LinkedHashSet<String> domains=new LinkedHashSet<>(ReplayIssueDomainService.ALLOWED_DOMAINS);
        store.policies().forEach(p->domains.add(p.issueDomain()));
        domains.addAll(events.jdbc().queryForList("SELECT DISTINCT issue_domain FROM dii_replay_reopen_event WHERE issue_domain IS NOT NULL",String.class));
        return domains.stream().filter(d->d!=null&&!d.isBlank()).toList();
    }

    private String fingerprint(CreateAppealRequest request,String actor) {
        return sha256(writeJson(List.of(actor,request.eventIds(),request.expectedVersions(),request.kind(),request.suggestedOwnerUsername(),request.reason(),request.evidence())));
    }

    static String evidenceHash(String evidence) {
        return sha256(Normalizer.normalize(evidence,Normalizer.Form.NFKC).replaceAll("\\s+","").toLowerCase(Locale.ROOT));
    }

    private static String sha256(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }

    private String writeJson(Object value) {
        try {return json.writeValueAsString(value);} catch(JsonProcessingException ex) {throw new IllegalStateException("无法保存责任调整信息",ex);}
    }

    private static long version(Long value) {
        if(value==null||value<1) throw ReplayReopenException.invalid("请提供当前记录版本");return value;
    }

    private static String required(String value,String label,int max) {
        String result=optional(value,max);if(result==null) throw ReplayReopenException.invalid("请填写"+label);return result;
    }

    private static String optional(String value,int max) {
        if(value==null||value.isBlank())return null;String result=value.trim();
        if(result.length()>max)throw ReplayReopenException.invalid("内容长度不能超过"+max+"个字符");return result;
    }

    private static String replayType(String value) {
        try{return ReplayIssueReplayType.parse(value).name();}catch(IllegalArgumentException ex){throw ReplayReopenException.invalid(ex.getMessage());}
    }

    private LocalDateTime now() {return LocalDateTime.now(clock);}
    private void enabled() {
        if(!properties.isEnabled()) throw new ReplayReopenException(503,"REOPEN_DISABLED","重开责任台账尚未启用，请联系系统管理员");
    }
}
