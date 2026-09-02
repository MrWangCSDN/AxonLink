package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueFieldChange;
import com.axonlink.ai.replay.dto.ReplayIssueOriginalDataItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Projects stored replay issue snapshots into the small tracking view shown by the UI. */
public final class ReplayIssueTrackingProjection {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final List<FieldDefinition> IMPORT_FIELDS = List.of(
            new FieldDefinition("transactionCode", "交易码"),
            new FieldDefinition("transactionName", "交易名称"),
            new FieldDefinition("issueLevel", "问题级别"),
            new FieldDefinition("issueDescription", "问题描述"),
            new FieldDefinition("fieldName", "字段名"),
            new FieldDefinition("transactionOwner", "交易负责人"),
            new FieldDefinition("resolvedDate", "解决日期"),
            new FieldDefinition("cooperationGroup", "协同组"),
            new FieldDefinition("resolver", "处理人"),
            new FieldDefinition("serialNo", "流水号"),
            new FieldDefinition("dataRepairDate", "数据修复日期"),
            new FieldDefinition("affectedTransactionCount", "影响交易数"),
            new FieldDefinition("batchNo", "批次号"));

    private static final List<FieldDefinition> CHANGE_FIELDS = List.of(
            new FieldDefinition("issueStatus", "问题状态"),
            new FieldDefinition("issueType", "问题类型"),
            new FieldDefinition("initialAnalysis", "初步问题分析"),
            new FieldDefinition("finalSolution", "最终处理方案"),
            new FieldDefinition("cooperationPerson", "需协同人"),
            new FieldDefinition("remark", "备注"),
            new FieldDefinition("plannedCompletionDate", "计划验证日期"),
            new FieldDefinition("reviewStatus", "审核状态"),
            new FieldDefinition("reviewer", "审核人"),
            new FieldDefinition("defectRepairDate", "缺陷修复日期"),
            new FieldDefinition("transactionCode", "交易码"),
            new FieldDefinition("transactionName", "交易名称"),
            new FieldDefinition("issueLevel", "问题级别"),
            new FieldDefinition("issueDescription", "问题描述"),
            new FieldDefinition("fieldName", "字段名"),
            new FieldDefinition("transactionOwner", "交易负责人"),
            new FieldDefinition("resolvedDate", "解决日期"),
            new FieldDefinition("cooperationGroup", "协同组"),
            new FieldDefinition("resolver", "处理人"),
            new FieldDefinition("serialNo", "流水号"),
            new FieldDefinition("dataRepairDate", "数据修复日期"),
            new FieldDefinition("affectedTransactionCount", "影响交易数"),
            new FieldDefinition("batchNo", "批次号"));

    private ReplayIssueTrackingProjection() {
    }

    public static List<ReplayIssueFieldChange> fieldChanges(String beforeSnapshot, String afterSnapshot) {
        JsonNode before = parse(beforeSnapshot);
        JsonNode after = parse(afterSnapshot);
        List<ReplayIssueFieldChange> changes = new ArrayList<>();
        for (FieldDefinition field : CHANGE_FIELDS) {
            String beforeValue = value(before, field.key());
            String afterValue = value(after, field.key());
            if (!normalize(beforeValue).equals(normalize(afterValue))) {
                changes.add(new ReplayIssueFieldChange(field.label(), display(beforeValue), display(afterValue)));
            }
        }
        return List.copyOf(changes);
    }

    public static List<ReplayIssueOriginalDataItem> originalData(String incomingSnapshot) {
        JsonNode incoming = parse(incomingSnapshot);
        List<ReplayIssueOriginalDataItem> items = new ArrayList<>();
        for (FieldDefinition field : IMPORT_FIELDS) {
            String value = value(incoming, field.key());
            if (!normalize(value).isEmpty()) {
                items.add(new ReplayIssueOriginalDataItem(field.label(), display(value)));
            }
        }
        return List.copyOf(items);
    }

    public static boolean hasFieldChanges(String beforeSnapshot, String afterSnapshot) {
        return !fieldChanges(beforeSnapshot, afterSnapshot).isEmpty();
    }

    private static JsonNode parse(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(snapshot);
        } catch (Exception exception) {
            throw new IllegalArgumentException("问题跟踪快照格式不正确", exception);
        }
    }

    private static String value(JsonNode node, String key) {
        if ("cooperationPerson".equals(key)) {
            return person(node, "cooperationPersonRealName", "cooperationPersonUsername");
        }
        if ("reviewer".equals(key)) {
            return person(node, "reviewerRealName", "reviewerUsername");
        }
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String person(JsonNode node, String realNameKey, String usernameKey) {
        String realName = value(node, realNameKey);
        String username = value(node, usernameKey);
        if (realName == null || realName.isBlank()) return username;
        if (username == null || username.isBlank()) return realName;
        return realName + "(" + username + ")";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String display(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? "空" : normalized;
    }

    private record FieldDefinition(String key, String label) {
    }
}
