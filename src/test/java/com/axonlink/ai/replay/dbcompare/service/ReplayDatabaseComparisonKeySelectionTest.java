package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseComparisonKeySelectionTest {
    private final ReplayBaseKeyOption primary = new ReplayBaseKeyOption("orders_pkey", "PRIMARY_KEY", List.of("id"));
    private final ReplayBaseKeyOption unique = new ReplayBaseKeyOption("orders_unique", "UNIQUE_INDEX", List.of("tenant", "ref"));

    @Test void defaultsToPhysicalPrimaryAndOtherwiseStableUniqueName() {
        assertEquals("orders_pkey", ReplayDatabaseComparisonKeys.choose("orders", List.of(unique, primary), null, null).indexName());
        assertEquals("a_unique", ReplayDatabaseComparisonKeys.choose("orders", List.of(unique,
                new ReplayBaseKeyOption("a_unique", "UNIQUE_INDEX", List.of("ref"))), null, null).indexName());
        assertThrows(ReplayBasePrimaryKeyMissingException.class, () -> ReplayDatabaseComparisonKeys.choose("orders", List.of(), null, null));
    }
    @Test void keepsSelectionAndRejectsRemovedOrReorderedIndexWithoutFallback() {
        var selected = ReplayDatabaseComparisonKeys.choose("orders", List.of(primary, unique), "orders_unique", null);
        assertEquals(unique.columnNames(), selected.columnNames());
        assertEquals(selected, ReplayDatabaseComparisonKeys.choose("orders", List.of(primary, unique), null, selected));
        assertThrows(IllegalArgumentException.class, () -> ReplayDatabaseComparisonKeys.choose("orders", List.of(primary), null, selected));
        assertThrows(IllegalArgumentException.class, () -> ReplayDatabaseComparisonKeys.choose("orders", List.of(primary,
                new ReplayBaseKeyOption("orders_unique", "UNIQUE_INDEX", List.of("ref", "tenant"))), null, selected));
        assertEquals("orders_pkey", ReplayDatabaseComparisonKeys.choose("orders", List.of(primary), "orders_pkey", selected).indexName());
    }
    @Test void requiresEntireSelectedCompositeKeyWithoutMixingIndexes() {
        var selected = ReplayDatabaseComparisonKeys.choose("orders", List.of(primary, unique), "orders_unique", null);
        assertThrows(IllegalArgumentException.class, () -> ReplayDatabaseComparisonKeys.requireFields("orders", selected, List.of("id", "tenant")));
        assertDoesNotThrow(() -> ReplayDatabaseComparisonKeys.requireFields("orders", selected, List.of("tenant", "ref")));
    }
}
