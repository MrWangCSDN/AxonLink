package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReplaySortFieldService {

    private static final Pattern SORT_FIELD_PATTERN = Pattern.compile("^([^(]+)\\(([^)]+)\\)$");

    private final ReplaySortFieldDao dao;
    private final ReplayConfigServiceCodeResolver resolver;

    public ReplaySortFieldService(ReplaySortFieldDao dao, ReplayConfigServiceCodeResolver resolver) {
        this.dao = dao;
        this.resolver = resolver;
    }

    public ReplayConfigPage<ReplaySortFieldRow> list(Integer limit, Integer offset,
                                                     String internalTransactionCode, String origTrcd,
                                                     String origArryName, String origFieldName) {
        int resolvedLimit = ReplayConfigValidation.pageLimit(limit);
        int resolvedOffset = ReplayConfigValidation.pageOffset(offset);
        Set<String> serviceCodes = resolver.resolveFinalServiceCodes(internalTransactionCode);
        long total = dao.count(origTrcd, origArryName, origFieldName, serviceCodes);
        if (total == 0) {
            return new ReplayConfigPage<>(0, List.of());
        }
        return new ReplayConfigPage<>(total,
                dao.list(origTrcd, origArryName, origFieldName, serviceCodes, resolvedLimit, resolvedOffset));
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
            return dao.createAll(drafts, operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码、对象/数组名称与排序字段重复）");
        }
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

    private record ParsedSortField(String arryName, String fieldName) {
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
        if (Objects.equals(current.origTrcd(), origTrcd)
                && Objects.equals(current.origArryName(), origArryName)
                && Objects.equals(current.origFieldName(), origFieldName)) {
            return current;
        }
        try {
            return dao.update(current, origTrcd, origArryName, origFieldName, operator);
        } catch (DuplicateKeyException exception) {
            throw new ReplayConfigConflictException("配置已存在（服务码、对象/数组名称与排序字段重复）");
        }
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
}
