package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.service.ReplayIssueImportBusyException;
import com.axonlink.ai.replay.service.ReplayIssueImportService;
import com.axonlink.ai.replay.service.ReplayIssueEditService;
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

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP API for importing and querying the active parallel replay issue snapshot. */
@RestController
@RequestMapping("/api/ai/parallel-replay/issues")
public class ReplayIssueController {

    private static final Logger log = LoggerFactory.getLogger(ReplayIssueController.class);

    private final ReplayIssueImportService importService;
    private final ReplayIssueDao dao;
    private final DaoIndexAnalysisProperties properties;
    private final ReplayIssueEditService editService;
    private final UserPrincipalResolver userResolver;

    public ReplayIssueController(ReplayIssueImportService importService, ReplayIssueDao dao,
                                 DaoIndexAnalysisProperties properties, ReplayIssueEditService editService,
                                 UserPrincipalResolver userResolver) {
        this.importService = importService;
        this.dao = dao;
        this.properties = properties;
        this.editService = editService;
        this.userResolver = userResolver;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<R<ReplayIssueRow>> update(@PathVariable long id,
                                                     @RequestBody ReplayIssueUpdateRequest body,
                                                     HttpServletRequest request) {
        UserPrincipalResolver.Resolved resolved = userResolver.resolve(request);
        if (resolved == null || resolved.user == null) {
            return error(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        ReplayIssueOperator operator = new ReplayIssueOperator(resolved.user.getUsername(), resolved.user.getRealName());
        try {
            return ResponseEntity.ok(R.ok(editService.update(id, body, operator)));
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

    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) Boolean sandbox,
            @RequestParam(required = false) String issueLevel,
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String keyword) {
        ReplayIssueQuery query = new ReplayIssueQuery(limit, offset, groupName,
                sandbox, issueLevel, issueType, keyword);
        List<Map<String, Object>> items = dao.list(query).stream()
                .map(ReplayIssueController::lowercaseKeys)
                .toList();
        return R.ok(Map.of("total", dao.count(query), "items", items));
    }

    @GetMapping("/options")
    public R<ReplayIssueFilterOptions> options() {
        return R.ok(dao.options());
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(dao.stats());
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

    private static Map<String, Object> lowercaseKeys(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }
}
