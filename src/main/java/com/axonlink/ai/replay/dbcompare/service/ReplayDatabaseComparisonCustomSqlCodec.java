package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSql;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Encodes the immutable SQL result schema shared by registrations and versions. */
public final class ReplayDatabaseComparisonCustomSqlCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReplayDatabaseComparisonCustomSqlCodec() { }

    public static String encodeColumns(ReplayDbCompareCustomSql definition) {
        if (definition == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(definition.resultColumns());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("自定义SQL结果字段编码失败", exception);
        }
    }

    public static ReplayDbCompareCustomSql decode(
            String registrationType, String sourceTableName, String sql, String resultColumnsJson) {
        if (!"CUSTOM_SQL".equals(registrationType)) {
            return null;
        }
        if (sourceTableName == null || sql == null || resultColumnsJson == null) {
            throw new IllegalStateException("自定义SQL快照不完整");
        }
        try {
            List<ReplayDbCompareSqlColumn> columns = JSON.readValue(
                    resultColumnsJson, new TypeReference<List<ReplayDbCompareSqlColumn>>() { });
            return new ReplayDbCompareCustomSql(sql, sourceTableName, columns);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("自定义SQL结果字段快照损坏", exception);
        }
    }
}
