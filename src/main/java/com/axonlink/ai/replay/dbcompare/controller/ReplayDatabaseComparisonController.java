package com.axonlink.ai.replay.dbcompare.controller;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbComparePartitioningRequest;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonPartitionForbiddenException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseTableOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetail;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditGroupPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareDeleteRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareOptions;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbComparePrimaryKeySyncResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareReregisterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSaveRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionSummary;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTablePage;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseDatabaseUnavailableException;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseTableNotFoundException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonImportService;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonConfigScriptService;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonService;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonVersionConflictException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonGenerationException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonScopeException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonVersionService;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseMetadataService;
import com.axonlink.ai.replay.dbcompare.service.ReplayBasePrimaryKeyMissingException;
import com.axonlink.ai.replay.dbcompare.service.ReplayBasePrimaryKeysRequiredException;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import com.axonlink.security.DiiTokenBypassFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/parallel-replay/database-comparison-fields")
public class ReplayDatabaseComparisonController {

    private static final Logger log = LoggerFactory.getLogger(ReplayDatabaseComparisonController.class);
    private static final List<String> DOMAINS =
            List.of("存款组", "贷款组", "公共组", "结算组", "平台组");

    private final ReplayDatabaseComparisonService service;
    private final ReplayBaseMetadataService metadataService;
    private final ReplayDatabaseComparisonImportService importService;
    private final ReplayDatabaseComparisonVersionService versionService;
    private final ReplayDatabaseComparisonConfigScriptService configScriptService;
    private final UserPrincipalResolver userResolver;
    private final ReplayDatabaseComparisonProperties properties;
    private final DaoIndexAnalysisProperties daoIndexProperties;

    public ReplayDatabaseComparisonController(
            ReplayDatabaseComparisonService service,
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonImportService importService,
            ReplayDatabaseComparisonVersionService versionService,
            ReplayDatabaseComparisonConfigScriptService configScriptService,
            UserPrincipalResolver userResolver,
            ReplayDatabaseComparisonProperties properties,
            DaoIndexAnalysisProperties daoIndexProperties) {
        this.service = service;
        this.metadataService = metadataService;
        this.importService = importService;
        this.versionService = versionService;
        this.configScriptService = configScriptService;
        this.userResolver = userResolver;
        this.properties = properties;
        this.daoIndexProperties = daoIndexProperties;
    }

    @PostMapping("/search")
    public R<ReplayDbCompareListPage> search(@RequestBody(required = false) ReplayDbCompareQuery query) {
        return R.ok(service.search(query));
    }

