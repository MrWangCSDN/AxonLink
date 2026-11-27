package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplayConfigServiceCodeResolverTest {

    private JdbcTemplate jdbc;
    private ReplayConfigServiceCodeResolver resolver;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        resolver = new ReplayConfigServiceCodeResolver(jdbc);
        ReplayConfigTestFixtures.insertServiceMappings(jdbc, "Y444",
                "S120034071CorpInfoQry&sop", "S120034071CorpInfoQry&sop", "SEQFoo&soap");
        ReplayConfigTestFixtures.insertServiceMappings(jdbc, "Z999", "OTHERBar&sop");
    }

    @Test
    void resolvesStoredServiceCodesWithDedup() {
        Set<String> codes = resolver.resolveFinalServiceCodes("Y444");

        assertEquals(Set.of("S120034071CorpInfoQry&sop", "SEQFoo&soap"), codes);
    }

    @Test
    void emptyMappingReturnsEmptySetAndBlankReturnsNull() {
        assertEquals(Set.of(), resolver.resolveFinalServiceCodes("NOPE"));
        assertNull(resolver.resolveFinalServiceCodes("  "));
        assertNull(resolver.resolveFinalServiceCodes(null));
    }

}
