package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.dto.ReplayConfigBatchDeleteRequest;
import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewRequest;
import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewResult;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigReviewRequest;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreUpdateRequest;
import com.axonlink.ai.replay.service.ReplayConfigValidation;
import com.axonlink.ai.replay.service.ReplayUnconditionalIgnoreService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/ai/parallel-replay/config/unconditional-ignores")
public class ReplayUnconditionalIgnoreController extends AbstractReplayConfigController {

    private final ReplayUnconditionalIgnoreService service;

    public ReplayUnconditionalIgnoreController(ReplayUnconditionalIgnoreService service,
                                               UserPrincipalResolver userResolver) {
        super(userResolver);
        this.service = service;
    }

    @GetMapping
    public R<ReplayConfigPage<ReplayUnconditionalIgnoreRow>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String internalTransactionCode,
            @RequestParam(required = false) String tranCode,
            @RequestParam(required = false) String fieldName,
            @RequestParam(required = false) Integer reviewStatus,
            @RequestParam(required = false) Boolean reviewableByMe,
            HttpServletRequest request) {
        return R.ok(service.list(limit, offset, internalTransactionCode, tranCode, fieldName,
                reviewStatus, reviewableByMe, resolveOperator(request)));
    }

    @PostMapping
    public R<ReplayUnconditionalIgnoreRow> create(
            @RequestBody(required = false) ReplayUnconditionalIgnoreCreateRequest body,
            HttpServletRequest request) {
        return R.ok(service.create(body, resolveOperator(request)));
    }

    @PatchMapping("/{id}")
    public R<ReplayUnconditionalIgnoreRow> update(@PathVariable("id") long id,
                                                  @RequestBody(required = false) ReplayUnconditionalIgnoreUpdateRequest body,
                                                  HttpServletRequest request) {
        return R.ok(service.update(id, body, resolveOperator(request)));
    }

    @PostMapping("/{id}/review")
    public R<ReplayUnconditionalIgnoreRow> review(@PathVariable("id") long id,
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
