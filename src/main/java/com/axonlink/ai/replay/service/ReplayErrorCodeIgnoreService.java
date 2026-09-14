package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayErrorCodeIgnoreDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ReplayErrorCodeIgnoreService {

    private final ReplayErrorCodeIgnoreDao dao;
    private final ReplayConfigServiceCodeResolver resolver;

    public ReplayErrorCodeIgnoreService(ReplayErrorCodeIgnoreDao dao,
                                        ReplayConfigServiceCodeResolver resolver) {
        this.dao = dao;
        this.resolver = resolver;
    }

    public ReplayConfigPage<ReplayErrorCodeIgnoreRow> list(Integer limit, Integer offset,
                                                           String internalTransactionCode, String serviceCode,
                                                           String oldRespCode, String newRespCode) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        Set<String> serviceCodes = resolver.resolveFinalServiceCodes(internalTransactionCode);
        long total = dao.count(serviceCode, oldRespCode, newRespCode, serviceCodes);
        if (total == 0) {
            return new ReplayConfigPage<>(0, List.of());
        }
        return new ReplayConfigPage<>(total,
                dao.list(serviceCode, oldRespCode, newRespCode, serviceCodes, resolvedLimit, resolvedOffset));
    }

    public ReplayErrorCodeIgnoreRow create(ReplayErrorCodeIgnoreCreateRequest request,
                                           ReplayConfigOperator operator) {
        String serviceCode = ReplayConfigValidation.requireServiceCode(request == null ? null : request.serviceCode());
        String oldRespCode = ReplayConfigValidation.normalizeResponseCode(
                request == null ? null : request.oldRespCode());
        String newRespCode = ReplayConfigValidation.normalizeResponseCode(
                request == null ? null : request.newRespCode());
        ReplayConfigValidation.requireAtLeastOneResponseCode(oldRespCode, newRespCode);
        try {
            return dao.create(serviceCode, oldRespCode, newRespCode, operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码与错误码组合重复）");
        }
    }

    public ReplayErrorCodeIgnoreRow update(long id, ReplayErrorCodeIgnoreUpdateRequest request,
                                           ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        String serviceCode = ReplayConfigValidation.requireServiceCode(request == null ? null : request.serviceCode());
        String oldRespCode = ReplayConfigValidation.normalizeResponseCode(
                request == null ? null : request.oldRespCode());
        String newRespCode = ReplayConfigValidation.normalizeResponseCode(
                request == null ? null : request.newRespCode());
        ReplayConfigValidation.requireAtLeastOneResponseCode(oldRespCode, newRespCode);
        int version = ReplayConfigValidation.requireVersion(request == null ? null : request.version());
        ReplayErrorCodeIgnoreRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != version) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        if (Objects.equals(current.serviceCode(), serviceCode)
                && Objects.equals(current.oldRespCode(), oldRespCode)
                && Objects.equals(current.newRespCode(), newRespCode)) {
            return current;
        }
        try {
            return dao.update(current, serviceCode, oldRespCode, newRespCode, operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码与错误码组合重复）");
        }
    }

    public void delete(long id, Integer version, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        int resolvedVersion = ReplayConfigValidation.requireVersion(version);
        ReplayErrorCodeIgnoreRow current = dao.findById(id);
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
}
