package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.util.Map;

public class ReplayReportMailBodyMetricExtractor {

    private static final String COVERAGE_SHEET = "回放交易覆盖情况";
    private static final String DOMAIN_SECTION = "按业务领域汇总";
    private static final String GROUP_SECTION = "按大组汇总";
    private static final String DETAIL_SECTION = "交易明细";

    public Metrics extract(byte[] workbookContent, ReplayReportSummaryView summary, String reportKey) {
        if (workbookContent == null || workbookContent.length == 0) {
            throw unavailable(reportKey, "报告快照为空");
        }
        CoverageTotal domainTotal;
        CoverageTotal groupTotal;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookContent))) {
            Sheet sheet = workbook.getSheet(COVERAGE_SHEET);
            if (sheet == null) throw unavailable(reportKey, "缺少回放交易覆盖情况");
            domainTotal = readSectionTotal(sheet, DOMAIN_SECTION, GROUP_SECTION, reportKey);
            groupTotal = readSectionTotal(sheet, GROUP_SECTION, DETAIL_SECTION, reportKey);
        } catch (BodyMetricUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BodyMetricUnavailableException(reportKey, "报告快照读取失败", exception);
        }

        if (domainTotal != null && groupTotal != null && !domainTotal.equals(groupTotal)) {
            throw unavailable(reportKey, "覆盖汇总合计不一致");
        }
        CoverageTotal selected = groupTotal != null ? groupTotal : domainTotal;
        if (selected == null) throw unavailable(reportKey, "覆盖汇总合计缺失");
        long collected = collectedTransactions(summary, reportKey);
        return new Metrics(selected.expectedTransactions(), selected.actualTransactions(), collected);
    }

    private CoverageTotal readSectionTotal(Sheet sheet, String title, String nextTitle, String reportKey) {
        int start = findRow(sheet, title);
        if (start < 0) return null;
        int end = findRow(sheet, nextTitle);
        if (end < 0 || end <= start) end = sheet.getLastRowNum() + 1;
        for (int index = start + 1; index < end; index++) {
            Row row = sheet.getRow(index);
            if (row == null || !"合计".equals(text(row.getCell(0)))) continue;
            long expected = nonNegativeLong(row.getCell(1), reportKey, "全量清单交易数合计");
            long actual = nonNegativeLong(row.getCell(2), reportKey, "本次已发送合计");
            return new CoverageTotal(expected, actual);
        }
        return null;
    }

    private static int findRow(Sheet sheet, String value) {
        for (int index = sheet.getFirstRowNum(); index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row != null && value.equals(text(row.getCell(0)))) return index;
        }
        return -1;
    }

    private static long collectedTransactions(ReplayReportSummaryView summary, String reportKey) {
        if (summary == null || summary.totalRow() == null) {
            throw unavailable(reportKey, "发送交易量合计缺失");
        }
        Map<String, Object> values = summary.totalRow().values();
        Object value = values == null ? null : values.get("sentTransactionCount");
        if (value instanceof Number number) {
            long result = number.longValue();
            if (result >= 0) return result;
        }
        throw unavailable(reportKey, "发送交易量合计缺失");
    }

    private static long nonNegativeLong(Cell cell, String reportKey, String label) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            long result = (long) value;
            if (value == result && result >= 0) return result;
        }
        String text = text(cell);
        try {
            long value = Long.parseLong(text);
            if (value >= 0) return value;
        } catch (NumberFormatException ignored) {
        }
        throw unavailable(reportKey, label + "无效");
    }

    private static String text(Cell cell) {
        return cell == null ? "" : new DataFormatter().formatCellValue(cell).trim();
    }

    private static BodyMetricUnavailableException unavailable(String reportKey, String reason) {
        return new BodyMetricUnavailableException(reportKey, reason);
    }

    public record Metrics(long expectedTransactions, long actualTransactions, long collectedTransactions) {
    }

    private record CoverageTotal(long expectedTransactions, long actualTransactions) {
    }

    public static final class BodyMetricUnavailableException extends RuntimeException {
        public BodyMetricUnavailableException(String reportKey, String reason) {
            super("报告正文统计不可用：" + reportKey + "（" + reason + "）");
        }

        public BodyMetricUnavailableException(String reportKey, String reason, Throwable cause) {
            super("报告正文统计不可用：" + reportKey + "（" + reason + "）", cause);
        }
    }
}
