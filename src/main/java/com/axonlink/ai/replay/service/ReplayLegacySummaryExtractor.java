package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryColumn;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ReplayLegacySummaryExtractor {

    private static final String SUMMARY_SHEET = "汇总信息";
    private static final String CURRENT_BATCH_SUFFIX = "（本批次）";
    private final ReplayReportSummaryViewFactory summaryViewFactory;

    public ReplayLegacySummaryExtractor() {
        this(new ReplayReportSummaryViewFactory());
    }

    ReplayLegacySummaryExtractor(ReplayReportSummaryViewFactory summaryViewFactory) {
        this.summaryViewFactory = summaryViewFactory;
    }

    public ReplayReportSummaryView extract(byte[] workbookBytes,
                                           ReplayReportPeriod period,
                                           String startBatchNo,
                                           String endBatchNo) {
        Objects.requireNonNull(period, "period");
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new LegacySummaryUnavailableException("历史报告文件为空");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet(SUMMARY_SHEET);
            if (sheet == null) {
                throw new LegacySummaryUnavailableException("历史报告缺少汇总信息工作表");
            }
            int titleRowIndex = findLowerTitleRow(sheet);
            List<ReplayReportSummaryColumn> columns = summaryViewFactory.columns();
            validateHeaders(sheet, titleRowIndex + 1, columns);
            ParsedRows parsed = readRows(sheet, titleRowIndex + 3, columns);
            String family = endBatchNo != null && endBatchNo.startsWith("DZ") ? "DZ" : "RPT";
            return new ReplayReportSummaryView(1, period, family, startBatchNo, endBatchNo,
                    reportName(period, family, startBatchNo, endBatchNo), columns,
                    parsed.details(), parsed.total());
        } catch (LegacySummaryUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LegacySummaryUnavailableException("历史报告汇总读取失败", exception);
        }
    }

    private static int findLowerTitleRow(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        for (Row row : sheet) {
            String value = formatter.formatCellValue(row.getCell(0));
            if (value.startsWith("批次号：") && value.endsWith(CURRENT_BATCH_SUFFIX)) {
                return row.getRowNum();
            }
        }
        throw new LegacySummaryUnavailableException("历史报告缺少本批次汇总表");
    }

    private static void validateHeaders(Sheet sheet, int parentRowIndex,
                                        List<ReplayReportSummaryColumn> columns) {
        Row parent = sheet.getRow(parentRowIndex);
        Row child = sheet.getRow(parentRowIndex + 1);
        if (parent == null || child == null) {
            throw new LegacySummaryUnavailableException("历史报告汇总表结构不兼容");
        }
        DataFormatter formatter = new DataFormatter();
        List<String> actual = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            String childValue = formatter.formatCellValue(child.getCell(index));
            actual.add(childValue.isBlank()
                    ? formatter.formatCellValue(parent.getCell(index))
                    : childValue);
        }
        List<String> expected = columns.stream().map(ReplayReportSummaryColumn::label).toList();
        if (!expected.equals(actual)) {
            throw new LegacySummaryUnavailableException("历史报告汇总表结构不兼容");
        }
    }

    private static ParsedRows readRows(Sheet sheet, int firstRowIndex,
                                       List<ReplayReportSummaryColumn> columns) {
        List<ReplayReportSummaryRow> details = new ArrayList<>();
        for (int rowIndex = firstRowIndex; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isEmpty(row, columns.size())) {
                continue;
            }
            Map<String, Object> values = readValues(row, columns);
            String domain = Objects.toString(values.get("domain"), "");
            ReplayReportSummaryRow summaryRow = new ReplayReportSummaryRow(
                    domain, "合计".equals(domain) ? "TOTAL" : "DETAIL", values);
            if ("TOTAL".equals(summaryRow.rowType())) {
                return new ParsedRows(details, summaryRow);
            }
            details.add(summaryRow);
        }
        throw new LegacySummaryUnavailableException("历史报告汇总表缺少合计行");
    }

    private static Map<String, Object> readValues(Row row,
                                                   List<ReplayReportSummaryColumn> columns) {
        Map<String, Object> values = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (int index = 0; index < columns.size(); index++) {
            ReplayReportSummaryColumn column = columns.get(index);
            Cell cell = row.getCell(index);
            Object value = switch (column.valueType()) {
                case TEXT -> {
                    String text = formatter.formatCellValue(cell);
                    yield text;
                }
                case INTEGER -> numeric(cell, false);
                case PERCENT -> numeric(cell, true);
            };
            values.put(column.key(), value);
        }
        return values;
    }

    private static Object numeric(Cell cell, boolean decimal) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() != CellType.NUMERIC) {
            throw new LegacySummaryUnavailableException("历史报告汇总表数值格式不兼容");
        }
        return decimal ? BigDecimal.valueOf(cell.getNumericCellValue()) : (long) cell.getNumericCellValue();
    }

    private static boolean isEmpty(Row row, int columnCount) {
        DataFormatter formatter = new DataFormatter();
        for (int index = 0; index < columnCount; index++) {
            if (!formatter.formatCellValue(row.getCell(index)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String reportName(ReplayReportPeriod period, String family,
                                     String startBatchNo, String endBatchNo) {
        try {
            return (period == ReplayReportPeriod.WEEKLY
                    ? ReplayReportFileNames.weekly(startBatchNo, endBatchNo)
                    : ReplayReportFileNames.daily(endBatchNo)).replaceFirst("\\.xlsx$", "");
        } catch (IllegalArgumentException exception) {
            String label = "RPT".equals(family) ? "查询" : "账务";
            return label + (period == ReplayReportPeriod.WEEKLY ? "周报" : "日报") + "-" + endBatchNo;
        }
    }

    private record ParsedRows(List<ReplayReportSummaryRow> details,
                              ReplayReportSummaryRow total) {
    }

    public static class LegacySummaryUnavailableException extends RuntimeException {
        public LegacySummaryUnavailableException(String message) {
            super(message);
        }

        public LegacySummaryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
