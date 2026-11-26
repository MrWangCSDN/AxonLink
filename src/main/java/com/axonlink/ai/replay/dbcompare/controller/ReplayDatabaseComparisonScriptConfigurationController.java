package com.axonlink.ai.replay.dbcompare.controller;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.*;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseDatabaseUnavailableException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonGenerationException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonScriptConfigurationService;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.common.R;
import com.axonlink.security.DiiTokenBypassFilter;
import com.axonlink.security.UserPrincipalResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/parallel-replay/database-comparison-fields/script-configs")
public class ReplayDatabaseComparisonScriptConfigurationController {
    private static final Logger log = LoggerFactory.getLogger(ReplayDatabaseComparisonScriptConfigurationController.class);
    private static final long MAX_EXCEL_BYTES = 10 * 1024 * 1024L;
    private final ReplayDatabaseComparisonScriptConfigurationService service;
    private final UserPrincipalResolver resolver;
    private final ObjectMapper json;

    public ReplayDatabaseComparisonScriptConfigurationController(
            ReplayDatabaseComparisonScriptConfigurationService service, UserPrincipalResolver resolver,
            ObjectMapper json) {
        this.service = service;
        this.resolver = resolver;
        this.json = json;
    }

    @GetMapping public R<Page> list() { return R.ok(service.list()); }

    @GetMapping("/{id}") public R<Detail> detail(@PathVariable long id) { return R.ok(service.detail(id)); }

    @PostMapping public R<Detail> create(@RequestBody(required = false) SaveRequest body, HttpServletRequest request) {
        return R.ok(service.create(body, operator(request)));
    }

    @PutMapping("/{id}") public R<Detail> update(@PathVariable long id,
            @RequestBody(required = false) SaveRequest body, HttpServletRequest request) {
        return R.ok(service.update(id, body, operator(request)));
    }

    @PostMapping("/preview-metadata")
    public R<TableImportResult> previewMetadata(@RequestBody(required = false) PreviewRequest body,
                                                HttpServletRequest request) {
        operator(request);
        return R.ok(service.previewMetadata(body));
    }

    @PostMapping(value = "/import-tables", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<TableImportResult> importTables(@RequestPart("file") MultipartFile file,
            @RequestPart(value = "connection", required = false) byte[] connection,
            @RequestParam(required = false) Long configurationId, @RequestParam(required = false) Long revision,
            HttpServletRequest request) throws IOException {
        operator(request);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空的表清单Excel");
        if (file.getSize() > MAX_EXCEL_BYTES) throw new UploadTooLarge();
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                || name.toLowerCase(Locale.ROOT).endsWith(".xls"))) {
            throw new IllegalArgumentException("表清单仅支持.xls或.xlsx文件");
        }
        ConnectionRequest source = connection == null ? null : json.readValue(connection, ConnectionRequest.class);
        try (var input = file.getInputStream()) {
            return R.ok(service.importTables(input, configurationId, revision, source));
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id, @RequestParam Long revision) {
        var file = service.download(id, revision);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/sql;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + UriUtils.encode(file.fileName(), StandardCharsets.UTF_8))
                .header("X-Content-SHA256", file.sha256()).body(file.content());
    }

    /** Same human-login boundary as registration maintenance; request bodies never supply actors. */
    private ReplayIssueOperator operator(HttpServletRequest request) {
        var identity = resolver.resolve(request);
        if (identity == null || identity.principal == null || identity.principal.isBlank()) throw new LoginRequired();
        String username = identity.user == null ? identity.principal : identity.user.getUsername();
        if (username == null || username.isBlank()) username = identity.principal;
        if (DiiTokenBypassFilter.DII_PRINCIPAL.equals(username)
                || DiiTokenBypassFilter.DII_PRINCIPAL.equals(identity.principal)) throw new HumanRequired();
        String name = identity.user == null ? username : identity.user.getRealName();
        return new ReplayIssueOperator(username, name == null || name.isBlank() ? username : name);
    }

    @ExceptionHandler(ReplayDatabaseComparisonGenerationException.class)
    public ResponseEntity<R<Map<String, Object>>> generation(ReplayDatabaseComparisonGenerationException error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", error.errorCode());
        if (error.data() instanceof Map<?, ?> payload) payload.forEach((k, v) -> data.put(String.valueOf(k), v));
        return ResponseEntity.status(error.status()).body(R.fail(error.status().value(), error.getMessage(), data));
    }

    @ExceptionHandler(LoginRequired.class) public ResponseEntity<R<Void>> loginRequired() {
        return error(HttpStatus.UNAUTHORIZED, "请先登录人员账号");
    }

    @ExceptionHandler(HumanRequired.class) public ResponseEntity<R<Void>> humanRequired() {
        return error(HttpStatus.FORBIDDEN, "操作口令不能维护命名配置，请使用人员账号登录");
    }

    @ExceptionHandler({UploadTooLarge.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<R<Void>> uploadTooLarge() { return error(HttpStatus.PAYLOAD_TOO_LARGE, "Excel文件不能超过10MiB"); }

    @ExceptionHandler(ReplayBaseDatabaseUnavailableException.class)
    public ResponseEntity<R<Map<String, String>>> baseUnavailable(ReplayBaseDatabaseUnavailableException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(R.fail(503, failure.getMessage(),
                Map.of("errorCode", "BASE_DATABASE_UNAVAILABLE")));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class, MissingServletRequestParameterException.class, IOException.class})
    public ResponseEntity<R<Void>> malformed(Exception failure) { return error(HttpStatus.BAD_REQUEST, "请求或Excel数据格式不正确"); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> invalid(IllegalArgumentException failure) { return error(HttpStatus.BAD_REQUEST, failure.getMessage()); }

    @ExceptionHandler(Exception.class) public ResponseEntity<R<Void>> unexpected(Exception failure) {
        log.error("[replay-script-config] request failed ({})", failure.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "命名脚本配置请求失败");
    }

    private ResponseEntity<R<Void>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(R.fail(status.value(), message, null));
    }

    private static final class LoginRequired extends RuntimeException {}
    private static final class HumanRequired extends RuntimeException {}
    private static final class UploadTooLarge extends RuntimeException {}
}
