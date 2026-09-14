package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayConfigServiceCodeResolverTest {

    private JdbcTemplate jdbc;
    private ReplayConfigServiceCodeResolver resolver;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        resolver = new ReplayConfigServiceCodeResolver(jdbc);
        insertMapping("S120034071.CorpInfo.Qry", "Y444");
        insertMapping("SEQFoo", "Y444");
        insertMapping("OTHER.Bar", "Z999");
    }

    @Test
    void resolvesDotsAndAppendsThreeSuffixesWithDedup() {
        Set<String> codes = resolver.resolveFinalServiceCodes("Y444");

        assertEquals(6, codes.size());
        assertTrue(codes.contains("S120034071CorpInfoQry&sop"));
        assertTrue(codes.contains("S120034071CorpInfoQry&soap"));
        assertTrue(codes.contains("S120034071CorpInfoQry&bzjson"));
        assertTrue(codes.contains("SEQFoo&sop"));
        assertTrue(codes.contains("SEQFoo&soap"));
        assertTrue(codes.contains("SEQFoo&bzjson"));
        assertTrue(!codes.contains("OTHERBar&sop"));
    }

    @Test
    void emptyMappingReturnsEmptySetAndBlankReturnsNull() {
        assertEquals(Set.of(), resolver.resolveFinalServiceCodes("NOPE"));
        assertNull(resolver.resolveFinalServiceCodes("  "));
        assertNull(resolver.resolveFinalServiceCodes(null));
    }

    private void insertMapping(String esfServiceCode, String tranCode) {
        jdbc.update("INSERT INTO znzx_service "
                        + "(application_name,esf_service_code,flow_id,tran_code,function_desc,group_name) "
                        + "VALUES (?,?,?,?,?,?)",
                "app", esfServiceCode, "flow-1", tranCode, "描述", "公共组");
    }
}