    @GetMapping("/{id}")
    public R<ReplayDbCompareRegistration> detail(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/header-filter-options")
    public R<ReplayDbCompareHeaderFilterResult> headerFilterOptions(
            @RequestBody ReplayDbCompareHeaderFilterRequest request) {
        return R.ok(service.headerFilterOptions(request));
    }

    @GetMapping("/{id}/audits")
    public R<ReplayDbCompareAuditPage> registrationAudits(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return R.ok(service.auditEvents(id, page, size));
    }

    @PostMapping("/audits/search")
    public R<ReplayDbCompareAuditPage> searchAudits(
            @RequestBody(required = false) ReplayDbCompareAuditQuery query) {
        return R.ok(service.searchAudits(query));
    }

    @PostMapping("/audits/grouped-search")
    public R<ReplayDbCompareAuditGroupPage> searchGroupedAudits(
            @RequestBody(required = false) ReplayDbCompareAuditQuery query) {
        return R.ok(service.searchGroupedAudits(query));
    }

    @GetMapping("/audits/{eventId}/details")
    public R<List<ReplayDbCompareAuditDetail>> auditDetails(@PathVariable long eventId) {
        return R.ok(service.auditDetails(eventId));
    }

    @GetMapping("/metadata/tables")
    public R<List<ReplayBaseTableOption>> searchTables(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        List<ReplayBaseTableOption> tables = metadataService.searchTables(keyword, limit).stream()
                .map(table -> {
                    ReplayDatabaseComparisonService.RegistrationState state =
                            service.registrationState(table.tableName());
                    return new ReplayBaseTableOption(
                            table.schemaName(), table.tableName(), table.tableComment(), state.status(),
                            state.registrationId(), state.registrationVersion());
                })
                .toList();
        return R.ok(tables);
    }

    @GetMapping("/metadata/tables/{tableName}/columns")
    public R<List<ReplayBaseColumnOption>> columns(
            @PathVariable String tableName,
            @RequestParam(required = false) String keyword) {
        return R.ok(metadataService.listColumns(tableName, keyword));
    }

    @PostMapping("/metadata/primary-keys/sync")
    public R<ReplayDbComparePrimaryKeySyncResult> synchronizePrimaryKeys() {
        return R.ok(service.synchronizePrimaryKeys());
    }

    @GetMapping("/options")
    public R<ReplayDbCompareOptions> options(HttpServletRequest request) {
        boolean importEnabled = properties.isImportEnabled();
        return R.ok(new ReplayDbCompareOptions(DOMAINS, importEnabled, importEnabled,
                canConfigurePartitions(request)));
    }

    @PostMapping
    public ResponseEntity<R<ReplayDbCompareRegistration>> create(
            @RequestBody ReplayDbCompareSaveRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok(R.ok(service.create(body, requireRegistrationOperator(request))));
    }

    @PutMapping("/{id}/partitioning")
    public R<ReplayDbCompareRegistration> updatePartitioning(
            @PathVariable long id,
            @RequestBody ReplayDbComparePartitioningRequest body,
            HttpServletRequest request) {
        if (!canConfigurePartitions(request)) {
            throw new ReplayDatabaseComparisonPartitionForbiddenException();
        }
        return R.ok(service.updatePartitioning(id, body.version(), body.validatedPartitionNum(),
                requireRegistrationOperator(request)));
    }

    private boolean canConfigurePartitions(HttpServletRequest request) {
        UserPrincipalResolver.Resolved resolved = userResolver.resolve(request);
        return resolved != null && resolved.principal != null && !resolved.principal.isBlank()
                && !DiiTokenBypassFilter.DII_PRINCIPAL.equals(resolved.principal)
                && resolved.user != null && Integer.valueOf(1).equals(resolved.user.getStatus())
                && properties.canConfigurePartitions(resolved.user.getEmpNo());
    }

    @ExceptionHandler(ReplayDatabaseComparisonPartitionForbiddenException.class)
    public ResponseEntity<R<Void>> handlePartitionForbidden() {
        return error(HttpStatus.FORBIDDEN, "无权配置读取分区数");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadableRequest() {
        return error(HttpStatus.BAD_REQUEST, "请求格式不正确");
    }

    @PutMapping("/{id}")
    public ResponseEntity<R<ReplayDbCompareRegistration>> update(
            @PathVariable long id,
            @RequestBody ReplayDbCompareSaveRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok(R.ok(service.update(id, body, requireRegistrationOperator(request))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<R<ReplayDbCompareRegistration>> delete(
            @PathVariable long id,
            @RequestBody ReplayDbCompareDeleteRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok(R.ok(service.delete(id, body, requireRegistrationOperator(request))));
    }

    @PostMapping("/{id}/reregister")
    public ResponseEntity<R<ReplayDbCompareRegistration>> reregister(
            @PathVariable long id,
            @RequestBody ReplayDbCompareReregisterRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok(R.ok(service.reregister(id, body, requireRegistrationOperator(request))));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<ReplayDbCompareImportResult>> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) throws Exception {
        if (!properties.isImportEnabled()) {
            return error(HttpStatus.FORBIDDEN, "初始化导入未开启");
        }
        String expected = daoIndexProperties.getBatchTrigger().getToken();
        if (expected != null && !expected.isBlank() && !expected.equals(token)) {
            return error(HttpStatus.UNAUTHORIZED, "口令错误");
        }
        // A verified operation token permits import without a human login, as with version generation.
        // An unconfigured token must not authorize an anonymous import.
        ReplayIssueOperator operator = expected != null && !expected.isBlank()
                ? optionalOperator(request) : requireOperator(request);
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "文件为空");
        }
        ReplayDbCompareImportResult result = importService.importFile(file.getInputStream(), operator);
        if (!result.success()) {
            return ResponseEntity.unprocessableEntity().body(R.fail(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "初始化导入校验失败，未写入任何数据", result));
        }
        return ResponseEntity.ok(R.ok(result));
    }

    @PostMapping("/versions/generate")
    public ResponseEntity<R<ReplayDbCompareVersionSummary>> generateVersion(
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ReplayIssueOperator operator = optionalOperator(request);
        String expected = daoIndexProperties.getBatchTrigger().getToken();
        return ResponseEntity.ok(R.ok(versionService.generate(token, expected, operator)));
    }

    @GetMapping("/versions/latest")
    public R<ReplayDbCompareVersionSummary> latestVersion() {
        return R.ok(versionService.latest());
    }

    @GetMapping("/versions")
    public R<ReplayDbCompareVersionPage> versions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(versionService.versions(page, size));
    }

    @PostMapping("/versions/{versionNo}/search")
    public R<ReplayDbCompareVersionTablePage> searchVersion(
            @PathVariable String versionNo,
            @RequestBody(required = false) ReplayDbCompareVersionQuery query) {
        return R.ok(versionService.searchVersion(versionNo, query));
    }

    @PostMapping("/versions/{versionNo}/header-filter-options")
    public R<ReplayDbCompareHeaderFilterResult> versionHeaderFilterOptions(
            @PathVariable String versionNo,
            @RequestBody ReplayDbCompareHeaderFilterRequest request) {
        return R.ok(versionService.versionHeaderFilterOptions(versionNo, request));
    }

    @GetMapping("/versions/{versionNo}/config-script")
    public R<ReplayDbCompareConfigScriptStatus> configScriptStatus(
            @PathVariable String versionNo) {
        return R.ok(configScriptService.status(versionNo));
    }

    @PostMapping("/versions/{versionNo}/config-script")
    public ResponseEntity<byte[]> generateConfigScript(
            @PathVariable String versionNo,
            HttpServletRequest request) {
        return scriptFile(configScriptService.generate(versionNo, requireOperator(request)));
    }

    @GetMapping("/versions/{versionNo}/config-script/download")
    public ResponseEntity<byte[]> downloadConfigScript(@PathVariable String versionNo) {
        return scriptFile(configScriptService.download(versionNo));
    }

    private ResponseEntity<byte[]> scriptFile(
            ReplayDatabaseComparisonConfigScriptService.ScriptFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/sql;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + UriUtils.encode(file.fileName(), StandardCharsets.UTF_8))
                .header("X-Content-SHA256", file.sha256())
                .body(file.content());
    }

    private ReplayIssueOperator requireRegistrationOperator(HttpServletRequest request) {
        ReplayIssueOperator operator = requireOperator(request);
        if (DiiTokenBypassFilter.DII_PRINCIPAL.equals(operator.username())) {
            throw new HumanLoginRequiredException();
        }
        return operator;
    }

    private ReplayIssueOperator requireOperator(HttpServletRequest request) {
        UserPrincipalResolver.Resolved resolved = userResolver.resolve(request);
        if (resolved == null || resolved.principal == null || resolved.principal.isBlank()) {
            throw new UnauthenticatedException();
        }
        if (resolved.user == null) {
            log.warn("[replay-db-compare] authenticated principal has no sys_user mapping authMethod={} principal={}",
                    resolved.authMethod, resolved.principal);
            return new ReplayIssueOperator(resolved.principal, resolved.principal);
        }
        String username = resolved.user.getUsername();
        if (username == null || username.isBlank()) {
            username = resolved.principal;
        }
        String name = resolved.user.getRealName();
        return new ReplayIssueOperator(username, name == null || name.isBlank() ? username : name);
    }

    private ReplayIssueOperator optionalOperator(HttpServletRequest request) {
        UserPrincipalResolver.Resolved resolved = userResolver.resolve(request);
        if (resolved == null || resolved.user == null) {
            return ReplayIssueOperator.system();
        }
        String username = resolved.user.getUsername();
        if (username == null || username.isBlank()) {
            username = resolved.principal;
        }
        if (username == null || username.isBlank()) {
            return ReplayIssueOperator.system();
        }
        String name = resolved.user.getRealName();
        return new ReplayIssueOperator(username, name == null || name.isBlank() ? username : name);
    }

    @ExceptionHandler(HumanLoginRequiredException.class)
    public ResponseEntity<R<Map<String, String>>> handleHumanLoginRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(
                HttpStatus.FORBIDDEN.value(), "当前为操作口令身份，请退出后使用人员账号登录再保存",
                Map.of("errorCode", "HUMAN_LOGIN_REQUIRED")));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<R<Void>> handleUnauthenticated() {
        return error(HttpStatus.UNAUTHORIZED, "用户未登录");
    }

