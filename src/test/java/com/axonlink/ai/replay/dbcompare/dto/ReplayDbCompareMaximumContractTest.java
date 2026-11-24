package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReplayDbCompareMaximumContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void rejectsCoercibleAndOutOfRangeMaximumsAcrossEverySaveBoundary() {
        for (Class<?> type : List.of(ReplayDbCompareSaveRequest.class, ReplayDbCompareReregisterRequest.class,
                ReplayDbCompareCustomSqlSaveRequest.class)) {
            for (String value : List.of("0", "257", "-1", "64.5", "64.0", "1e2", "\"64\"", "true", "9223372036854775808")) {
                assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                        () -> json.readValue("{\"maxPartitionNum\":" + value + "}", type), type.getSimpleName() + ": " + value);
            }
        }
    }

    @Test
    void rejectsCoercibleAndOutOfRangeMaximumFiltersAtEveryQueryBoundary() {
        for (Class<?> type : List.of(ReplayDbCompareQuery.class, ReplayDbCompareVersionQuery.class,
                ReplayDbCompareHeaderFilterRequest.class)) {
            for (String value : List.of("0", "257", "64.5", "64.0", "\"64\"", "true", "null", "999999999999999999999999999")) {
                assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                        () -> json.readValue("{\"maxPartitionNums\":[" + value + "]}", type), type.getSimpleName() + ": " + value);
            }
        }
    }

    @Test
    void acceptsAllIntegerMaximumsIncludingNonPowerOfTwoAndMissingOrNull() throws Exception {
        for (Class<?> type : List.of(ReplayDbCompareSaveRequest.class, ReplayDbCompareReregisterRequest.class,
                ReplayDbCompareCustomSqlSaveRequest.class)) {
            for (int value : List.of(1, 64, 100, 256)) {
                var parsed = json.readValue("{\"maxPartitionNum\":" + value + "}", type);
                assertEquals(value, json.valueToTree(parsed).path("maxPartitionNum").asInt());
            }
            assertDoesNotThrow(() -> json.readValue("{}", type));
            assertDoesNotThrow(() -> json.readValue("{\"maxPartitionNum\":null}", type));
        }
    }
}
