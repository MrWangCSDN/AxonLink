package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigPersonInfo;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayUnconditionalIgnoreDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ReplayUnconditionalIgnoreService {

    private final ReplayUnconditionalIgnoreDao dao;
    private final ReplayConfigServiceCodeResolver resolver;
    private final ReplayConfigPersonResolver personResolver;

    public ReplayUnconditionalIgnoreService(ReplayUnconditionalIgnoreDao dao,
                                            ReplayConfigServiceCodeResolver resolver,
                                            ReplayConfigPersonResolver personResolver) {
        this.dao = dao;
        this.resolver = resolver;
        this.personResolver = personResolver;
    }

    public ReplayConfigPage<ReplayUnconditionalIgnoreRow> list(Integer limit, Integer offset,
                                                               String internalTransactionCode, String tranCode,
                                                               String fieldName) {
        return list(limit, offset, internalTransactionCode, tranCode, fieldName, null, null);
    }

    public ReplayConfigPage<ReplayUnconditionalIgnoreRow> list(Integer limit, Integer offset,
                                                               String internalTransactionCode, String tranCode,
                                                               String fieldName, ReplayConfigOperator operator) {
        return list(limit, offset, internalTransactionCode, tranCode, fieldName, null, operator);
    }

    public ReplayConfigPage<ReplayUnconditionalIgnoreRow> list(Integer limit, Integer offset,
                                                               String internalTransactionCode, String tranCode,
                                                               String fieldName, Integer reviewStatus,
                                                               ReplayConfigOperator operator) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        Integer resolvedReviewStatus = ReplayConfigValidation.optionalReviewStatus(reviewStatus);
        Set<String> serviceCodes = resolver.resolveFinalServiceCodes(internalTransactionCode);
        long total = dao.count(tranCode, fieldName, serviceCodes, resolvedReviewStatus);
        if (total == 0) {
            return new ReplayConfigPage<>(0, List.of());
        }
        List<ReplayUnconditionalIgnoreRow> rows = dao.list(tranCode, fieldName, serviceCodes,
                resolvedReviewStatus, resolvedLimit, resolvedOffset);
        return new ReplayConfigPage<>(total, enrich(rows, operator));
    }

    public ReplayUnconditionalIgnoreRow create(ReplayUnconditionalIgnoreCreateRequest request,
                                               ReplayConfigOperator operator) {
        String tranCode = ReplayConfigValidation.requireServiceCode(request == null ? null : request.tranCode());
        String fieldName = ReplayConfigValidation.requireText(request == null ? null : request.fieldName(), "忽略字段");
        try {
            return enrich(dao.create(tranCode, fieldName, operator), operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码与忽略字段重复）");
        }
    }

    public ReplayUnconditionalIgnoreRow update(long id, ReplayUnconditionalIgnoreUpdateRequest request,
                                               ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        String tranCode = ReplayConfigValidation.requireServiceCode(request == null ? null : request.tranCode());
        String fieldName = ReplayConfigValidation.requireText(request == null ? null : request.fieldName(), "忽略字段");
        int version = ReplayConfigValidation.requireVersion(request == null ? null : request.version());
        ReplayUnconditionalIgnoreRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != version) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        if (current.reviewStatus() == 1 && !ReplayConfigPersonResolver.matchesBankOwner(
                personResolver.resolveByServiceCodes(List.of(current.tranCode())).get(current.tranCode()),
                operator)) {
            throw new ReplayConfigReviewForbiddenException("该记录已审核，仅限审核人员修改");
        }
        if (Objects.equals(current.tranCode(), tranCode) && Objects.equals(current.fieldName(), fieldName)) {
            return enrich(current, operator);
        }
        try {
            return enrich(dao.update(current, tranCode, fieldName, operator), operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码与忽略字段重复）");
        }
    }

    public ReplayUnconditionalIgnoreRow review(long id, Integer version, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        int resolvedVersion = ReplayConfigValidation.requireVersion(version);
        ReplayUnconditionalIgnoreRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != resolvedVersion) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(current.tranCode()))
                .get(current.tranCode());
        if (current.reviewStatus() == 1) {
            return enrich(current, operator);
        }
        if (info == null) {
            throw new ReplayConfigReviewForbiddenException("无审核人");
        }
        if (!ReplayConfigPersonResolver.matchesBankOwner(info, operator)) {
            throw new ReplayConfigReviewForbiddenException(ReplayConfigPersonResolver.reviewForbiddenMessage(info));
        }
        return enrich(dao.review(current, operator), operator);
    }

    public void delete(long id, Integer version, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        int resolvedVersion = ReplayConfigValidation.requireVersion(version);
        ReplayUnconditionalIgnoreRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != resolvedVersion) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        dao.delete(current, operator);
    }

    public int batchDelete(List<ReplayConfigVersionedId> items, ReplayConfigOperator operator) {
        return dao.batchDelete(items, operator);
    }

    public ReplayConfigPage<ReplayConfigOperationView> operations(long id, Integer limit, Integer offset) {
        ReplayConfigValidation.requirePositiveId(id);
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        if (dao.findById(id) == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        return dao.operations(id, resolvedLimit, resolvedOffset);
    }

    private List<ReplayUnconditionalIgnoreRow> enrich(List<ReplayUnconditionalIgnoreRow> rows,
                                                      ReplayConfigOperator operator) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, ReplayConfigPersonInfo> infoMap = personResolver.resolveByServiceCodes(
                rows.stream().map(ReplayUnconditionalIgnoreRow::tranCode).toList());
        return rows.stream()
                .map(row -> enrich(row, infoMap.get(row.tranCode()), operator))
                .toList();
    }

    private ReplayUnconditionalIgnoreRow enrich(ReplayUnconditionalIgnoreRow row, ReplayConfigOperator operator) {
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(row.tranCode()))
                .get(row.tranCode());
        return enrich(row, info, operator);
    }

    private ReplayUnconditionalIgnoreRow enrich(ReplayUnconditionalIgnoreRow row, ReplayConfigPersonInfo info,
                                                ReplayConfigOperator operator) {
        String reason = ReplayConfigPersonResolver.reviewDisabledReason(info, operator, row.reviewStatus());
        return new ReplayUnconditionalIgnoreRow(row.id(), row.tranCode(), row.fieldName(), row.enableFlag(),
                row.createdAt(), row.updatedAt(), row.version(), row.reviewStatus(),
                info == null ? null : info.oldTransactionCode(),
                info == null ? null : info.developer(),
                info == null ? null : info.bankOwner(),
                reason == null, reason);
    }
}
