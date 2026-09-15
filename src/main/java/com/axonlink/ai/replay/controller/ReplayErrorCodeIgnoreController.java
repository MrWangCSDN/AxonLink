package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.dto.ReplayConfigBatchDeleteRequest;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
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
            @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String oldRespCode,
            @RequestParam(required = false) String newRespCode) {
        return R.ok(service.list(limit, offset, internalTransactionCode, serviceCode, oldRespCode, newRespCode));
    }

    @PostMapping
    public R<ReplayErrorCodeIgnoreRow> create(
            @RequestBody(required = false) ReplayErrorCodeIgnoreCreateRequest body,
            HttpServletRequest request) {
        return R.ok(service.create(body, resolveOperator(request)));
    }

    @PatchMapping("/{id}")
    public R<ReplayErrorCodeIgnoreRow> update(@PathVariable("id") long id,
                                              @RequestBody(required = false) ReplayErrorCodeIgnoreUpdateRequest body,
                                              HttpServletRequest request) {
        return R.ok(service.update(id, body, resolveOperator(request)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") long id,
                          @RequestParam(required = false) Integer version,
                          HttpServletRequest request) {
        service.delete(id, version, resolveOperator(request));
        return R.ok(null);
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
