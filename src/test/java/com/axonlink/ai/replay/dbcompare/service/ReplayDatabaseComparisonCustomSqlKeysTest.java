package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseKeyOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayDatabaseComparisonCustomSqlKeysTest {
    @Test
    void exposesACompletePrimaryKeyFromTheResultFieldNames() {
        var primary = key("pk_account", "PRIMARY_KEY", "tenant_id", "id");

        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("amount", "id", "tenant_id"),
                List.of(primary))).containsExactly(primary);
    }

    @Test
    void excludesAnIncompleteCompositeKeyAndKeepsAnAvailableUniqueAlternative() {
        var primary = key("pk_account", "PRIMARY_KEY", "tenant_id", "id");
        var unique = key("uk_reference", "UNIQUE_INDEX", "reference_no");

        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("id", "reference_no", "amount"),
                List.of(primary, unique))).containsExactly(unique);
    }

    @Test
    void preservesSourceIndexOrderAndSeparateChoicesForIndexesWithTheSameColumns() {
        var primary = key("pk_account", "PRIMARY_KEY", "id");
        var uniqueWithSameColumns = key("uk_account_id", "UNIQUE_INDEX", "id");
        var anotherUnique = key("uk_reference", "UNIQUE_INDEX", "reference_no");

        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("reference_no", "id"),
                List.of(primary, uniqueWithSameColumns, anotherUnique)))
                .containsExactly(primary, uniqueWithSameColumns, anotherUnique);
    }

    @Test
    void hasNoCandidateWhenThereIsNoIndexOrNoCompleteResultKey() {
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("id"), List.of())).isEmpty();
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("amount"),
                List.of(key("pk_account", "PRIMARY_KEY", "id")))).isEmpty();
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(List.of(),
                List.of(key("pk_account", "PRIMARY_KEY", "id")))).isEmpty();
    }

    @Test
    void normalizesResultAndSourceKeyNamesWhileRetainingIndexIdentityAndColumnOrder() {
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("ID", "Tenant_ID"),
                List.of(key("PK_Account", "PRIMARY_KEY", "TENANT_ID", "id"))))
                .containsExactly(key("PK_Account", "PRIMARY_KEY", "tenant_id", "id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "id ", "bad-name", "a.id", "*", "0id", "字段"})
    void rejectsAKeyGroupWithAnInvalidColumnName(String invalid) {
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("id", invalid),
                List.of(key("bad_index", "UNIQUE_INDEX", invalid)))).isEmpty();
    }

    @Test
    void rejectsEmptyOrRepeatedKeyColumnsAfterCanonicalizationWithoutDroppingOtherCandidates() {
        var primary = key("pk_account", "PRIMARY_KEY", "id");
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output("id"),
                List.of(key("empty_index", "UNIQUE_INDEX"),
                        key("duplicate_index", "UNIQUE_INDEX", "id", "ID"), primary)))
                .containsExactly(primary);
    }

    @Test
    void rejectsNamesLongerThanTheCanonicalResultFieldLimit() {
        String invalid = "a".repeat(101);
        var invalidKey = key("uk_too_long", "UNIQUE_INDEX", invalid);
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(output(invalid), List.of(invalidKey)))
                .isEmpty();
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(List.of(invalidKey), List.of(invalid)))
                .isFalse();
    }

    @Test
    void acceptsAnExactlyCompleteCompositeKeyInAnySelectionOrder() {
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(
                List.of(key("pk_account", "PRIMARY_KEY", "tenant_id", "id")),
                List.of("ID", "TENANT_ID"))).isTrue();
    }

    @Test
    void acceptsAnyAvailableUniqueIndexAsTheSelectedKey() {
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(
                List.of(key("pk_account", "PRIMARY_KEY", "id"),
                        key("uk_reference", "UNIQUE_INDEX", "reference_no")),
                List.of("reference_no"))).isTrue();
    }

    @Test
    void rejectsPartialExtraDuplicateOrEmptySelectedKeys() {
        var available = List.of(key("pk_account", "PRIMARY_KEY", "tenant_id", "id"));

        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(available, List.of("id"))).isFalse();
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(available,
                List.of("tenant_id", "id", "amount"))).isFalse();
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(available,
                List.of("tenant_id", "id", "ID"))).isFalse();
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(available, List.of())).isFalse();
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(List.of(), List.of("id"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "id ", "bad-name", "a.id", "0id"})
    void rejectsInvalidSelectedFieldNames(String invalid) {
        assertThat(ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(
                List.of(key("uk_invalid", "UNIQUE_INDEX", invalid)), List.of(invalid))).isFalse();
    }

    private ReplayBaseKeyOption key(String name, String type, String... columns) {
        return new ReplayBaseKeyOption(name, type, List.of(columns));
    }

    private List<ReplayDbCompareSqlColumn> output(String... names) {
        return IntStream.range(0, names.length).mapToObj(index ->
                new ReplayDbCompareSqlColumn(names[index], names[index], "varchar", index + 1)).toList();
    }
}
