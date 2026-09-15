package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigBatchDeleteRequest;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 回放配置字段与分页参数校验。集中在此处避免四类配置口径漂移。 */
public final class ReplayConfigValidation {

    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_BATCH_DELETE = 100;
    private static final Set<Integer> ALLOWED_LIMITS = Set.of(10, 30, 50, 100);
    private static final Pattern SERVICE_CODE_PATTERN = Pattern.compile("^[0-9A-Za-z]+&(sop|soap|bzjson)$");

    private ReplayConfigValidation() {
    }

    /** 最终服务码：去除首尾空格、保持大小写并满足严格格式。 */
    public static String requireServiceCode(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("服务码不能为空");
        }
        if (!SERVICE_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("服务码格式不正确，应为 <服务码>&sop|&soap|&bzjson");
        }
        return trimmed;
    }

    /** 必填文本：去除首尾空格且不允许全空白。 */
    public static String requireText(String value, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return trimmed;
    }

    /** 空字符串归一化为 null；非空内容原样保留（含内部格式与换行）。 */
    public static String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    /** 错误码：空字符串归一化为 null，二者不能同时为空。 */
    public static String normalizeResponseCode(String value) {
        return normalizeNullableText(value);
    }

    public static void requireAtLeastOneResponseCode(String oldRespCode, String newRespCode) {
        if (oldRespCode == null && newRespCode == null) {
            throw new IllegalArgumentException("老核心错误码与新核心错误码不能同时为空");
        }
    }

    /** fieldFileFlag 必填且只允许 1 或 2。 */
    public static int requireFieldFileFlag(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("字段标识不能为空");
        }
        if (value != 1 && value != 2) {
            throw new IllegalArgumentException("字段标识只允许 1 或 2");
        }
        return value;
    }

    public static int requireVersion(Integer version) {
        if (version == null) {
            throw new IllegalArgumentException("缺少 version");
        }
        return version;
    }

    public static int pageLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (!ALLOWED_LIMITS.contains(limit)) {
            throw new IllegalArgumentException("分页大小只允许 10、30、50、100");
        }
        return limit;
    }

    public static int pageOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("偏移量不能小于 0");
        }
        return offset;
    }

    /** 批量删除：1 至 100 条，id 不得重复，version 必填。 */
    public static List<ReplayConfigVersionedId> requireBatchItems(ReplayConfigBatchDeleteRequest request) {
        List<ReplayConfigVersionedId> items = request == null ? null : request.items();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("批量删除至少需要 1 条记录");
        }
        if (items.size() > MAX_BATCH_DELETE) {
            throw new IllegalArgumentException("批量删除单次最多 100 条");
        }
        Set<Long> ids = new HashSet<>();
        for (ReplayConfigVersionedId item : items) {
            if (item == null || item.id() <= 0) {
                throw new IllegalArgumentException("批量删除记录 id 不合法");
            }
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("批量删除记录不能重复");
            }
            if (item.version() == null) {
                throw new IllegalArgumentException("批量删除记录缺少 version");
            }
        }
        return items;
    }

    public static void requirePositiveId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("记录 id 不合法");
        }
    }
}
