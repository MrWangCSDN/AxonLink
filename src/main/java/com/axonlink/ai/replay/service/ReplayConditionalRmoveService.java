package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveCreateRequest;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveRow;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayConditionalRmoveDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class ReplayConditionalRmoveService {

    private static final int MAX_INDEX_RETRIES = 5;

    private final ReplayConditionalRmoveDao dao;
    private final ReplayConfigServiceCodeResolver resolver;

    public ReplayConditionalRmoveService(ReplayConditionalRmoveDao dao,
                                         ReplayConfigServiceCodeResolver resolver) {
        this.dao = dao;
        this.resolver = resolver;
    }

    public ReplayConfigPage<ReplayConditionalRmoveRow> list(Integer limit, Integer offset,
                                                            String internalTransactionCode, String origTrcd,
                                                            String fieldRmoveName, Integer fieldFileFlag) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        Set<String> serviceCodes = resolver.resolveFinalServiceCodes(internalTransactionCode);
        long total = dao.count(origTrcd, fieldRmoveName, fieldFileFlag, serviceCodes);
        if (total == 0) {
            return new ReplayConfigPage<>(0, List.of());
        }
        return new ReplayConfigPage<>(total,
                dao.list(origTrcd, fieldRmoveName, fieldFileFlag, serviceCodes, resolvedLimit, resolvedOffset));
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
        return withIndexRetry(() -> dao.create(origTrcd, fieldRmoveName, fieldFileFlag, origFieldCond,
                destFieldCond, operator));
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
            return current;
        }
        return withIndexRetry(() -> dao.update(current, origTrcd, fieldRmoveName, fieldFileFlag, origFieldCond,
                destFieldCond, operator));
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
}
