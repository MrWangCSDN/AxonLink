package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConditionalRmoveCreateRequest;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveRow;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveUpdateRequest;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigPersonInfo;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.persistence.ReplayConditionalRmoveDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class ReplayConditionalRmoveService {

    private static final int MAX_INDEX_RETRIES = 5;

    private final ReplayConditionalRmoveDao dao;
    private final ReplayConfigServiceCodeResolver resolver;
    private final ReplayConfigPersonResolver personResolver;

    public ReplayConditionalRmoveService(ReplayConditionalRmoveDao dao,
                                         ReplayConfigServiceCodeResolver resolver,
                                         ReplayConfigPersonResolver personResolver) {
        this.dao = dao;
        this.resolver = resolver;
        this.personResolver = personResolver;
    }

    public ReplayConfigPage<ReplayConditionalRmoveRow> list(Integer limit, Integer offset,
                                                            String internalTransactionCode, String origTrcd,
                                                            String fieldRmoveName, Integer fieldFileFlag) {
        return list(limit, offset, internalTransactionCode, origTrcd, fieldRmoveName, fieldFileFlag, null);
    }

    public ReplayConfigPage<ReplayConditionalRmoveRow> list(Integer limit, Integer offset,
                                                            String internalTransactionCode, String origTrcd,
                                                            String fieldRmoveName, Integer fieldFileFlag,
                                                            ReplayConfigOperator operator) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        Set<String> serviceCodes = resolver.resolveFinalServiceCodes(internalTransactionCode);
        long total = dao.count(origTrcd, fieldRmoveName, fieldFileFlag, serviceCodes);
        if (total == 0) {
            return new ReplayConfigPage<>(0, List.of());
        }
        List<ReplayConditionalRmoveRow> rows = dao.list(origTrcd, fieldRmoveName, fieldFileFlag, serviceCodes,
                resolvedLimit, resolvedOffset);
        return new ReplayConfigPage<>(total, enrich(rows, operator));
    }

    public ReplayConditionalRmoveRow create(ReplayConditionalRmoveCreateRequest request,
                                            ReplayConfigOperator operator) {
        String origTrcd = ReplayConfigValidation.requireServiceCode(request == null ? null : request.origTrcd());
        String fieldRmoveName = ReplayConfigValidation.requireText(
                request == null ? null : request.fieldRmoveName(), "忽略字段");
        int fieldFileFlag = ReplayConfigValidation.requireFieldFileFlag(
                request == null ? null : request.fieldFileFlag());
        String origFieldCond = ReplayConfigValidation.normalizeNullableText(
                request == null ? null : request.origFieldCond());
        String destFieldCond = ReplayConfigValidation.normalizeNullableText(
                request == null ? null : request.destFieldCond());
        return enrich(withIndexRetry(() -> dao.create(origTrcd, fieldRmoveName, fieldFileFlag, origFieldCond,
                destFieldCond, operator)), operator);
    }

    public ReplayConditionalRmoveRow update(long id, ReplayConditionalRmoveUpdateRequest request,
                                            ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        String origTrcd = ReplayConfigValidation.requireServiceCode(request == null ? null : request.origTrcd());
        String fieldRmoveName = ReplayConfigValidation.requireText(
                request == null ? null : request.fieldRmoveName(), "忽略字段");
        int fieldFileFlag = ReplayConfigValidation.requireFieldFileFlag(
                request == null ? null : request.fieldFileFlag());
        String origFieldCond = ReplayConfigValidation.normalizeNullableText(
                request == null ? null : request.origFieldCond());
        String destFieldCond = ReplayConfigValidation.normalizeNullableText(
                request == null ? null : request.destFieldCond());
        int version = ReplayConfigValidation.requireVersion(request == null ? null : request.version());
        ReplayConditionalRmoveRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != version) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        boolean unchanged = Objects.equals(current.origTrcd(), origTrcd)
                && Objects.equals(current.fieldRmoveName(), fieldRmoveName)
                && current.fieldFileFlag() == fieldFileFlag
                && Objects.equals(current.origFieldCond(), origFieldCond)
                && Objects.equals(current.destFieldCond(), destFieldCond);
        if (unchanged) {
            return enrich(current, operator);
        }
        return enrich(withIndexRetry(() -> dao.update(current, origTrcd, fieldRmoveName, fieldFileFlag,
                origFieldCond, destFieldCond, operator)), operator);
    }

    public ReplayConditionalRmoveRow review(long id, Integer version, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        int resolvedVersion = ReplayConfigValidation.requireVersion(version);
        ReplayConditionalRmoveRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != resolvedVersion) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(current.origTrcd()))
                .get(current.origTrcd());
        if (info == null) {
            throw new ReplayConfigReviewForbiddenException("无审核人");
        }
        if (current.reviewStatus() == 1) {
            throw new ReplayConfigConflictException("该记录已审核");
        }
        if (!ReplayConfigPersonResolver.matchesBankOwner(info, operator)) {
            throw new ReplayConfigReviewForbiddenException("仅行方负责人可审核");
        }
        return enrich(dao.review(current, operator), operator);
    }

    public void delete(long id, Integer version, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        int resolvedVersion = ReplayConfigValidation.requireVersion(version);
        ReplayConditionalRmoveRow current = dao.findById(id);
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

    private ReplayConditionalRmoveRow withIndexRetry(Supplier<ReplayConditionalRmoveRow> action) {
        for (int attempt = 0; attempt < MAX_INDEX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (DuplicateKeyException exception) {
                if (attempt == MAX_INDEX_RETRIES - 1) {
                    throw new ReplayConfigConflictException("字段索引分配冲突，请重试");
                }
            }
        }
        throw new ReplayConfigConflictException("字段索引分配冲突，请重试");
    }

    private List<ReplayConditionalRmoveRow> enrich(List<ReplayConditionalRmoveRow> rows,
                                                   ReplayConfigOperator operator) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, ReplayConfigPersonInfo> infoMap = personResolver.resolveByServiceCodes(
                rows.stream().map(ReplayConditionalRmoveRow::origTrcd).toList());
        return rows.stream()
                .map(row -> enrich(row, infoMap.get(row.origTrcd()), operator))
                .toList();
    }

    private ReplayConditionalRmoveRow enrich(ReplayConditionalRmoveRow row, ReplayConfigOperator operator) {
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(row.origTrcd()))
                .get(row.origTrcd());
        return enrich(row, info, operator);
    }

    private ReplayConditionalRmoveRow enrich(ReplayConditionalRmoveRow row, ReplayConfigPersonInfo info,
                                             ReplayConfigOperator operator) {
        String reason = ReplayConfigPersonResolver.reviewDisabledReason(info, operator, row.reviewStatus());
        return new ReplayConditionalRmoveRow(row.id(), row.origTrcd(), row.fieldRmoveName(), row.fieldFielState(),
                row.fieldFileIndx(), row.fieldFileFlag(), row.origFieldCond(), row.destFieldCond(),
                row.createdAt(), row.updatedAt(), row.version(), row.reviewStatus(),
                info == null ? null : info.oldTransactionCode(),
                info == null ? null : info.developer(),
                info == null ? null : info.bankOwner(),
                reason == null, reason);
    }
}
