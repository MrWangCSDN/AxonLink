package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
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
import com.axonlink.ai.replay.dto.ReplayIssuePersonRanking;
import com.axonlink.ai.replay.dto.ReplayIssueRoundEntry;
import com.axonlink.ai.replay.dto.ReplayIssueRoundTrackingGroup;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.ai.replay.dto.ReplayIssueMailSendRequest;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.service.ReplayIssueImportBusyException;
import com.axonlink.ai.replay.service.ReplayIssueImportService;
import com.axonlink.ai.replay.service.ReplayIssueFullRefreshService;
import com.axonlink.ai.replay.service.ReplayIssueEditService;
import com.axonlink.ai.replay.service.ReplayIssueMailService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @org.springframework.beans.factory.annotation.Autowired
    private ReplayIssueMailService issueMailService;

    public ReplayIssueController(ReplayIssueImportService importService,
                                 ReplayIssueFullRefreshService fullRefreshService, ReplayIssueDao dao,
                                 DaoIndexAnalysisProperties properties, ReplayIssueEditService editService,
                                 UserPrincipalResolver userResolver) {
        this.importService = importService;
        this.fullRefreshService = fullRefreshService;
        this.dao = dao;
        this.properties = properties;
        this.editService = editService;
        this.userResolver = userResolver;
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

    @GetMapping("/{id}/mail-status")
    public ResponseEntity<R<ReplayIssueMailStatus>> mailStatus(@PathVariable long id) {
        try {
            ReplayIssueRow issue = dao.findCurrentByIdForUpdate(id);
            if (issue == null) return error(HttpStatus.NOT_FOUND, "回放问题不存在");
            return ResponseEntity.ok(R.ok(issueMailService.status(issue)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/{id}/mail-send")
    public ResponseEntity<R<ReplayIssueMailStatus>> sendMail(@PathVariable long id,
                                                              @RequestBody(required = false) ReplayIssueMailSendRequest body,
                                                              HttpServletRequest request) {
        if (resolveOperator(request) == null) return error(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            ReplayIssueRow issue = dao.findCurrentByIdForUpdate(id);
            if (issue == null) return error(HttpStatus.NOT_FOUND, "回放问题不存在");
            return ResponseEntity.ok(R.ok(issueMailService.requestSend(issue, body == null ? null : body.recipientEmails())));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<ReplayIssueImportResult>> importFile(
            @RequestPart("file") MultipartFile file,
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
            return ResponseEntity.ok(R.ok(importService.importFile(file)));
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
            @RequestParam(required = false) List<String> occurrenceBatches) {
        ReplayIssueQuery query = new ReplayIssueQuery(limit, offset, groupName,
                sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner, cooperationPerson,
                serialNo, globalSerialNo, defectRepairDate, coverageRound,
                safe(transactionCodes), safe(issueLevels), safe(developers), safe(bankOwners), safe(issueStatuses), safe(issueTypes), safe(cooperationPersons), safe(occurrenceBatches));
        List<Map<String, Object>> items = dao.list(query).stream()
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
                                                @RequestParam(required = false) String coverageRound,
                                                @RequestParam(required = false) List<String> transactionCodes,
                                                @RequestParam(required = false) List<String> issueLevels,
                                                @RequestParam(required = false) List<String> developers,
                                                @RequestParam(required = false) List<String> bankOwners,
                                                @RequestParam(required = false) List<String> issueStatuses,
                                                @RequestParam(required = false) List<String> issueTypes,
                                                @RequestParam(required = false) List<String> cooperationPersons,
                                                @RequestParam(required = false) List<String> occurrenceBatches) {
        ReplayIssueQuery query = new ReplayIssueQuery(500, 0, groupName, sandbox, issueLevel, issueType, null,
                issueStatus, developer, bankOwner, cooperationPerson, null, null, null, coverageRound,
                safe(transactionCodes), safe(issueLevels), safe(developers), safe(bankOwners), safe(issueStatuses), safe(issueTypes), safe(cooperationPersons), safe(occurrenceBatches));
        return R.ok(dao.headerFilterValues(field, query, keyword));
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    @GetMapping("/options")
    public R<ReplayIssueFilterOptions> options() {
        return R.ok(dao.options());
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(dao.stats());
    }

    @GetMapping("/stats/groups")
    public R<List<ReplayIssueGroupSummary>> groupSummaries() {
        return R.ok(dao.groupIssueSummaries());
    }

    @GetMapping("/stats/person-ranking")
    public R<List<ReplayIssuePersonRanking>> personRankings() {
        return R.ok(dao.personIssueRankings());
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
            @RequestParam(required = false) List<String> occurrenceBatches) {
        ReplayIssueQuery query = new ReplayIssueQuery(200, 0, groupName, sandbox, issueLevel, issueType, keyword,
                issueStatus, developer, bankOwner, cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound,
                safe(transactionCodes), safe(issueLevels), safe(developers), safe(bankOwners), safe(issueStatuses), safe(issueTypes), safe(cooperationPersons), safe(occurrenceBatches));
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("回放问题清单");
            String[] headers = {"领域", "issue_id", "是否沙箱", "交易码", "交易名称", "问题级别", "字段名", "流水号", "全局流水号", "问题描述",
                    "开发负责人", "科技负责人", "问题状态", "问题类型", "初步问题分析", "最终处理方案", "需协同人", "备注", "批次", "导入时间", "登记时间",
                    "缺陷修复日期", "该问题出现在的交易笔数", "issue_key", "历史出现次数", "首次出现日期", "上次出现日期", "出现批次"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            int rowIndex = 1;
            for (Map<String, Object> item : dao.listForExport(query)) {
                Row row = sheet.createRow(rowIndex++);
                Object[] values = {text(item.get("domain")), text(item.get("issue_id")), sandboxText(item.get("is_sandbox")), text(item.get("transaction_code")),
                        text(item.get("transaction_name")), text(item.get("issue_level")), text(item.get("field_name")), text(item.get("serial_no")), text(item.get("global_serial_no")),
                        text(item.get("issue_description")), text(item.get("matched_developer")), text(item.get("matched_bank_owner")), text(item.get("issue_status")), text(item.get("issue_type")), text(item.get("initial_analysis")),
                        text(item.get("final_solution")), personText(item), text(item.get("remark")), text(item.get("batch_no")), text(item.get("import_date")),
                        text(item.get("registered_date")), text(item.get("defect_repair_date")), text(item.get("affected_transaction_count")), text(item.get("issue_key")),
                        text(item.get("historical_occurrence_count")), text(item.get("first_occurrence_date")), text(item.get("last_occurrence_date")), text(item.get("occurrence_rounds"))};
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
