package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlowSqlImportColumnLayoutTest {

    @Test
    void recognizesNewSqlThenServiceHeaderOrder() {
        var layout = SlowSqlImportService.columnLayout("slowSqlStatement", "ServiceName");

        assertEquals(1, layout.serviceIndex());
        assertEquals(0, layout.abstractIndex());
    }

    @ParameterizedTest
    @ValueSource(strings = {"serviceName", "服务名"})
    void recognizesBlankSqlHeaderBeforeNamedServiceColumn(String serviceHeader) {
        var layout = SlowSqlImportService.columnLayout(List.of("", serviceHeader, "slowSqlParameter",
                "slowSqlTimeCost", "slowSqlLocation", "sssCount"));

        assertEquals(1, layout.serviceIndex());
        assertEquals(0, layout.abstractIndex());
        assertEquals(2, layout.paramsIndex());
        assertEquals(5, layout.countIndex());
    }

    @Test
    void keepsLegacyOrderForExistingHeadersAndHeaderlessFiles() {
        var legacy = SlowSqlImportService.columnLayout("ServiceName", "abstractSql");
        var missing = SlowSqlImportService.columnLayout(null, null);

        assertEquals(0, legacy.serviceIndex());
        assertEquals(1, legacy.abstractIndex());
        assertEquals(0, missing.serviceIndex());
        assertEquals(1, missing.abstractIndex());
    }
}
