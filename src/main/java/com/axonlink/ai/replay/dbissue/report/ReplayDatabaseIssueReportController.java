package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import com.axonlink.security.DiiTokenBypassFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Separate summary export endpoints; the existing issue detail export keeps its original behavior. */
@RestController
@RequestMapping("/api/ai/parallel-replay/database-issues/report")
public class ReplayDatabaseIssueReportController {
    private static final Logger log = LoggerFactory.getLogger(ReplayDatabaseIssueReportController.class);
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ReplayDatabaseIssueReportService service;
    private final ReplayDatabaseIssueReportWorkbookWriter writer;
    private final UserPrincipalResolver resolver;

    public ReplayDatabaseIssueReportController(ReplayDatabaseIssueReportService service,
                                               ReplayDatabaseIssueReportWorkbookWriter writer,
                                               UserPrincipalResolver resolver) {
        this.service = service; this.writer = writer; this.resolver = resolver;
    }

    public record ExportRequest(String batchNo) {}

    @GetMapping("/options")
    public R<Map<String, Object>> options(HttpServletRequest request) {
        requireLogin(request);
        return R.ok(Map.of("batches", service.batchNames()));
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody(required = false) ExportRequest body, HttpServletRequest request) {
        requireLogin(request);
        var report = service.build(body == null ? null : body.batchNo());
        byte[] bytes = writer.write(report);
        String name = "回放数据库问题汇总报表" + (report.batchNo() == null ? "" : "-" + report.batchNo()) + ".xlsx";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(XLSX).contentLength(bytes.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded).body(bytes);
    }

    private void requireLogin(HttpServletRequest request) {
        var user = resolver.resolve(request);
        if (user == null || user.principal == null || user.principal.isBlank()
                || DiiTokenBypassFilter.DII_PRINCIPAL.equals(user.principal))
            throw new AuthenticationCredentialsNotFoundException("请先登录");
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<R<Object>> unauthenticated(Exception exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(HttpStatus.UNAUTHORIZED.value(), exception.getMessage(), null));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<R<Object>> invalid(Exception exception) {
        String message = exception instanceof IllegalArgumentException ? exception.getMessage() : "请求格式不正确";
        return ResponseEntity.badRequest().body(R.fail(HttpStatus.BAD_REQUEST.value(), message, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Object>> failed(Exception exception) {
        log.error("Database issue summary export failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "导出报表失败，请稍后重试", null));
    }
}
