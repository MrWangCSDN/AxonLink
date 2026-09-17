package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetailDraft;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditOperation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareChangeType;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class ReplayDatabaseComparisonAuditDiff {

    public List<ReplayDbCompareAuditDetailDraft> compare(
            ReplayDbCompareState before,
            ReplayDbCompareState after,
            ReplayDbCompareAuditOperation operation) {
        if (operation == ReplayDbCompareAuditOperation.DELETE) {
            return deletionDetails(before);
        }
        if (before == null) {
            return creationDetails(after);
        }
        if (after == null) {
            throw new IllegalArgumentException("非删除操作必须提供修改后状态");
        }

        List<ReplayDbCompareAuditDetailDraft> details = new ArrayList<>();
        addModify(details, "tableComment", "表中文名",
                text(before.tableComment()), text(after.tableComment()));
        addModify(details, "domainName", "领域",
                text(before.domainName()), text(after.domainName()));
        addModify(details, "groupOwner", "小组负责人",
                owner(before), owner(after));
        addModify(details, "registeredDate", "登记日期",
                value(before.registeredDate()), value(after.registeredDate()));
        addModify(details, "deleted", "删除状态",
                Boolean.toString(before.deleted()), Boolean.toString(after.deleted()));
        addModify(details, "whereCondition", "WHERE 条件",
                condition(before), condition(after));
        addModify(details, "compareLimit", "比对条数",
                limit(before.compareLimit()), limit(after.compareLimit()));
        addFieldDifferences(details, before.fields(), after.fields());
        return List.copyOf(details);
    }

    private List<ReplayDbCompareAuditDetailDraft> creationDetails(ReplayDbCompareState after) {
        if (after == null) {
            throw new IllegalArgumentException("新增或重新登记必须提供修改后状态");
        }
        List<ReplayDbCompareAuditDetailDraft> details = new ArrayList<>();
        addAdded(details, "tableComment", "表中文名", text(after.tableComment()));
        addAdded(details, "domainName", "领域", text(after.domainName()));
        addAdded(details, "groupOwner", "小组负责人", owner(after));
        addAdded(details, "registeredDate", "登记日期", value(after.registeredDate()));
        addAdded(details, "deleted", "删除状态", Boolean.toString(after.deleted()));
        addAdded(details, "whereCondition", "WHERE 条件", condition(after));
        addAdded(details, "compareLimit", "比对条数", limit(after.compareLimit()));
        fieldsByName(after.fields()).values().forEach(field ->
                details.add(fieldDetail(ReplayDbCompareChangeType.ADD, field, null, fieldValue(field))));
        return List.copyOf(details);
    }

    private List<ReplayDbCompareAuditDetailDraft> deletionDetails(ReplayDbCompareState before) {
        if (before == null) {
            throw new IllegalArgumentException("删除必须提供修改前状态");
        }
        List<ReplayDbCompareAuditDetailDraft> details = new ArrayList<>();
        details.add(new ReplayDbCompareAuditDetailDraft(
                ReplayDbCompareChangeType.MODIFY, "deleted", "删除状态", "false", "true"));
        fieldsByName(before.fields()).values().forEach(field ->
                details.add(fieldDetail(ReplayDbCompareChangeType.DELETE, field, fieldValue(field), null)));
        return List.copyOf(details);
    }

    private void addFieldDifferences(List<ReplayDbCompareAuditDetailDraft> details,
                                     List<ReplayDbCompareField> beforeFields,
                                     List<ReplayDbCompareField> afterFields) {
        Map<String, ReplayDbCompareField> before = fieldsByName(beforeFields);
        Map<String, ReplayDbCompareField> after = fieldsByName(afterFields);
        List<String> names = new ArrayList<>();
        names.addAll(before.keySet());
        after.keySet().stream().filter(name -> !before.containsKey(name)).forEach(names::add);
        names.sort(Comparator.naturalOrder());

        for (String name : names) {
            ReplayDbCompareField oldField = before.get(name);
            ReplayDbCompareField newField = after.get(name);
            if (oldField == null) {
                details.add(addedFieldDetail(newField));
                continue;
            }
            if (newField == null) {
                details.add(fieldDetail(ReplayDbCompareChangeType.DELETE, oldField, fieldValue(oldField), null));
                continue;
            }
            if (!Objects.equals(text(oldField.columnComment()), text(newField.columnComment()))) {
                details.add(fieldDetail(ReplayDbCompareChangeType.MODIFY, newField,
                        text(oldField.columnComment()), text(newField.columnComment())));
            }
            if (oldField.primaryKey() != newField.primaryKey()) {
                details.add(new ReplayDbCompareAuditDetailDraft(
                        ReplayDbCompareChangeType.MODIFY,
                        "comparisonFields." + name + ".primaryKey",
                        "比对字段 " + name + " 主键属性",
                        Boolean.toString(oldField.primaryKey()),
                        Boolean.toString(newField.primaryKey())));
            }
            if (oldField.comparisonOrder() != newField.comparisonOrder()) {
                details.add(fieldDetail(ReplayDbCompareChangeType.REORDER, newField,
                        Integer.toString(oldField.comparisonOrder()),
                        Integer.toString(newField.comparisonOrder())));
            }
        }
    }

    private Map<String, ReplayDbCompareField> fieldsByName(List<ReplayDbCompareField> fields) {
        Map<String, ReplayDbCompareField> result = new LinkedHashMap<>();
        fields.stream()
                .sorted(Comparator.comparing(field -> identifier(field.columnName())))
                .forEach(field -> result.put(identifier(field.columnName()), field));
        return result;
    }

    private ReplayDbCompareAuditDetailDraft fieldDetail(
            ReplayDbCompareChangeType type,
            ReplayDbCompareField field,
            String beforeValue,
            String afterValue) {
        String name = identifier(field.columnName());
        return new ReplayDbCompareAuditDetailDraft(
                type, "comparisonFields." + name, "比对字段 " + name, beforeValue, afterValue);
    }

    private ReplayDbCompareAuditDetailDraft addedFieldDetail(ReplayDbCompareField field) {
        String name = identifier(field.columnName());
        String label = "比对字段 " + name + (field.primaryKey() ? "（母库新增主键）" : "");
        return new ReplayDbCompareAuditDetailDraft(
                ReplayDbCompareChangeType.ADD,
                "comparisonFields." + name,
                label,
                null,
                fieldValue(field));
    }

    private String fieldValue(ReplayDbCompareField field) {
        return text(field.columnComment());
    }

    private void addModify(List<ReplayDbCompareAuditDetailDraft> details, String code, String label,
                           String before, String after) {
        if (!Objects.equals(before, after)) {
            details.add(new ReplayDbCompareAuditDetailDraft(
                    ReplayDbCompareChangeType.MODIFY, code, label, before, after));
        }
    }

    private void addAdded(List<ReplayDbCompareAuditDetailDraft> details, String code, String label, String after) {
        details.add(new ReplayDbCompareAuditDetailDraft(
                ReplayDbCompareChangeType.ADD, code, label, null, after));
    }

    private String owner(ReplayDbCompareState state) {
        return text(state.groupOwnerEmpNo()) + "/" + text(state.groupOwnerName());
    }

    private String value(Object value) {
        return value == null ? null : value.toString();
    }

    private String condition(ReplayDbCompareState state) {
        if (state.whereCondition() == null || state.whereCondition().groups().isEmpty()) {
            return "未配置";
        }
        return new ReplayDatabaseComparisonConditionCodec().encode(state.whereCondition());
    }

    private String limit(Long value) {
        return value == null ? "全表" : value.toString();
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String identifier(String value) {
        return text(value) == null ? "" : text(value).toLowerCase(Locale.ROOT);
    }
}
