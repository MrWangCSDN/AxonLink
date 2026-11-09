package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;
import static com.axonlink.ai.replay.reopen.ReplayReopenWorkflowDtos.*;

/** Independent API: account identities always come from the authenticated principal. */
@RestController
@RequestMapping("/api/ai/parallel-replay/issues/reopens")
public class ReplayReopenController {
    private final ReplayReopenService service;
    private final UserPrincipalResolver resolver;

    public ReplayReopenController(ReplayReopenService service,UserPrincipalResolver resolver) {
        this.service=service;this.resolver=resolver;
    }

    @GetMapping("/permissions")
    public R<Permissions> permissions(HttpServletRequest request) {return R.ok(service.permissions(operator(request)));}

    @GetMapping("/filters")
    public R<Map<String,List<String>>> filters(@RequestParam(required=false) String replayType) {return R.ok(service.filters(replayType));}

    @GetMapping("/developers")
    public R<List<UserOption>> developers(@RequestParam MultiValueMap<String,String> query) {
        return R.ok(service.developers(query.getFirst("keyword"),integer(query,"limit",20)));
    }

    @GetMapping("/events")
    public R<Page<EventItem>> events(@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        return R.ok(service.events(filter(query),operator(request)));
    }

    @GetMapping("/events/{id}")
    public R<EventItem> event(@PathVariable long id,HttpServletRequest request) {return R.ok(service.event(id,operator(request)));}

    @GetMapping("/events/{id}/history")
    public R<Page<AuditEntry>> history(@PathVariable long id,@RequestParam MultiValueMap<String,String> query) {
        return R.ok(service.history(id,integer(query,"page",1),integer(query,"pageSize",20)));
    }

    public record IssueCountsRequest(List<String> issueKeys) {}

    @PostMapping("/issue-counts")
    public R<Map<String,Long>> issueCounts(@RequestBody(required=false) IssueCountsRequest body) {
        return R.ok(service.issueCounts(body==null?null:body.issueKeys()));
    }

    @GetMapping("/statistics")
    public R<Statistics> statistics(@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        return R.ok(service.statistics(filter(query),operator(request)));
    }

    @GetMapping("/appeals")
    public R<Page<AppealView>> appeals(@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        return R.ok(service.appeals(filter(query),query.getFirst("status"),bool(query,"onlyMine"),bool(query,"assignedToMe"),operator(request)));
    }

    @GetMapping("/appeals/{id}")
    public R<AppealView> appeal(@PathVariable long id,HttpServletRequest request) {return R.ok(service.appeal(id,operator(request)));}

    @PostMapping("/appeals")
    public R<AppealView> create(@RequestBody(required=false) CreateAppealRequest body,HttpServletRequest request) {
        return R.ok(service.createAppeal(body,operator(request)));
    }

    @PostMapping("/appeals/{id}/decisions")
    public R<AppealView> decide(@PathVariable long id,@RequestBody(required=false) DecisionRequest body,HttpServletRequest request) {
        return R.ok(service.decide(id,body,operator(request)));
    }

    @PostMapping("/appeals/{id}/withdraw")
    public R<AppealView> withdraw(@PathVariable long id,@RequestBody(required=false) WithdrawRequest body,HttpServletRequest request) {
        return R.ok(service.withdraw(id,body,operator(request)));
    }

    @PostMapping("/appeals/{id}/reassign")
    public R<AppealView> reassign(@PathVariable long id,@RequestBody(required=false) ReassignRequest body,HttpServletRequest request) {
        return R.ok(service.reassign(id,body,operator(request)));
    }

    @PostMapping("/events/{id}/assign")
    public R<EventItem> assign(@PathVariable long id,@RequestBody(required=false) AssignRequest body,HttpServletRequest request) {
        return R.ok(service.assign(id,body,operator(request)));
    }

    @GetMapping("/policies")
    public R<List<Policy>> policies(HttpServletRequest request) {return R.ok(service.policies(operator(request)));}

    @PutMapping("/policies/{domain}")
    public R<Policy> policy(@PathVariable String domain,@RequestBody(required=false) PolicyRequest body,HttpServletRequest request) {
        return R.ok(service.savePolicy(domain,body,operator(request)));
    }

    public record BackfillRequest(Long afterRoundEntryId,Integer limit) {}

    @PostMapping("/backfill")
    public R<BackfillResult> backfill(@RequestBody(required=false) BackfillRequest body,HttpServletRequest request) {
        return R.ok(service.backfill(body==null||body.afterRoundEntryId()==null?0:body.afterRoundEntryId(),
                body==null||body.limit()==null?500:body.limit(),operator(request)));
    }

    private ReplayIssueOperator operator(HttpServletRequest request) {
        UserPrincipalResolver.Resolved resolved=resolver.resolve(request);
        if(resolved==null||resolved.user==null) return null;
        return new ReplayIssueOperator(resolved.user.getUsername(),resolved.user.getRealName());
    }

    private static Filter filter(MultiValueMap<String,String> query) {
        return new Filter(query.getFirst("issueKey"),query.getFirst("batchName"),query.getFirst("issueDomain"),query.getFirst("replayType"),
                query.getFirst("ownerUsername"),number(query,"eventId"),bool(query,"onlyMine"),integer(query,"page",1),integer(query,"pageSize",20),number(query,"sourceRoundId"));
    }

    private static int integer(MultiValueMap<String,String> query,String name,int fallback) {
        String value=query.getFirst(name);if(value==null||value.isBlank())return fallback;
        try{return Integer.parseInt(value);}catch(NumberFormatException ex){throw ReplayReopenException.invalid(name+"必须是整数");}
    }

    private static Long number(MultiValueMap<String,String> query,String name) {
        String value=query.getFirst(name);if(value==null||value.isBlank())return null;
        try{return Long.parseLong(value);}catch(NumberFormatException ex){throw ReplayReopenException.invalid(name+"必须是整数");}
    }

    private static boolean bool(MultiValueMap<String,String> query,String name) {
        String value=query.getFirst(name);if(value==null||value.isBlank())return false;
        if("true".equalsIgnoreCase(value))return true;if("false".equalsIgnoreCase(value))return false;
        throw ReplayReopenException.invalid(name+"必须是true或false");
    }

    @ExceptionHandler(ReplayReopenException.class)
    public ResponseEntity<R<Map<String,String>>> businessFailure(ReplayReopenException ex) {
        return ResponseEntity.status(ex.status()).body(R.fail(ex.status(),ex.getMessage(),Map.of("errorCode",ex.errorCode())));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    public ResponseEntity<R<Map<String,String>>> invalidRequest(Exception ex) {
        return ResponseEntity.badRequest().body(R.fail(400,"请求参数格式不合法",Map.of("errorCode","VALIDATION")));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<R<Map<String,String>>> staleRequest(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(409).body(R.fail(409,"记录已变化，请刷新后重试",Map.of("errorCode","CONFLICT")));
    }
}
