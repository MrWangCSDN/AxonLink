package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryColumn;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReplayReportMailHtmlRendererTest {

    @Test
    void escapesPlainTextAndRendersQueryBeforeAccountingWithTotals() {
        var renderer = new ReplayReportMailHtmlRenderer();

        String html = renderer.render("第一行<script>alert(1)</script>\n第二行",
                List.of(view("DZ", "账务日报"), view("RPT", "查询日报")));

        assertTrue(html.contains("第一行&lt;script&gt;alert(1)&lt;/script&gt;<br>第二行"));
        assertTrue(html.indexOf("查询日报") < html.indexOf("账务日报"));
        assertTrue(html.contains("<table"));
        assertTrue(html.contains("87.50%"));
        assertTrue(html.contains("合计"));
    }

    @Test
    void collapsesBlankLinesAndMatchesExcelGroupedHeaderPalette() {
        var renderer = new ReplayReportMailHtmlRenderer();

        String html = renderer.render("第一行\r\n\r\n第二行\n\n\n第三行", List.of(groupedView()));

        assertTrue(html.contains("第一行<br>第二行<br>第三行"));
        assertFalse(html.contains("<br><br>"));
        assertTrue(html.contains("colspan=\"2\""));
        assertTrue(html.contains("rowspan=\"2\""));
        assertTrue(html.contains("background:#F4CCCC"));
        assertTrue(html.contains("background:#FFF2CC"));
        assertTrue(html.contains("background:#FFF9E6"));
        assertTrue(html.contains("background:#E2F0D9"));
        int previousUnresolvedHeader = html.indexOf(">上一批次未解决问题数量</th>");
        assertTrue(previousUnresolvedHeader > 0);
        int previousUnresolvedCell = html.lastIndexOf("<th", previousUnresolvedHeader);
        String previousUnresolvedMarkup = html.substring(previousUnresolvedCell, previousUnresolvedHeader);
        assertTrue(previousUnresolvedMarkup.contains("background:#FFF2CC"), previousUnresolvedMarkup);
        assertFalse(html.contains("交易核对分类统计 / 528成功/CCBS失败"));
    }

    private static ReplayReportSummaryView view(String family, String name) {
        List<ReplayReportSummaryColumn> columns = List.of(
                new ReplayReportSummaryColumn("domain", "领域", null,
                        ReplayReportSummaryColumn.ValueType.TEXT, 0),
                new ReplayReportSummaryColumn("rate", "通过率", null,
                        ReplayReportSummaryColumn.ValueType.PERCENT, 1));
        LinkedHashMap<String, Object> detailValues = new LinkedHashMap<>();
        detailValues.put("domain", "公共组");
        detailValues.put("rate", new BigDecimal("0.875"));
        LinkedHashMap<String, Object> totalValues = new LinkedHashMap<>();
        totalValues.put("domain", "合计");
        totalValues.put("rate", new BigDecimal("0.875"));
        return new ReplayReportSummaryView(1, ReplayReportPeriod.DAILY, family, null,
                family + "20260916-01", name, columns,
                List.of(new ReplayReportSummaryRow("公共组", "DETAIL", detailValues)),
                new ReplayReportSummaryRow("合计", "TOTAL", totalValues));
    }

    private static ReplayReportSummaryView groupedView() {
        List<ReplayReportSummaryColumn> columns = List.of(
                new ReplayReportSummaryColumn("domain", "领域", null,
                        ReplayReportSummaryColumn.ValueType.TEXT, 0),
                new ReplayReportSummaryColumn("success", "528成功/CCBS失败", "交易核对分类统计",
                        ReplayReportSummaryColumn.ValueType.INTEGER, 1),
                new ReplayReportSummaryColumn("failure", "528失败/CCBS成功", "交易核对分类统计",
                        ReplayReportSummaryColumn.ValueType.INTEGER, 2),
                new ReplayReportSummaryColumn("previousUnresolvedTotal", "上一批次未解决问题数量", null,
                        ReplayReportSummaryColumn.ValueType.INTEGER, 14),
                new ReplayReportSummaryColumn("unanalyzed", "未分析", "上一批次未解决问题分类统计",
                        ReplayReportSummaryColumn.ValueType.INTEGER, 16));
        LinkedHashMap<String, Object> detailValues = new LinkedHashMap<>();
        detailValues.put("domain", "公共组");
        detailValues.put("success", 1);
        detailValues.put("failure", 2);
        detailValues.put("previousUnresolvedTotal", 3);
        detailValues.put("unanalyzed", 3);
        LinkedHashMap<String, Object> totalValues = new LinkedHashMap<>();
        totalValues.put("domain", "合计");
        totalValues.put("success", 1);
        totalValues.put("failure", 2);
        totalValues.put("previousUnresolvedTotal", 3);
        totalValues.put("unanalyzed", 3);
        return new ReplayReportSummaryView(1, ReplayReportPeriod.DAILY, "RPT", null,
                "RPT20260916-01", "查询日报", columns,
                List.of(new ReplayReportSummaryRow("公共组", "DETAIL", detailValues)),
                new ReplayReportSummaryRow("合计", "TOTAL", totalValues));
    }
}
