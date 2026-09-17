package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ReplayDatabaseComparisonConditionCodec {

    private final ObjectMapper objectMapper;

    public ReplayDatabaseComparisonConditionCodec() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    ReplayDatabaseComparisonConditionCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReplayDbCompareConditionTree normalize(ReplayDbCompareConditionTree tree) {
        if (tree == null || tree.groups().isEmpty()) {
            return null;
        }
        List<ReplayDbCompareConditionGroup> groups = tree.groups().stream()
                .map(group -> new ReplayDbCompareConditionGroup(
                        group.connector(),
                        group.conditions().stream().map(this::normalizeCondition).toList()))
                .toList();
        return new ReplayDbCompareConditionTree(tree.connector(), groups);
    }

    public String encode(ReplayDbCompareConditionTree tree) {
        ReplayDbCompareConditionTree normalized = normalize(tree);
        if (normalized == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("比对条件无法序列化", exception);
        }
    }

    public ReplayDbCompareConditionTree decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return normalize(objectMapper.readValue(json, ReplayDbCompareConditionTree.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("已保存的比对条件格式无效", exception);
        }
    }

    private ReplayDbCompareCondition normalizeCondition(ReplayDbCompareCondition condition) {
        String columnName = condition.columnName() == null
                ? null
                : condition.columnName().trim().toLowerCase(Locale.ROOT);
        return new ReplayDbCompareCondition(columnName, condition.operator(), condition.values());
    }
}
