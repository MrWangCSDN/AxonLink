package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryColumn;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayReportSummaryCodecTest {

    private final ReplayReportSummaryCodec codec = new ReplayReportSummaryCodec();

    @Test
    void roundTripsSummaryViewWithNumericTypes() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("domain", "公共组");
        values.put("issueTotal", 12L);
        values.put("successRate", new BigDecimal("0.875"));
        ReplayReportSummaryRow row = new ReplayReportSummaryRow("公共组", "DETAIL", values);
        ReplayReportSummaryView view = new ReplayReportSummaryView(
                1, ReplayReportPeriod.DAILY, "RPT", null, "RPT20260916-01", "查询日报-20260916",
                List.of(new ReplayReportSummaryColumn("domain", "领域", null,
                        ReplayReportSummaryColumn.ValueType.TEXT, 0)),
                List.of(row), new ReplayReportSummaryRow("合计", "TOTAL", values));

        ReplayReportSummaryView decoded = codec.decode(codec.encode(view));

        assertEquals(view, decoded);
        assertEquals(Long.class, decoded.rows().get(0).values().get("issueTotal").getClass());
        assertEquals(BigDecimal.class, decoded.rows().get(0).values().get("successRate").getClass());
    }

    @Test
    void rejectsBlankAndUnsupportedSchema() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(" "));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("""
                {"schemaVersion":2,"period":"DAILY","family":"RPT","columns":[],"rows":[]}
                """));
    }
}
