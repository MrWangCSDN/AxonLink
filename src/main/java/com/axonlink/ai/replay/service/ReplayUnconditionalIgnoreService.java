package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayUnconditionalIgnoreDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ReplayUnconditionalIgnoreService {

    private final ReplayUnconditionalIgnoreDao dao;
    private final ReplayConfigServiceCodeResolver resolver;

    public ReplayUnconditionalIgnoreService(ReplayUnconditionalIgnoreDao dao,
                                            ReplayConfigServiceCodeResolver resolver) {
        this.dao = dao;
        this.resolver = resolver;
    }

    public ReplayConfigPage<ReplayUnconditionalIgnoreRow> list(Integer limit, Integer offset,
                                                               String internalTransactionCode, String tranCode,
                                                               String fieldName) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        Set<String> serviceCodes = resolver.resolveFinalServiceCodes(internalTransactionCode);
        long total = dao.count(tranCode, fieldName, serviceCodes);
        if (total == 0) {
            return new ReplayConfigPage<>(0, List.of());
        }
        return new ReplayConfigPage<>(total, dao.list(tranCode, fieldName, serviceCodes, resolvedLimit, resolvedOffset));
    }

    public ReplayUnconditionalIgnoreRow create(ReplayUnconditionalIgnoreCreateRequest request,
                                               ReplayConfigOperator operator) {
        String tranCode = ReplayConfigValidation.requireServiceCode(request == null ? null : request.tranCode());
        String fieldName = ReplayConfigValidation.requireText(request == null ? null : request.fieldName(), "忽略字段");
        try {
            return dao.create(tranCode, fieldName, operator);
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
        if (Objects.equals(current.tranCode(), tranCode) && Objects.equals(current.fieldName(), fieldName)) {
            return current;
        }
        try {
            return dao.update(current, tranCode, fieldName, operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码与忽略字段重复）");
        }
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
}
