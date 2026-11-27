package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewResult;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigPersonInfo;
import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayOneSideErrorCodeIgnoreDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ReplayOneSideErrorCodeIgnoreService {

    private final ReplayOneSideErrorCodeIgnoreDao dao;
    private final ReplayConfigPersonResolver personResolver;

    public ReplayOneSideErrorCodeIgnoreService(ReplayOneSideErrorCodeIgnoreDao dao,
                                               ReplayConfigPersonResolver personResolver) {
        this.dao = dao;
        this.personResolver = personResolver;
    }

    public ReplayConfigPage<ReplayOneSideErrorCodeIgnoreRow> list(
            Integer limit, Integer offset, String category, String serviceCode, String ownership, String errorCode,
            Integer reviewStatus, Boolean reviewableByMe, Boolean developedByMe, Boolean reasonEmpty,
            String domain, Boolean domainEmpty,
            ReplayConfigOperator operator) {
        return list(limit, offset, category, serviceCode, ownership, errorCode, reviewStatus, reviewableByMe,
                developedByMe, reasonEmpty, domain, domainEmpty, false, operator);
    }

    public ReplayConfigPage<ReplayOneSideErrorCodeIgnoreRow> list(
            Integer limit, Integer offset, String category, String serviceCode, String ownership, String errorCode,
            Integer reviewStatus, Boolean reviewableByMe, Boolean developedByMe, Boolean reasonEmpty,
            String domain, Boolean domainEmpty,
            boolean internalTransactionCodeEmpty,
            ReplayConfigOperator operator) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        String resolvedCategory = category == null || category.isBlank() ? null : ReplayConfigValidation.requireMessageSequenceCategory(category);
        String resolvedOwnership = ownership == null || ownership.isBlank() ? null : ReplayConfigValidation.requireOneSideOwnership(ownership);
        Integer resolvedReviewStatus = ReplayConfigValidation.optionalReviewStatus(reviewStatus);
        boolean onlyReviewableByMe = Boolean.TRUE.equals(reviewableByMe);
        if (onlyReviewableByMe && Integer.valueOf(1).equals(resolvedReviewStatus)) return new ReplayConfigPage<>(0, List.of());
        Integer effectiveReviewStatus = onlyReviewableByMe ? Integer.valueOf(0) : resolvedReviewStatus;
        Set<String> serviceCodes = ReplayConfigPersonResolver.intersect(
                personResolver.resolveConfigServiceCodes("one-side-error-code-ignores", domain, null,
                        Boolean.TRUE.equals(domainEmpty), false, internalTransactionCodeEmpty),
                onlyReviewableByMe ? personResolver.findReviewableServiceCodes(operator == null ? null : operator.empNo()) : null,
                Boolean.TRUE.equals(developedByMe) ? personResolver.findDevelopedServiceCodes(operator == null ? null : operator.username()) : null);
        long total = dao.count(resolvedCategory, serviceCode, resolvedOwnership, errorCode, serviceCodes, effectiveReviewStatus, reasonEmpty);
        if (total == 0) return new ReplayConfigPage<>(0, List.of());
        return new ReplayConfigPage<>(total, enrich(dao.list(resolvedCategory, serviceCode, resolvedOwnership, errorCode,
                serviceCodes, effectiveReviewStatus, reasonEmpty, resolvedLimit, resolvedOffset), operator));
    }

    public ReplayConfigPage<ReplayOneSideErrorCodeIgnoreRow> list(
            Integer limit, Integer offset, String category, String serviceCode, String ownership, String errorCode,
            Integer reviewStatus, Boolean reviewableByMe, Boolean developedByMe, Boolean reasonEmpty,
            ReplayConfigOperator operator) {
        return list(limit, offset, category, serviceCode, ownership, errorCode, reviewStatus,
                reviewableByMe, developedByMe, reasonEmpty, null, null, operator);
    }

    public List<ReplayOneSideErrorCodeIgnoreRow> create(ReplayOneSideErrorCodeIgnoreCreateRequest request,
                                                         ReplayConfigOperator operator) {
        String serviceCode = ReplayConfigValidation.requireServiceCode(request == null ? null : request.serviceCode());
        String category = ReplayConfigValidation.requireMessageSequenceCategory(request == null ? null : request.category());
        String ownership = ReplayConfigValidation.requireOneSideOwnership(request == null ? null : request.ownership());
        List<String> codes = ReplayConfigValidation.requireOneSideErrorCodes(request == null ? null : request.errorCodes());
        String reason = ReplayConfigValidation.requireIgnoreReason(request == null ? null : request.ignoreReason());
        try {
            return enrich(dao.createAll(serviceCode, category, ownership, codes, reason, operator), operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("该领域和归属下忽略错误码已存在，整批未写入");
        }
    }

    public ReplayOneSideErrorCodeIgnoreRow update(long id, ReplayOneSideErrorCodeIgnoreUpdateRequest request,
                                                   ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        String serviceCode = ReplayConfigValidation.requireServiceCode(request == null ? null : request.serviceCode());
        String category = ReplayConfigValidation.requireMessageSequenceCategory(request == null ? null : request.category());
        String ownership = ReplayConfigValidation.requireOneSideOwnership(request == null ? null : request.ownership());
        List<String> codes = ReplayConfigValidation.requireOneSideErrorCodes(request == null ? null : request.errorCode());
        if (codes.size() != 1) throw new IllegalArgumentException("修改时只能填写一个忽略错误码");
        String code = codes.get(0);
        String reason = ReplayConfigValidation.requireIgnoreReason(request == null ? null : request.ignoreReason());
        ReplayOneSideErrorCodeIgnoreRow current = requireRow(id);
        if (Objects.equals(current.serviceCode(), serviceCode) && Objects.equals(current.category(), category)
                && Objects.equals(current.ownership(), ownership) && Objects.equals(current.errorCode(), code)
                && Objects.equals(current.ignoreReason(), reason)) return enrich(current, operator);
        try {
            return enrich(dao.update(current, serviceCode, category, ownership, code, reason, operator), operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("该领域和归属下忽略错误码已存在");
        }
    }

    public ReplayOneSideErrorCodeIgnoreRow review(long id, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        ReplayOneSideErrorCodeIgnoreRow current = requireRow(id);
        if (current.reviewStatus() == 1) return enrich(current, operator);
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(current.serviceCode())).get(current.serviceCode());
        if (!ReplayConfigPersonResolver.matchesBankOwner(info, operator)) throw new ReplayConfigReviewForbiddenException(ReplayConfigPersonResolver.reviewForbiddenMessage(info));
        return enrich(dao.review(current, operator), operator);
    }

    public ReplayConfigBatchReviewResult batchReview(List<Long> ids, ReplayConfigOperator operator) {
        int approved = dao.batchReview(ids, operator, row -> {
            ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(row.serviceCode())).get(row.serviceCode());
            return ReplayConfigPersonResolver.matchesBankOwner(info, operator);
        });
        return new ReplayConfigBatchReviewResult(approved, ids.size() - approved);
    }

    public void delete(long id, ReplayConfigOperator operator) { ReplayConfigValidation.requirePositiveId(id); dao.batchDelete(List.of(id), operator); }
    public int batchDelete(List<Long> ids, ReplayConfigOperator operator) { return dao.batchDelete(ids, operator); }

    public ReplayConfigPage<ReplayConfigOperationView> operations(long id, Integer limit, Integer offset) {
        ReplayConfigValidation.requirePositiveId(id); requireRow(id);
        return dao.operations(id, ReplayConfigValidation.pageLimit(limit), ReplayConfigValidation.pageOffset(offset));
    }

    private ReplayOneSideErrorCodeIgnoreRow requireRow(long id) {
        ReplayOneSideErrorCodeIgnoreRow row = dao.findById(id);
        if (row == null) throw new ReplayConfigNotFoundException("记录不存在");
        return row;
    }

    private List<ReplayOneSideErrorCodeIgnoreRow> enrich(List<ReplayOneSideErrorCodeIgnoreRow> rows, ReplayConfigOperator operator) {
        if (rows.isEmpty()) return rows;
        Map<String, ReplayConfigPersonInfo> persons = personResolver.resolveByServiceCodes(rows.stream().map(ReplayOneSideErrorCodeIgnoreRow::serviceCode).toList());
        return rows.stream().map(row -> enrich(row, persons.get(row.serviceCode()), operator)).toList();
    }
    private ReplayOneSideErrorCodeIgnoreRow enrich(ReplayOneSideErrorCodeIgnoreRow row, ReplayConfigOperator operator) {
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(row.serviceCode())).get(row.serviceCode());
        return enrich(row, info, operator);
    }
    private ReplayOneSideErrorCodeIgnoreRow enrich(ReplayOneSideErrorCodeIgnoreRow row, ReplayConfigPersonInfo info, ReplayConfigOperator operator) {
        String disabledReason = ReplayConfigPersonResolver.reviewDisabledReason(info, operator, row.reviewStatus());
        return new ReplayOneSideErrorCodeIgnoreRow(row.id(), row.serviceCode(), row.category(), row.ownership(), row.errorCode(), row.ignoreReason(),
                row.reviewStatus(), row.createdAt(), row.updatedAt(), info == null ? null : info.oldTransactionCode(),
                info == null ? null : info.developer(), info == null ? null : info.bankOwner(), disabledReason == null, disabledReason,
                info == null ? null : info.domain());
    }
}
