package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportSummaryColumn;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class ReplayReportMailHtmlRenderer {

    private static final String CELL_STYLE =
            "border:1px solid #A6A6A6;padding:6px 8px;text-align:center;white-space:nowrap;";
    private static final String UNRESOLVED_GROUP = "上一批次未解决问题分类统计";
    private static final String PINK = "#F4CCCC";
    private static final String YELLOW = "#FFF2CC";
    private static final String PALE_YELLOW = "#FFF9E6";
    private static final String GREEN = "#E2F0D9";
    private static final String WHITE = "#FFFFFF";

    public String render(String plainTextBody, List<ReplayReportSummaryView> summaries) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<div style=\"font-family:Arial,'Microsoft YaHei',sans-serif;color:#1f2937;\">")
                .append("<div style=\"line-height:1.7;margin-bottom:20px;\">")
                .append(escape(normalizeBody(plainTextBody)).replace("\n", "<br>"))
                .append("</div>");
        List<ReplayReportSummaryView> ordered = new ArrayList<>(summaries == null ? List.of() : summaries);
        ordered.sort(Comparator.comparingInt((ReplayReportSummaryView view) ->
                        "RPT".equals(view.family()) ? 0 : 1)
                .thenComparing(ReplayReportSummaryView::period)
                .thenComparing(ReplayReportSummaryView::endBatchNo,
                        Comparator.nullsFirst(String::compareTo))
                .thenComparing(ReplayReportSummaryView::startBatchNo,
                        Comparator.nullsFirst(String::compareTo)));
        String previousFamily = null;
        for (ReplayReportSummaryView summary : ordered) {
            if (previousFamily != null && !previousFamily.equals(summary.family())) {
                html.append("<div style=\"height:24px\"></div>");
            }
            renderSummary(html, summary);
            previousFamily = summary.family();
        }
        return html.append("</div>").toString();
    }

    private static void renderSummary(StringBuilder html, ReplayReportSummaryView summary) {
        html.append("<div style=\"margin:0 0 20px 0;overflow-x:auto;\">")
                .append("<div style=\"font-size:16px;font-weight:700;margin:0 0 8px 0;\">")
                .append(escape(summary.reportName())).append("</div>")
                .append("<table style=\"border-collapse:collapse;font-size:12px;min-width:100%;\"><thead><tr>");
        List<ReplayReportSummaryColumn> columns = summary.columns();
        for (int index = 0; index < columns.size();) {
            ReplayReportSummaryColumn column = columns.get(index);
            if (column.groupLabel() == null) {
                appendHeader(html, column.label(), headerColor(column), " rowspan=\"2\"");
                index++;
                continue;
            }
            int groupEnd = index + 1;
            while (groupEnd < columns.size()
                    && column.groupLabel().equals(columns.get(groupEnd).groupLabel())) {
                groupEnd++;
            }
            appendHeader(html, column.groupLabel(), headerColor(column),
                    " colspan=\"" + (groupEnd - index) + "\"");
            index = groupEnd;
        }
        html.append("</tr><tr>");
        for (ReplayReportSummaryColumn column : columns) {
            if (column.groupLabel() != null) {
                appendHeader(html, column.label(), headerColor(column), "");
            }
        }
        html.append("</tr></thead><tbody>");
        for (ReplayReportSummaryRow row : summary.rows()) renderRow(html, columns, row, false);
        renderRow(html, columns, summary.totalRow(), true);
        html.append("</tbody></table></div>");
    }

    private static void appendHeader(StringBuilder html, String label, String color, String span) {
        html.append("<th").append(span).append(" style=\"background:").append(color).append(";")
                .append(CELL_STYLE).append("font-weight:700;\">")
                .append(escape(label)).append("</th>");
    }

    private static void renderRow(StringBuilder html, List<ReplayReportSummaryColumn> columns,
                                  ReplayReportSummaryRow row, boolean total) {
        html.append("<tr").append(total ? " style=\"font-weight:700;background:#f8fafc;\"" : "")
                .append(">");
        Map<String, Object> values = row.values();
        for (ReplayReportSummaryColumn column : columns) {
            html.append("<td style=\"background:").append(cellColor(column, total)).append(";")
                    .append(CELL_STYLE).append("\">")
                    .append(escape(format(values.get(column.key()), column.valueType())))
                    .append("</td>");
        }
        html.append("</tr>");
    }

    private static String headerColor(ReplayReportSummaryColumn column) {
        return isClassificationColumn(column) ? YELLOW : PINK;
    }

    private static String cellColor(ReplayReportSummaryColumn column, boolean total) {
        if (isClassificationColumn(column)) {
            return total ? YELLOW : PALE_YELLOW;
        }
        return total ? GREEN : WHITE;
    }

    private static boolean isClassificationColumn(ReplayReportSummaryColumn column) {
        return column.order() >= 14 || UNRESOLVED_GROUP.equals(column.groupLabel());
    }

    private static String normalizeBody(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n").replace("\r", "\n")
                .replaceAll("\\n(?:\\h*\\n)+", "\n");
    }

    private static String format(Object value, ReplayReportSummaryColumn.ValueType type) {
        if (value == null) return "";
        if (type == ReplayReportSummaryColumn.ValueType.PERCENT) {
            BigDecimal decimal = value instanceof BigDecimal number
                    ? number : new BigDecimal(value.toString());
            return decimal.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
        }
        if (type == ReplayReportSummaryColumn.ValueType.INTEGER && value instanceof Number number) {
            return Long.toString(number.longValue());
        }
        return value.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
