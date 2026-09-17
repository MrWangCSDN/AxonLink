package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayDatabaseComparisonConditionCodecTest {

    private final ReplayDatabaseComparisonConditionCodec codec =
            new ReplayDatabaseComparisonConditionCodec();

    @Test
    void encodesCanonicalLowerCaseColumnNamesAndRoundTripsOrder() {
        ReplayDbCompareConditionTree tree = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.OR,
                        List.of(
                                new ReplayDbCompareCondition(" STATUS ", ReplayDbCompareConditionOperator.EQ,
                                        List.of("1")),
                                new ReplayDbCompareCondition("Status", ReplayDbCompareConditionOperator.IN,
                                        List.of("2", "3"))))));

        String json = codec.encode(tree);

        assertThat(json).isEqualTo("{\"connector\":\"AND\",\"groups\":[{\"connector\":\"OR\","
                + "\"conditions\":[{\"columnName\":\"status\",\"operator\":\"EQ\",\"values\":[\"1\"]},"
                + "{\"columnName\":\"status\",\"operator\":\"IN\",\"values\":[\"2\",\"3\"]}]}]}");
        assertThat(codec.decode(json)).isEqualTo(codec.normalize(tree));
    }

    @Test
    void normalizesEmptyTreesAndBlankStorageToNull() {
        ReplayDbCompareConditionTree empty = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND, List.of());

        assertThat(codec.normalize(empty)).isNull();
        assertThat(codec.encode(empty)).isNull();
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("  ")).isNull();
    }
}
