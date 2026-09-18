package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewResult;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigPersonInfo;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplaySortFieldCreateRequest;
import com.axonlink.ai.replay.dto.ReplaySortFieldDraft;
import com.axonlink.ai.replay.dto.ReplaySortFieldRow;
import com.axonlink.ai.replay.dto.ReplaySortFieldUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplaySortFieldDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReplaySortFieldService {

    private static final Pattern SORT_FIELD_PATTERN = Pattern.compile("^([^(]+)\\(([^)]+)\\)$");

    private final ReplaySortFieldDao dao;
    private final ReplayConfigServiceCodeResolver resolver;
    private final ReplayConfigPersonResolver personResolver;

    public ReplaySortFieldService(ReplaySortFieldDao dao, ReplayConfigServiceCodeResolver resolver,
                                  ReplayConfigPersonResolver personResolver) {
        this.dao = dao;
        this.resolver = resolver;
        this.personResolver = personResolver;
    }

    public ReplayConfigPage<ReplaySortFieldRow> list(Integer limit, Integer offset,
                                                     String internalTransactionCode, String origTrcd,
                                                     String origArryName, String origFieldName) {
        return list(limit, offset, internalTransactionCode, origTrcd, origArryName, origFieldName, null, null);
    }

    public ReplayConfigPage<ReplaySortFieldRow> list(Integer limit, Integer offset,
                                                     String internalTransactionCode, String origTrcd,
                                                     String origArryName, String origFieldName,
                                                     ReplayConfigOperator operator) {
        return list(limit, offset, internalTransactionCode, origTrcd, origArryName, origFieldName, null, operator);
    }

    public ReplayConfigPage<ReplaySortFieldRow> list(Integer limit, Integer offset,
                                                     String internalTransactionCode, String origTrcd,
                                                     String origArryName, String origFieldName,
                                                     Integer reviewStatus, ReplayConfigOperator operator) {
        return list(limit, offset, internalTransactionCode, origTrcd, origArryName, origFieldName,
                reviewStatus, null, operator);
    }

    public ReplayConfigPage<ReplaySortFieldRow> list(Integer limit, Integer offset,
                                                     String internalTransactionCode, String origTrcd,
                                                     String origArryName, String origFieldName,
                                                     Integer reviewStatus, Boolean reviewableByMe,
                                                     ReplayConfigOperator operator) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        Integer resolvedReviewStatus = ReplayConfigValidation.optionalReviewStatus(reviewStatus);
        Set<String> serviceCodes = ReplayConfigPersonResolver.intersect(
                resolver.resolveFinalServiceCodes(internalTransactionCode),
                Boolean.TRUE.equals(reviewableByMe)
                        ? personResolver.findReviewableServiceCodes(operator == null ? null : operator.empNo())
                        : null);
        long total = dao.count(origTrcd, origArryName, origFieldName, serviceCodes, resolvedReviewStatus);
        if (total == 0) {
            return new ReplayConfigPage<>(0, List.of());
        }
        List<ReplaySortFieldRow> rows = dao.list(origTrcd, origArryName, origFieldName, serviceCodes,
                resolvedReviewStatus, resolvedLimit, resolvedOffset);
        return new ReplayConfigPage<>(total, enrich(rows, operator));
    }

    public ReplayConfigBatchReviewResult batchReview(List<ReplayConfigVersionedId> items,
                                                     ReplayConfigOperator operator) {
        if (items == null || items.isEmpty()) {
            return new ReplayConfigBatchReviewResult(0, 0);
        }
        int approved = dao.batchReview(items, operator, row -> row.reviewStatus() == 0
                && ReplayConfigPersonResolver.matchesBankOwner(
                        personResolver.resolveByServiceCodes(List.of(row.origTrcd())).get(row.origTrcd()),
                        operator));
        return new ReplayConfigBatchReviewResult(approved, items.size() - approved);
    }

    public List<ReplaySortFieldRow> create(ReplaySortFieldCreateRequest request, ReplayConfigOperator operator) {
        String tranCode = ReplayConfigValidation.requireText(request == null ? null : request.tranCode(), "交易码");
        ParsedSortField oldParsed = parseSortField(
                ReplayConfigValidation.requireText(request == null ? null : request.oldSortField(), "老核心排序字段"),
                "老核心排序字段");
        ParsedSortField newParsed = parseSortField(
                ReplayConfigValidation.requireText(request == null ? null : request.newSortField(), "新核心排序字段"),
                "新核心排序字段");
        List<String> esfCodes = resolver.findEsfServiceCodes(tranCode);
        if (esfCodes.isEmpty()) {
            throw new IllegalArgumentException("交易码无映射：" + tranCode);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ReplaySortFieldDraft> drafts = new ArrayList<>();
        for (String esfCode : esfCodes) {
            if (esfCode == null) {
                continue;
            }
            String base = esfCode.replace(".", "");
            if (base.isBlank()) {
                continue;
            }
            addDraft(drafts, seen, base + "&sop", oldParsed);
            addDraft(drafts, seen, base + "&soap", newParsed);
            addDraft(drafts, seen, base + "&bzjson", newParsed);
        }
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("交易码无映射：" + tranCode);
        }
        try {
            return enrich(dao.createAll(drafts, operator), operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码、对象/数组名称与排序字段重复）");
        }
    }

    public ReplaySortFieldRow update(long id, ReplaySortFieldUpdateRequest request, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        String origTrcd = ReplayConfigValidation.requireServiceCode(request == null ? null : request.origTrcd());
        String origArryName = ReplayConfigValidation.requireText(
                request == null ? null : request.origArryName(), "对象/数组名称");
        String origFieldName = ReplayConfigValidation.requireText(
                request == null ? null : request.origFieldName(), "排序字段");
        int version = ReplayConfigValidation.requireVersion(request == null ? null : request.version());
        ReplaySortFieldRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != version) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        if (current.reviewStatus() == 1 && !ReplayConfigPersonResolver.matchesBankOwner(
                personResolver.resolveByServiceCodes(List.of(current.origTrcd())).get(current.origTrcd()),
                operator)) {
            throw new ReplayConfigReviewForbiddenException("该记录已审核，仅限审核人员修改");
        }
        if (Objects.equals(current.origTrcd(), origTrcd)
                && Objects.equals(current.origArryName(), origArryName)
                && Objects.equals(current.origFieldName(), origFieldName)) {
            return enrich(current, operator);
        }
        try {
            return enrich(dao.update(current, origTrcd, origArryName, origFieldName, operator), operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码、对象/数组名称与排序字段重复）");
        }
    }

    public ReplaySortFieldRow review(long id, Integer version, ReplayConfigOperator operator) {
        ReplayConfigValidation.requirePositiveId(id);
        int resolvedVersion = ReplayConfigValidation.requireVersion(version);
        ReplaySortFieldRow current = dao.findById(id);
        if (current == null) {
            throw new ReplayConfigNotFoundException("记录不存在");
        }
        if (current.version() != resolvedVersion) {
            throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
        }
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(current.origTrcd()))
                .get(current.origTrcd());
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
        ReplaySortFieldRow current = dao.findById(id);
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

    private static void addDraft(List<ReplaySortFieldDraft> drafts, Set<String> seen, String origTrcd,
                                 ParsedSortField parsed) {
        String key = origTrcd + "|" + parsed.arryName() + "|" + parsed.fieldName();
        if (seen.add(key)) {
            drafts.add(new ReplaySortFieldDraft(origTrcd, parsed.arryName(), parsed.fieldName()));
        }
    }

    /** 解析 A.B 或 A(B,C)：A 为对象/数组名称，其余为排序字段。 */
    private static ParsedSortField parseSortField(String value, String label) {
        String trimmed = value.trim();
        Matcher matcher = SORT_FIELD_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            String arrayName = matcher.group(1).trim();
            String fieldName = matcher.group(2).trim();
            if (!arrayName.isEmpty() && !fieldName.isEmpty()) {
                return new ParsedSortField(arrayName, fieldName);
            }
            throw new IllegalArgumentException(label + "格式不正确，应为 A.B 或 A(B,C)");
        }
        int dot = trimmed.indexOf('.');
        if (dot > 0 && dot < trimmed.length() - 1) {
            String arrayName = trimmed.substring(0, dot).trim();
            String fieldName = trimmed.substring(dot + 1).trim();
            if (!arrayName.isEmpty() && !fieldName.isEmpty()) {
                return new ParsedSortField(arrayName, fieldName);
            }
        }
        throw new IllegalArgumentException(label + "格式不正确，应为 A.B 或 A(B,C)");
    }

    private List<ReplaySortFieldRow> enrich(List<ReplaySortFieldRow> rows, ReplayConfigOperator operator) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, ReplayConfigPersonInfo> infoMap = personResolver.resolveByServiceCodes(
                rows.stream().map(ReplaySortFieldRow::origTrcd).toList());
        return rows.stream()
                .map(row -> enrich(row, infoMap.get(row.origTrcd()), operator))
                .toList();
    }

    private ReplaySortFieldRow enrich(ReplaySortFieldRow row, ReplayConfigOperator operator) {
        ReplayConfigPersonInfo info = personResolver.resolveByServiceCodes(List.of(row.origTrcd()))
                .get(row.origTrcd());
        return enrich(row, info, operator);
    }

    private ReplaySortFieldRow enrich(ReplaySortFieldRow row, ReplayConfigPersonInfo info,
                                      ReplayConfigOperator operator) {
        String reason = ReplayConfigPersonResolver.reviewDisabledReason(info, operator, row.reviewStatus());
        return new ReplaySortFieldRow(row.id(), row.origTrcd(), row.origArryName(), row.origFieldName(),
                row.tranMode(), row.createdAt(), row.updatedAt(), row.version(), row.reviewStatus(),
                info == null ? null : info.oldTransactionCode(),
                info == null ? null : info.developer(),
                info == null ? null : info.bankOwner(),
                reason == null, reason);
    }

    private record ParsedSortField(String arryName, String fieldName) {
    }
}
