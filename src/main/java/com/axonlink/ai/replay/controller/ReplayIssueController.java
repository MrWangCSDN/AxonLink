package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueAffectedTransactionCountOrder;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueFullRefreshResult;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayIssueUpdateRequest;
import com.axonlink.ai.replay.dto.ReplayIssueHistoryEntry;
import com.axonlink.ai.replay.dto.ReplayImportRound;
import com.axonlink.ai.replay.dto.ReplayIssueGroupSummary;
import com.axonlink.ai.replay.dto.ReplayIssueHeaderFilterOptionResult;
import com.axonlink.ai.replay.dto.ReplayIssuePersonRanking;
import com.axonlink.ai.replay.dto.ReplayIssueRoundEntry;
import com.axonlink.ai.replay.dto.ReplayIssueRoundTrackingGroup;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.ai.replay.dto.ReplayIssueMailSendRequest;
import com.axonlink.ai.replay.dto.ReplayIssueWeeklyTaskConfig;
import com.axonlink.ai.replay.dto.ReplayIssueWeeklyTaskUpdateRequest;
import com.axonlink.ai.replay.dto.ReplayIssueReviewPermissions;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDatePermissions;
import com.axonlink.ai.replay.dto.ReplayIssuePlannedCompletionDateUpdateRequest;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDashboard;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDatePointsResponse;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionIssuePage;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.service.ReplayIssueImportBusyException;
import com.axonlink.ai.replay.service.ReplayIssueImportService;
import com.axonlink.ai.replay.service.ReplayIssueImportMode;
import com.axonlink.ai.replay.service.ReplayIssueFullRefreshService;
import com.axonlink.ai.replay.service.ReplayIssueEditService;
import com.axonlink.ai.replay.service.ReplayIssueMailService;
import com.axonlink.ai.replay.service.ReplayIssueDailyReportService;
import com.axonlink.ai.replay.service.ReplayIssueWeeklyTaskService;
import com.axonlink.ai.replay.service.ReplayIssueReviewService;
import com.axonlink.ai.replay.service.ReplayIssueReviewForbiddenException;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateService;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateForbiddenException;
import com.axonlink.ai.replay.service.ReplayIssueDomainForbiddenException;
import com.axonlink.ai.replay.service.ReplayIssueDomainService;
import com.axonlink.ai.replay.service.ReplayIssueCompletionStatsService;
import com.axonlink.security.UserPrincipalResolver;
import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** HTTP API for importing and querying the active parallel replay issue snapshot. */
@RestController
@RequestMapping("/api/ai/parallel-replay/issues")
public class ReplayIssueController {

    private static final Logger log = LoggerFactory.getLogger(ReplayIssueController.class);
    private static final String INHERITED_CONTENT_OPERATION = "基础数据覆盖，人工内容继承";

    private final ReplayIssueImportService importService;
    private final ReplayIssueFullRefreshService fullRefreshService;
    private final ReplayIssueDao dao;
    private final DaoIndexAnalysisProperties properties;
    private final ReplayIssueEditService editService;
    private final UserPrincipalResolver userResolver;
    private final ReplayIssueDailyReportService dailyReportService;
    private final ReplayIssueWeeklyTaskService weeklyTaskService;

    @org.springframework.beans.factory.annotation.Autowired
    private ReplayIssueMailService issueMailService;

    @org.springframework.beans.factory.annotation.Autowired
    private ReplayIssueReviewService reviewService;

    @org.springframework.beans.factory.annotation.Autowired
    private ReplayIssuePlanDateService planDateService;

    @org.springframework.beans.factory.annotation.Autowired
    private ReplayIssueCompletionStatsService completionStatsService;

    @org.springframework.beans.factory.annotation.Autowired
    private ReplayIssueDomainService issueDomainService;

    public ReplayIssueController(ReplayIssueImportService importService,
                                 ReplayIssueFullRefreshService fullRefreshService, ReplayIssueDao dao,
                                 DaoIndexAnalysisProperties properties, ReplayIssueEditService editService,
                                 UserPrincipalResolver userResolver,
                                 ReplayIssueDailyReportService dailyReportService,
                                 ReplayIssueWeeklyTaskService weeklyTaskService) {
        this.importService = importService;
        this.fullRefreshService = fullRefreshService;
        this.dao = dao;
        this.properties = properties;
        this.editService = editService;
        this.userResolver = userResolver;
        this.dailyReportService = dailyReportService;
        this.weeklyTaskService = weeklyTaskService;
    }

