package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewResult;
import com.axonlink.ai.replay.dto.ReplayConfigIdBatchRequest;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreUpdateRequest;
import com.axonlink.ai.replay.service.ReplayConfigValidation;
import com.axonlink.ai.replay.service.ReplayMessageSequenceIgnoreExportService;
import com.axonlink.ai.replay.service.ReplayMessageSequenceIgnoreService;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/parallel-replay/config/message-sequence-ignores")
public class ReplayMessageSequenceIgnoreController extends AbstractReplayConfigController {

    private final ReplayMessageSequenceIgnoreService service;
    private final ReplayMessageSequenceIgnoreExportService exportService;

    public ReplayMessageSequenceIgnoreController(ReplayMessageSequenceIgnoreService service,
                                                  ReplayMessageSequenceIgnoreExportService exportService,
                                                  UserPrincipalResolver userResolver) {
        super(userResolver);
        this.service = service;
        this.exportService = exportService;
    }

    @GetMapping
    public R<ReplayConfigPage<ReplayMessageSequenceIgnoreRow>> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String category, @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String messageSequence, @RequestParam(required = false) Integer reviewStatus,
            @RequestParam(required = false) Boolean reviewableByMe,
            @RequestParam(required = false) Boolean developedByMe,
            @RequestParam(required = false) Boolean reasonEmpty,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) Boolean domainEmpty,
            @RequestParam(required = false) boolean internalTransactionCodeEmpty,
            HttpServletRequest request) {
        return R.ok(service.list(limit, offset, category, serviceCode, messageSequence, reviewStatus,
                reviewableByMe, developedByMe, reasonEmpty, domain, domainEmpty, internalTransactionCodeEmpty, resolveOperator(request)));
    }

    @PostMapping
    public R<List<ReplayMessageSequenceIgnoreRow>> create(
            @RequestBody(required = false) ReplayMessageSequenceIgnoreCreateRequest body,
            HttpServletRequest request) {
        return R.ok(service.create(body, resolveOperator(request)));
    }

    @PatchMapping("/{id}")
    public R<ReplayMessageSequenceIgnoreRow> update(@PathVariable long id,
                                                    @RequestBody(required = false) ReplayMessageSequenceIgnoreUpdateRequest body,
                                                    HttpServletRequest request) {
        return R.ok(service.update(id, body, resolveOperator(request)));
    }

    @PostMapping("/{id}/review")
    public R<ReplayMessageSequenceIgnoreRow> review(@PathVariable long id, HttpServletRequest request) {
        return R.ok(service.review(id, resolveOperator(request)));
    }

    @PostMapping("/batch-review")
    public R<ReplayConfigBatchReviewResult> batchReview(@RequestBody(required = false) ReplayConfigIdBatchRequest body,
                                                        HttpServletRequest request) {
        return R.ok(service.batchReview(ReplayConfigValidation.requireBatchIds(body), resolveOperator(request)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id, HttpServletRequest request) {
        service.delete(id, resolveOperator(request));
        return R.ok(null);
    }

    @PostMapping("/batch-delete")
    public R<Map<String, Object>> batchDelete(@RequestBody(required = false) ReplayConfigIdBatchRequest body,
                                              HttpServletRequest request) {
        int deleted = service.batchDelete(ReplayConfigValidation.requireBatchIds(body), resolveOperator(request));
        return R.ok(Map.of("deletedCount", deleted));
    }

    @GetMapping("/{id}/operations")
    public R<ReplayConfigPage<ReplayConfigOperationView>> operations(@PathVariable long id,
                                                                     @RequestParam(required = false) Integer limit,
                                                                     @RequestParam(required = false) Integer offset) {
        return R.ok(service.operations(id, limit, offset));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        byte[] body = exportService.exportReviewed();
        String fileName = "流水号忽略-" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".txt";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