    @ExceptionHandler(ReplayDatabaseComparisonVersionConflictException.class)
    public ResponseEntity<R<Void>> handleVersionConflict(
            ReplayDatabaseComparisonVersionConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(ReplayDatabaseComparisonGenerationException.class)
    public ResponseEntity<R<Map<String, Object>>> handleGeneration(
            ReplayDatabaseComparisonGenerationException exception) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", exception.errorCode());
        if (exception.data() instanceof Map<?, ?> payload) {
            payload.forEach((key, value) -> data.put(String.valueOf(key), value));
        }
        return ResponseEntity.status(exception.status()).body(R.fail(
                exception.status().value(), exception.getMessage(), data));
    }

    @ExceptionHandler(ReplayBaseDatabaseUnavailableException.class)
    public ResponseEntity<R<Map<String, Object>>> handleBaseUnavailable(
            ReplayBaseDatabaseUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(R.fail(
                HttpStatus.SERVICE_UNAVAILABLE.value(), exception.getMessage(),
                Map.of("errorCode", "BASE_DATABASE_UNAVAILABLE")));
    }

    @ExceptionHandler(ReplayBaseTableNotFoundException.class)
    public ResponseEntity<R<Map<String, Object>>> handleBaseTableNotFound(
            ReplayBaseTableNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(
                HttpStatus.NOT_FOUND.value(), exception.getMessage(),
                Map.of("errorCode", "BASE_TABLE_NOT_FOUND", "tableName", exception.tableName())));
    }

    @ExceptionHandler(ReplayBasePrimaryKeyMissingException.class)
    public ResponseEntity<R<Map<String, Object>>> handlePrimaryKeyMissing(
            ReplayBasePrimaryKeyMissingException exception) {
        return ResponseEntity.unprocessableEntity().body(R.fail(
                HttpStatus.UNPROCESSABLE_ENTITY.value(), exception.getMessage(),
                Map.of("errorCode", "BASE_PRIMARY_KEY_MISSING", "tableName", exception.tableName())));
    }

    @ExceptionHandler(ReplayBasePrimaryKeysRequiredException.class)
    public ResponseEntity<R<Map<String, Object>>> handlePrimaryKeysRequired(
            ReplayBasePrimaryKeysRequiredException exception) {
        return ResponseEntity.unprocessableEntity().body(R.fail(
                HttpStatus.UNPROCESSABLE_ENTITY.value(), exception.getMessage(),
                Map.of(
                        "errorCode", "BASE_PRIMARY_KEYS_REQUIRED",
                        "tableName", exception.tableName(),
                        "missingPrimaryKeyNames", exception.missingPrimaryKeyNames())));
    }

    @ExceptionHandler(ReplayDatabaseComparisonScopeException.class)
    public ResponseEntity<R<Map<String, Object>>> handleScopeInvalid(
            ReplayDatabaseComparisonScopeException exception) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", "COMPARISON_SCOPE_INVALID");
        data.put("errors", exception.errors());
        return ResponseEntity.unprocessableEntity().body(R.fail(
                HttpStatus.UNPROCESSABLE_ENTITY.value(), exception.getMessage(), data));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> handleInvalid(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpected(Exception exception) {
        log.error("[replay-db-compare] request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "请求失败");
    }

    private static <T> ResponseEntity<R<T>> error(HttpStatus status, String message) {
        R<T> body = R.fail(message);
        body.setCode(status.value());
        return ResponseEntity.status(status).body(body);
    }

    private static final class HumanLoginRequiredException extends RuntimeException {
    }

    private static final class UnauthenticatedException extends RuntimeException {
    }
}