    @GetMapping("/weekly-task")
    public R<ReplayIssueWeeklyTaskConfig> weeklyTask() {
        return R.ok(weeklyTaskService.current());
    }

    @PutMapping("/weekly-task")
    public ResponseEntity<R<ReplayIssueWeeklyTaskConfig>> replaceWeeklyTask(
            @RequestBody(required = false) ReplayIssueWeeklyTaskUpdateRequest body,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        String expected = properties.getBatchTrigger().getToken();
        if (expected != null && !expected.trim().isEmpty() && !expected.equals(token)) {
            log.warn("[replay-issue] weekly task token rejected remoteAddr={} hasToken={}",
                    request.getRemoteAddr(), token != null);
            return error(HttpStatus.UNAUTHORIZED, "口令错误");
        }
        try {
            List<String> batchNames = body == null || body.batchNames() == null ? List.of() : body.batchNames();
            return ResponseEntity.ok(R.ok(weeklyTaskService.replace(batchNames)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("[replay-issue] priority task replacement failed", exception);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "优先任务配置失败，原配置未改变");
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<R<ReplayIssueRow>> update(@PathVariable long id,
                                                     @RequestBody ReplayIssueUpdateRequest body,
                                                     HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) {
            return error(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        try {
            return ResponseEntity.ok(R.ok(editService.update(id, body, operator)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/review-permissions")
    public ResponseEntity<R<ReplayIssueReviewPermissions>> reviewPermissions(HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        return ResponseEntity.ok(R.ok(reviewService.permissions(operator)));
    }

    @GetMapping("/plan-date-permissions")
    public ResponseEntity<R<ReplayIssuePlanDatePermissions>> planDatePermissions(HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        return ResponseEntity.ok(R.ok(planDateService.permissions(operator)));
    }

    @GetMapping("/issue-domain-permissions")
    public ResponseEntity<R<com.axonlink.ai.replay.dto.ReplayIssueDomainPermissions>> issueDomainPermissions(
            HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        return ResponseEntity.ok(R.ok(issueDomainService.permissions(operator)));
    }

    @PatchMapping("/{id}/issue-domain")
    public ResponseEntity<R<com.axonlink.ai.replay.dto.ReplayIssueDomainUpdateResult>> updateIssueDomain(
            @PathVariable long id,
            @RequestBody(required = false) com.axonlink.ai.replay.dto.ReplayIssueDomainUpdateRequest body,
            HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            return ResponseEntity.ok(R.ok(issueDomainService.update(
                    id, body == null ? null : body.issueDomain(), operator)));
        } catch (ReplayIssueDomainForbiddenException exception) {
            return error(HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            HttpStatus status = "回放问题不存在".equals(exception.getMessage())
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return error(status, exception.getMessage());
        }
    }

    @GetMapping("/{id}/issue-domain-transfers")
    public ResponseEntity<R<com.axonlink.ai.replay.dto.ReplayIssueDomainTransfers>> issueDomainTransfers(
            @PathVariable long id, HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            return ResponseEntity.ok(R.ok(issueDomainService.transfers(id)));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = "回放问题不存在".equals(exception.getMessage())
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return error(status, exception.getMessage());
        }
    }

    @PatchMapping("/{id}/planned-completion-date")
    public ResponseEntity<R<com.axonlink.ai.replay.dto.ReplayIssuePlanDateUpdateResult>> updatePlannedCompletionDate(
            @PathVariable long id,
            @RequestBody(required = false) ReplayIssuePlannedCompletionDateUpdateRequest body,
            HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            return ResponseEntity.ok(R.ok(planDateService.update(id,
                    body == null ? null : body.plannedCompletionDate(), operator)));
        } catch (ReplayIssuePlanDateForbiddenException exception) {
            return error(HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            HttpStatus status = "回放问题不存在".equals(exception.getMessage())
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return error(status, exception.getMessage());
        }
    }

    @GetMapping("/{id}/planned-completion-date-changes")
    public ResponseEntity<R<com.axonlink.ai.replay.dto.ReplayIssuePlanDateChanges>> plannedCompletionDateChanges(
            @PathVariable long id, HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            return ResponseEntity.ok(R.ok(planDateService.changes(id)));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = "回放问题不存在".equals(exception.getMessage())
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return error(status, exception.getMessage());
        }
    }

    @PostMapping("/{id}/review/approve")
    public ResponseEntity<R<ReplayIssueRow>> approveReview(@PathVariable long id, HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            return ResponseEntity.ok(R.ok(reviewService.approve(id, operator)));
        } catch (ReplayIssueReviewForbiddenException exception) {
            return error(HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/{id}/mail-status")
    public ResponseEntity<R<ReplayIssueMailStatus>> mailStatus(@PathVariable long id,
                                                               HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            ReplayIssueRow issue = dao.findCurrentByIdForUpdate(id);
            if (issue == null) return error(HttpStatus.NOT_FOUND, "回放问题不存在");
            return ResponseEntity.ok(R.ok(issueMailService.status(issue, operator)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/{id}/mail-send")
    public ResponseEntity<R<ReplayIssueMailStatus>> sendMail(@PathVariable long id,
                                                              @RequestBody(required = false) ReplayIssueMailSendRequest body,
                                                              HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            ReplayIssueRow issue = dao.findCurrentByIdForUpdate(id);
            if (issue == null) return error(HttpStatus.NOT_FOUND, "回放问题不存在");
            return ResponseEntity.ok(R.ok(issueMailService.requestSend(
                    issue, body == null ? null : body.recipientEmails(), operator)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<ReplayIssueImportResult>> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "replayType", required = false) String replayType,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        String expected = properties.getBatchTrigger().getToken();
        if (expected != null && !expected.trim().isEmpty()
                && (token == null || !expected.equals(token))) {
            log.warn("[replay-issue] import token rejected remoteAddr={} hasToken={}",
                    request.getRemoteAddr(), token != null);
            return error(HttpStatus.UNAUTHORIZED, "口令错误");
        }
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "文件为空");
        }
        String filename = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            return error(HttpStatus.BAD_REQUEST, "仅支持 .xlsx / .xls");
        }
        try {
            ReplayIssueImportMode mode = ReplayIssueImportMode.fromRequest(replayType);
            return ResponseEntity.ok(R.ok(importService.importFile(file, mode)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (ReplayIssueImportBusyException exception) {
            return error(HttpStatus.CONFLICT, "已有导入任务正在执行");
        } catch (Exception exception) {
            log.error("[replay-issue] import failed file={}", file.getOriginalFilename(), exception);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "导入失败");
        }
    }

    @PostMapping(value = "/full-refresh", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<ReplayIssueFullRefreshResult>> fullRefresh(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "confirm", required = false) String confirm,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ReplayIssueOperator operator = resolveOperator(request);
        if (operator == null) operator = ReplayIssueOperator.system();
        String expected = properties.getBatchTrigger().getToken();
        if (expected != null && !expected.trim().isEmpty()
                && (token == null || !expected.equals(token))) {
            log.warn("[replay-issue] full refresh token rejected remoteAddr={} hasToken={}",
                    request.getRemoteAddr(), token != null);
            return error(HttpStatus.UNAUTHORIZED, "口令错误");
        }
        if (!"FULL_REFRESH".equals(confirm)) {
            return error(HttpStatus.BAD_REQUEST, "请确认 FULL_REFRESH 后再执行全量更新");
        }
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "文件为空");
        }
        String filename = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            return error(HttpStatus.BAD_REQUEST, "仅支持 .xlsx / .xls");
        }
        try {
            return ResponseEntity.ok(R.ok(fullRefreshService.fullRefresh(file, operator)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (ReplayIssueImportBusyException exception) {
            return error(HttpStatus.CONFLICT, "已有导入任务正在执行");
        } catch (Exception exception) {
            log.error("[replay-issue] full refresh failed file={}", file.getOriginalFilename(), exception);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "全量更新失败，原有数据未变更");
        }
    }

    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) Boolean sandbox,
            @RequestParam(required = false) String issueLevel,
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String issueStatus,
            @RequestParam(required = false) String developer,
            @RequestParam(required = false) String bankOwner,
            @RequestParam(required = false) String cooperationPerson,
            @RequestParam(required = false) String serialNo,
            @RequestParam(required = false) String globalSerialNo,
            @RequestParam(required = false) String defectRepairDate,
            @RequestParam(required = false) String coverageRound,
            @RequestParam(required = false) List<String> transactionCodes,
            @RequestParam(required = false) List<String> issueLevels,
            @RequestParam(required = false) List<String> developers,
            @RequestParam(required = false) List<String> bankOwners,
            @RequestParam(required = false) List<String> issueStatuses,
            @RequestParam(required = false) List<String> issueTypes,
            @RequestParam(required = false) List<String> cooperationPersons,
            @RequestParam(required = false) List<String> occurrenceBatches,
            @RequestParam(required = false) Boolean weeklyTask,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) List<String> reviewStatuses,
            @RequestParam(required = false) String issueId,
            @RequestParam(required = false) List<String> groupNames,
            @RequestParam(required = false) List<String> issueDomains,
            @RequestParam(required = false) List<String> sandboxes,
            @RequestParam(required = false) List<String> plannedCompletionDates,
            @RequestParam(required = false) List<String> issueIds,
            @RequestParam(required = false) List<String> serialNos,
            @RequestParam(required = false) List<String> globalSerialNos,
            @RequestParam(required = false) List<String> defectRepairDates,
            @RequestParam(required = false) List<String> transactionNames,
            @RequestParam(required = false) List<String> fieldNames,
            @RequestParam(required = false) List<String> issueDescriptions,
            @RequestParam(required = false) List<String> issueKeys,
            @RequestParam(required = false) ReplayIssueAffectedTransactionCountOrder affectedTransactionCountOrder) {
        ReplayIssueQuery query = new ReplayIssueQuery(limit, offset, groupName,
                sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner, cooperationPerson,
                serialNo, globalSerialNo, defectRepairDate, coverageRound,
                safe(transactionCodes), safe(issueLevels), safe(developers), safe(bankOwners), safe(issueStatuses), safe(issueTypes), safe(cooperationPersons), safe(occurrenceBatches), weeklyTask,
                reviewStatus, safe(reviewStatuses), issueId, safe(groupNames), safe(sandboxes), safe(plannedCompletionDates),
                safe(issueIds), safe(serialNos), safe(globalSerialNos), safe(defectRepairDates),
                safe(transactionNames), safe(fieldNames), safe(issueDescriptions), safe(issueKeys), safe(issueDomains));
        List<Map<String, Object>> items = dao.list(query, affectedTransactionCountOrder).stream()
                .map(ReplayIssueController::lowercaseKeys)
                .toList();
        return R.ok(Map.of("total", dao.count(query), "items", items));
    }

    @GetMapping("/header-filter-options")
    public R<List<String>> headerFilterOptions(@RequestParam String field,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String groupName,
                                                @RequestParam(required = false) Boolean sandbox,
                                                @RequestParam(required = false) String issueLevel,
                                                @RequestParam(required = false) String issueType,
                                                @RequestParam(required = false) String issueStatus,
                                                @RequestParam(required = false) String developer,
                                                @RequestParam(required = false) String bankOwner,
                                                @RequestParam(required = false) String cooperationPerson,
                                                @RequestParam(required = false) String serialNo,
                                                @RequestParam(required = false) String globalSerialNo,
                                                @RequestParam(required = false) String defectRepairDate,
                                                @RequestParam(required = false) String coverageRound,
                                                @RequestParam(required = false) List<String> transactionCodes,
                                                @RequestParam(required = false) List<String> issueLevels,
                                                @RequestParam(required = false) List<String> developers,
                                                @RequestParam(required = false) List<String> bankOwners,
                                                @RequestParam(required = false) List<String> issueStatuses,
                                                @RequestParam(required = false) List<String> issueTypes,
                                                @RequestParam(required = false) List<String> cooperationPersons,
                                                @RequestParam(required = false) List<String> occurrenceBatches,
                                                @RequestParam(required = false) Boolean weeklyTask,
                                                @RequestParam(required = false) String reviewStatus,
                                                @RequestParam(required = false) List<String> reviewStatuses,
                                                @RequestParam(required = false) String issueId,
                                                @RequestParam(required = false) List<String> groupNames,
                                                @RequestParam(required = false) List<String> issueDomains,
                                                @RequestParam(required = false) List<String> sandboxes,
                                                @RequestParam(required = false) List<String> plannedCompletionDates,
                                                @RequestParam(required = false) List<String> issueIds,
                                                @RequestParam(required = false) List<String> serialNos,
                                                @RequestParam(required = false) List<String> globalSerialNos,
                                                @RequestParam(required = false) List<String> defectRepairDates,
                                                @RequestParam(required = false) List<String> transactionNames,
                                                @RequestParam(required = false) List<String> fieldNames,
                                                @RequestParam(required = false) List<String> issueDescriptions,
                                                @RequestParam(required = false) List<String> issueKeys) {
        ReplayIssueQuery query = new ReplayIssueQuery(500, 0, groupName, sandbox, issueLevel, issueType, null,
                issueStatus, developer, bankOwner, cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound,
                safe(transactionCodes), safe(issueLevels), safe(developers), safe(bankOwners), safe(issueStatuses), safe(issueTypes), safe(cooperationPersons), safe(occurrenceBatches), weeklyTask,
                reviewStatus, safe(reviewStatuses), issueId, safe(groupNames), safe(sandboxes), safe(plannedCompletionDates),
                safe(issueIds), safe(serialNos), safe(globalSerialNos), safe(defectRepairDates),
                safe(transactionNames), safe(fieldNames), safe(issueDescriptions), safe(issueKeys), safe(issueDomains));
        return R.ok(dao.headerFilterValues(field, query, keyword));
    }

    @GetMapping("/header-filter-option-counts")
    public R<ReplayIssueHeaderFilterOptionResult> headerFilterOptionCounts(
            @RequestParam String field,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) Boolean sandbox,
            @RequestParam(required = false) String issueLevel,
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String issueStatus,
            @RequestParam(required = false) String developer,
            @RequestParam(required = false) String bankOwner,
            @RequestParam(required = false) String cooperationPerson,
            @RequestParam(required = false) String serialNo,
            @RequestParam(required = false) String globalSerialNo,
            @RequestParam(required = false) String defectRepairDate,
            @RequestParam(required = false) String coverageRound,
            @RequestParam(required = false) List<String> transactionCodes,
            @RequestParam(required = false) List<String> issueLevels,
            @RequestParam(required = false) List<String> developers,
            @RequestParam(required = false) List<String> bankOwners,
            @RequestParam(required = false) List<String> issueStatuses,
            @RequestParam(required = false) List<String> issueTypes,
            @RequestParam(required = false) List<String> cooperationPersons,
            @RequestParam(required = false) List<String> occurrenceBatches,
            @RequestParam(required = false) Boolean weeklyTask,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) List<String> reviewStatuses,
            @RequestParam(required = false) String issueId,
            @RequestParam(required = false) List<String> groupNames,
            @RequestParam(required = false) List<String> issueDomains,
            @RequestParam(required = false) List<String> sandboxes,
            @RequestParam(required = false) List<String> plannedCompletionDates,
            @RequestParam(required = false) List<String> issueIds,
            @RequestParam(required = false) List<String> serialNos,
            @RequestParam(required = false) List<String> globalSerialNos,
            @RequestParam(required = false) List<String> defectRepairDates,
            @RequestParam(required = false) List<String> transactionNames,
            @RequestParam(required = false) List<String> fieldNames,
            @RequestParam(required = false) List<String> issueDescriptions,
            @RequestParam(required = false) List<String> issueKeys) {
        ReplayIssueQuery query = new ReplayIssueQuery(500, 0, groupName, sandbox, issueLevel, issueType, null,
                issueStatus, developer, bankOwner, cooperationPerson, serialNo, globalSerialNo, defectRepairDate,
                coverageRound, safe(transactionCodes), safe(issueLevels), safe(developers), safe(bankOwners),
                safe(issueStatuses), safe(issueTypes), safe(cooperationPersons), safe(occurrenceBatches), weeklyTask,
                reviewStatus, safe(reviewStatuses), issueId, safe(groupNames), safe(sandboxes),
                safe(plannedCompletionDates), safe(issueIds), safe(serialNos), safe(globalSerialNos),
                safe(defectRepairDates), safe(transactionNames), safe(fieldNames), safe(issueDescriptions),
                safe(issueKeys), safe(issueDomains));
        return R.ok(dao.headerFilterOptionCounts(field, query, keyword));
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    @GetMapping("/options")
    public R<ReplayIssueFilterOptions> options() {
        return R.ok(dao.options());
    }

    @GetMapping("/stats")
    public ResponseEntity<R<Map<String, Object>>> stats(
            @RequestParam(defaultValue = "domain") String groupBy) {
        try {
            return ResponseEntity.ok(R.ok(dao.stats(groupBy)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/stats/groups")
    public ResponseEntity<R<List<ReplayIssueGroupSummary>>> groupSummaries(
            @RequestParam(defaultValue = "domain") String groupBy) {
        try {
            return ResponseEntity.ok(R.ok(dao.groupIssueSummaries(groupBy)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/stats/person-ranking")
    public ResponseEntity<R<List<ReplayIssuePersonRanking>>> personRankings(
            @RequestParam(defaultValue = "domain") String groupBy) {
        try {
            return ResponseEntity.ok(R.ok(dao.personIssueRankings(groupBy)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/stats/planned-completion/date-points")
    public R<ReplayIssueCompletionDatePointsResponse> plannedCompletionDatePoints() {
        return R.ok(completionStatsService.datePoints());
    }

    @GetMapping("/stats/planned-completion")
    public ResponseEntity<R<ReplayIssueCompletionDashboard>> plannedCompletionDashboard(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "domain") String groupBy) {
        try {
            return ResponseEntity.ok(R.ok(completionStatsService.dashboard(startDate, endDate, groupBy)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/stats/planned-completion/issues")
    public ResponseEntity<R<ReplayIssueCompletionIssuePage>> plannedCompletionIssues(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "domain") String groupBy,
            @RequestParam String groupName,
            @RequestParam(required = false) String matchedDeveloper,
            @RequestParam String category,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            return ResponseEntity.ok(R.ok(completionStatsService.issues(startDate, endDate, groupBy,
                    groupName, matchedDeveloper, category, limit, offset)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/rounds")
    public R<List<ReplayImportRound>> rounds() {
        return R.ok(dao.listImportRounds());
    }

    @GetMapping("/{id}/round-tracking")
    public R<List<ReplayIssueRoundTrackingGroup>> roundTracking(@PathVariable long id) {
        List<ReplayIssueHistoryEntry> history = dao.findHistoryByIssueId(id, 200);
        Map<String, List<ReplayIssueHistoryEntry>> inheritedByBatch = new LinkedHashMap<>();
        Map<String, List<ReplayIssueHistoryEntry>> manualByBatch = new LinkedHashMap<>();
        List<ReplayIssueHistoryEntry> baseEvents = new ArrayList<>();
        for (ReplayIssueHistoryEntry event : history) {
            String batch = event.occurrenceBatchName();
            if (batch == null || batch.isBlank()) {
                baseEvents.add(event);
            } else if ("人工保存".equals(event.operationType())) {
                manualByBatch.computeIfAbsent(batch, ignored -> new ArrayList<>()).add(event);
            } else if (INHERITED_CONTENT_OPERATION.equals(event.operationType())) {
                inheritedByBatch.computeIfAbsent(batch, ignored -> new ArrayList<>()).add(event);
            }
        }
        List<ReplayIssueRoundTrackingGroup> result = new ArrayList<>();
        for (String batch : dao.occurrenceBatchNames(id)) {
            List<ReplayIssueHistoryEntry> manual = manualByBatch.getOrDefault(batch, List.of());
            List<ReplayIssueHistoryEntry> inherited = inheritedByBatch.getOrDefault(batch, List.of());
            ReplayIssueHistoryEntry latest = history.stream().filter(event -> batch.equals(event.occurrenceBatchName())).findFirst().orElse(null);
            ReplayIssueHistoryEntry importEvent = history.stream().filter(event -> batch.equals(event.occurrenceBatchName()) && !"人工保存".equals(event.operationType())).findFirst().orElse(latest);
            result.add(new ReplayIssueRoundTrackingGroup(null, batch, latest == null ? null : latest.operationAt(), true,
                    null, importEvent == null ? null : importEvent.issueStatus(), importEvent == null ? "批次记录" : importEvent.operationType(), null, null, manual.size(),
                    manual.isEmpty() ? latest == null ? null : latest.issueStatus() : manual.get(0).issueStatus(), inherited, manual));
        }
        result.sort(Comparator.comparing(ReplayIssueRoundTrackingGroup::importedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (!baseEvents.isEmpty()) {
            result.add(new ReplayIssueRoundTrackingGroup(null, "基础数据", null, null, null, null,
                    "基础数据/未关联批次", null, null, baseEvents.size(), baseEvents.get(0).issueStatus(),
                    List.of(), baseEvents));
        }
        return R.ok(result);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) Boolean sandbox,
            @RequestParam(required = false) String issueLevel,
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String issueStatus,
            @RequestParam(required = false) String developer,
            @RequestParam(required = false) String bankOwner,
            @RequestParam(required = false) String cooperationPerson,
            @RequestParam(required = false) String serialNo,
            @RequestParam(required = false) String globalSerialNo,
            @RequestParam(required = false) String defectRepairDate,
            @RequestParam(required = false) String coverageRound,
            @RequestParam(required = false) List<String> transactionCodes,
            @RequestParam(required = false) List<String> issueLevels,
            @RequestParam(required = false) List<String> developers,
            @RequestParam(required = false) List<String> bankOwners,
            @RequestParam(required = false) List<String> issueStatuses,
            @RequestParam(required = false) List<String> issueTypes,
            @RequestParam(required = false) List<String> cooperationPersons,
            @RequestParam(required = false) List<String> occurrenceBatches,
            @RequestParam(required = false) Boolean weeklyTask,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) List<String> reviewStatuses,
            @RequestParam(required = false) String issueId,
            @RequestParam(required = false) List<String> groupNames,
            @RequestParam(required = false) List<String> issueDomains,
            @RequestParam(required = false) List<String> sandboxes,
            @RequestParam(required = false) List<String> plannedCompletionDates,
            @RequestParam(required = false) List<String> issueIds,
            @RequestParam(required = false) List<String> serialNos,
            @RequestParam(required = false) List<String> globalSerialNos,
            @RequestParam(required = false) List<String> defectRepairDates,
            @RequestParam(required = false) List<String> transactionNames,
            @RequestParam(required = false) List<String> fieldNames,
            @RequestParam(required = false) List<String> issueDescriptions,
            @RequestParam(required = false) List<String> issueKeys,
            @RequestParam(required = false) ReplayIssueAffectedTransactionCountOrder affectedTransactionCountOrder) {
        ReplayIssueQuery query = new ReplayIssueQuery(200, 0, groupName, sandbox, issueLevel, issueType, keyword,
                issueStatus, developer, bankOwner, cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound,
                safe(transactionCodes), safe(issueLevels), safe(developers), safe(bankOwners), safe(issueStatuses), safe(issueTypes), safe(cooperationPersons), safe(occurrenceBatches), weeklyTask,
                reviewStatus, safe(reviewStatuses), issueId, safe(groupNames), safe(sandboxes), safe(plannedCompletionDates),
                safe(issueIds), safe(serialNos), safe(globalSerialNos), safe(defectRepairDates),
                safe(transactionNames), safe(fieldNames), safe(issueDescriptions), safe(issueKeys), safe(issueDomains));
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("回放问题清单");
            String[] headers = {"issue_id", "是否沙箱", "交易码", "交易名称", "问题级别", "字段名", "流水号", "全局流水号", "问题描述",
                    "优先任务", "领域", "问题所属领域", "计划验证日期", "缺陷修复日期", "开发负责人", "科技负责人", "问题状态", "审核状态", "审核人", "审核时间", "问题类型", "需协同人", "初步问题分析", "最终处理方案", "备注",
                    "出现笔数", "issue_key", "首次出现日期", "出现批次"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            int rowIndex = 1;
            for (Map<String, Object> item : dao.listForExport(query, affectedTransactionCountOrder)) {
                Row row = sheet.createRow(rowIndex++);
                Object[] values = {text(item.get("issue_id")), sandboxText(item.get("is_sandbox")), text(item.get("transaction_code")),
                        text(item.get("transaction_name")), text(item.get("issue_level")), text(item.get("field_name")), text(item.get("serial_no")), text(item.get("global_serial_no")),
                        text(item.get("issue_description")), weeklyTaskText(item.get("weekly_task")), text(item.get("domain")), text(item.get("issue_domain")),
                        text(item.get("planned_completion_date")), text(item.get("defect_repair_date")), text(item.get("matched_developer")), text(item.get("matched_bank_owner")), text(item.get("issue_status")), text(item.get("review_status")), text(item.get("reviewer_real_name")), text(item.get("reviewed_at")), text(item.get("issue_type")), personText(item), text(item.get("initial_analysis")),
                        text(item.get("final_solution")), text(item.get("remark")), text(item.get("affected_transaction_count")), text(item.get("issue_key")),
                        dateOnlyText(item.get("first_occurrence_date")), text(item.get("occurrence_rounds"))};
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(String.valueOf(values[i]));
            }
            workbook.write(output);
            String filename = URLEncoder.encode("回放问题清单.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename).body(output.toByteArray());
        } catch (Exception exception) {
            log.error("[replay-issue] export failed", exception);
            return ResponseEntity.internalServerError().build();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String dateOnlyText(Object value) {
        String valueText = text(value).trim();
        return valueText.matches("^\\d{4}-\\d{2}-\\d{2}.*$") ? valueText.substring(0, 10) : valueText;
    }

    private static String weeklyTaskText(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0) ? "是" : "-";
    }

    private static String sandboxText(Object value) {
        return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) ? "是" : "否";
    }

    private static String personText(Map<String, Object> item) {
        String name = text(item.get("cooperation_person_real_name"));
        String username = text(item.get("cooperation_person_username"));
        if (!name.isEmpty() && !username.isEmpty()) return name + "(" + username + ")";
        return name.isEmpty() ? username : name;
    }

    @GetMapping("/{id}/tracking")
    public R<List<ReplayIssueHistoryEntry>> tracking(@PathVariable long id,
                                                      @RequestParam(defaultValue = "200") int limit) {
        return R.ok(dao.findHistoryByIssueId(id, limit));
    }

    /**
     * 列出可下载的日报批次号（按最近出现时间倒序）。
     * 同时返回每个批次对应的快照文件是否存在，供前端决定哪些批次可下载。
     */
    @GetMapping("/daily-report/batches")
    public R<List<Map<String, Object>>> dailyReportBatches() {
        List<String> batches = dao.occurrenceBatchNamesRecentFirst();
        List<Map<String, Object>> result = new ArrayList<>(batches.size());
        for (String batch : batches) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchNo", batch);
            row.put("available", dailyReportService.locateReport(batch) != null
                    && dailyReportService.locateReport(batch).toFile().exists());
            result.add(row);
        }
        return R.ok(result);
    }

    /**
     * 下载指定批次的日报 .xlsx（导入时落盘的快照）。
     * <p>快照语义：状态修改不影响日报数据。
     */
    @GetMapping("/daily-report")
    public ResponseEntity<byte[]> downloadDailyReport(@RequestParam("batchNo") String batchNo) {
        java.nio.file.Path path = dailyReportService.locateReport(batchNo);
        if (path == null || !path.toFile().exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            String filename = URLEncoder.encode(batchNo + "日报.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename).body(bytes);
        } catch (java.io.IOException e) {
            log.error("[replay-issue] 日报下载失败 batchNo={}", batchNo, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleInvalidRequestParameter(
            MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST, "请求参数错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpectedException(Exception exception) {
        log.error("[replay-issue] request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "请求失败");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "请求参数错误");
    }

    private static <T> ResponseEntity<R<T>> error(HttpStatus status, String message) {
        R<T> body = R.fail(message);
        body.setCode(status.value());
        return ResponseEntity.status(status).body(body);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private ReplayIssueOperator resolveOperator(HttpServletRequest request) {
        UserPrincipalResolver.Resolved resolved = userResolver.resolve(request);
        return toOperator(resolved);
    }

    private ReplayIssueOperator toOperator(UserPrincipalResolver.Resolved resolved) {
        if (resolved == null || resolved.principal == null || resolved.principal.isBlank()) {
            return null;
        }
        if (resolved.user == null) {
            log.warn("[replay-issue] authenticated principal has no sys_user mapping authMethod={} principal={}",
                    resolved.authMethod, resolved.principal);
            return new ReplayIssueOperator(resolved.principal, resolved.principal);
        }
        String username = firstNonBlank(resolved.user.getUsername(), resolved.principal);
        return new ReplayIssueOperator(username, firstNonBlank(resolved.user.getRealName(), username));
    }

    private static Map<String, Object> lowercaseKeys(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }
}
