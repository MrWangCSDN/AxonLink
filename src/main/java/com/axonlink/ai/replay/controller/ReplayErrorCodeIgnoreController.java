package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.dto.ReplayConfigBatchCreateRequest;
import com.axonlink.ai.replay.dto.ReplayConfigBatchDeleteRequest;
import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewRequest;
import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewResult;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigReviewRequest;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreUpdateRequest;
import com.axonlink.ai.replay.service.ReplayConfigValidation;
import com.axonlink.ai.replay.service.ReplayErrorCodeIgnoreService;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/parallel-replay/config/error-code-ignores")
public class ReplayErrorCodeIgnoreController extends AbstractReplayConfigController {

    private final ReplayErrorCodeIgnoreService service;

    public ReplayErrorCodeIgnoreController(ReplayErrorCodeIgnoreService service,
                                           UserPrincipalResolver userResolver) {
        super(userResolver);
        this.service = service;
    }

    @GetMapping
    public R<ReplayConfigPage<ReplayErrorCodeIgnoreRow>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String internalTransactionCode,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String oldRespCode,
            @RequestParam(required = false) String newRespCode,
            @RequestParam(required = false) Integer reviewStatus,
            @RequestParam(required = false) Boolean reviewableByMe,
            HttpServletRequest request) {
        return R.ok(service.list(limit, offset, internalTransactionCode, domain, serviceCode, oldRespCode,
                newRespCode, reviewStatus, reviewableByMe, resolveOperator(request)));
    }

    @PostMapping
    public R<ReplayErrorCodeIgnoreRow> create(
            @RequestBody(required = false) ReplayErrorCodeIgnoreCreateRequest body,
            HttpServletRequest request) {
        return R.ok(service.create(body, resolveOperator(request)));
    }

    @PostMapping("/batch-create")
    public R<List<ReplayErrorCodeIgnoreRow>> batchCreate(
            @RequestBody(required = false)
            ReplayConfigBatchCreateRequest<ReplayErrorCodeIgnoreCreateRequest> body,
            HttpServletRequest request) {
        return R.ok(service.createBatch(body == null ? null : body.items(), resolveOperator(request)));
    }

    @PatchMapping("/{id}")
    public R<ReplayErrorCodeIgnoreRow> update(@PathVariable("id") long id,
                                              @RequestBody(required = false) ReplayErrorCodeIgnoreUpdateRequest body,
                                              HttpServletRequest request) {
        return R.ok(service.update(id, body, resolveOperator(request)));
    }

    @PostMapping("/{id}/review")
    public R<ReplayErrorCodeIgnoreRow> review(@PathVariable("id") long id,
                                              @RequestBody(required = false) ReplayConfigReviewRequest body,
                                              HttpServletRequest request) {
        return R.ok(service.review(id, body == null ? null : body.version(), resolveOperator(request)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") long id,
                          @RequestParam(required = false) Integer version,
                          HttpServletRequest request) {
        service.delete(id, version, resolveOperator(request));
        return R.ok(null);
    }

    @PostMapping("/batch-review")
    public R<ReplayConfigBatchReviewResult> batchReview(
            @RequestBody(required = false) ReplayConfigBatchReviewRequest body,
            HttpServletRequest request) {
        return R.ok(service.batchReview(
                ReplayConfigValidation.requireBatchItems(body == null ? null : body.items()),
                resolveOperator(request)));
    }

    @PostMapping("/batch-delete")
    public R<Map<String, Object>> batchDelete(
            @RequestBody(required = false) ReplayConfigBatchDeleteRequest body,
            HttpServletRequest request) {
        int deleted = service.batchDelete(ReplayConfigValidation.requireBatchItems(body),
                resolveOperator(request));
        return R.ok(Map.of("deletedCount", deleted));
    }

    @GetMapping("/{id}/operations")
    public R<ReplayConfigPage<ReplayConfigOperationView>> operations(
            @PathVariable("id") long id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return R.ok(service.operations(id, limit, offset));
    }
}
