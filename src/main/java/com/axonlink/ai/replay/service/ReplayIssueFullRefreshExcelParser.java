package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the first sheet of the temporary full-update workbook. */
@Service
public class ReplayIssueFullRefreshExcelParser {

    static final List<String> HEADERS = List.of(
            "领域", "批次", "交易码", "交易名称", "问题级别", "登记日期", "字段名", "问题描述",
            "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协同组",
            "协同人", "流水号", "数据修复日期", "备注", "该问题出现过的交易笔数",
            "issue_id", "issue_key", "历史出现次数", "首次出现日期", "上次出现日期", "问题状态", "是否沙箱");

    public ParsedWorkbook parse(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 中没有可导入的页签");
            }
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<ReplayIssueRow> rows = new ArrayList<>();
            Map<String, Integer> rowsBySheet = new LinkedHashMap<>();
            Map<String, SourceLocation> issueKeys = new HashMap<>();
            int generated = 0;
            int sandboxRows = 0;

            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = sheet.getSheetName();
            HeaderMapping mapping = findHeaderMapping(sheet, formatter, evaluator);
            int sheetRows = 0;
            for (int rowIndex = mapping.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row source = sheet.getRow(rowIndex);
                Map<String, String> values = values(source, mapping.columns(), formatter, evaluator);
                if (values.values().stream().allMatch(String::isEmpty)) {
                    continue;
                }
                String issueId = values.get("issue_id").trim();
                String issueKey = values.get("issue_key").trim();
                if (issueId.isEmpty() || issueKey.isEmpty()) {
                    generated++;
                }
                if (!issueKey.isEmpty()) {
                    SourceLocation previous = issueKeys.putIfAbsent(issueKey, new SourceLocation(sheetName, rowIndex));
                    if (previous != null) {
                        throw new IllegalArgumentException("工作簿存在重复 issue_key：" + issueKey + "（"
                                + location(previous.sheetName(), previous.rowIndex()) + "、"
                                + location(sheetName, rowIndex) + "）");
                    }
                }

                ReplayIssueRow row = toRow(sheetName, rowIndex, values, issueId, issueKey);
                rows.add(row);
                sheetRows++;
                if (row.sandbox()) {
                    sandboxRows++;
                }
            }
            if (sheetRows == 0) {
                throw new IllegalArgumentException("页签“" + sheetName + "”中没有可导入数据");
            }
            rowsBySheet.put(sheetName, sheetRows);
            return new ParsedWorkbook(rows, rowsBySheet, generated, sandboxRows, rows.size() - sandboxRows);
        }
    }

    private HeaderMapping findHeaderMapping(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int rowIndex = 0; rowIndex <= Math.min(19, sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Map<String, Integer> columns = headerColumns(row, formatter, evaluator, sheet.getSheetName());
            if (columns.keySet().containsAll(List.of("领域", "问题状态", "是否沙箱"))) {
                for (String header : HEADERS) {
                    if (!columns.containsKey(header)) {
                        throw new IllegalArgumentException("页签“" + sheet.getSheetName() + "”缺少表头：" + header);
                    }
                }
                return new HeaderMapping(rowIndex, columns);
            }
        }
        throw new IllegalArgumentException("页签“" + sheet.getSheetName() + "”未找到完整表头");
    }

    private Map<String, Integer> headerColumns(Row row, DataFormatter formatter, FormulaEvaluator evaluator,
                                               String sheetName) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : row) {
            String header = normalizeHeader(formatter.formatCellValue(cell, evaluator));
            if (!header.isEmpty() && columns.putIfAbsent(header, cell.getColumnIndex()) != null) {
                throw new IllegalArgumentException("页签“" + sheetName + "”存在重复表头：" + header);
            }
        }
        return columns;
    }

    private Map<String, String> values(Row row, Map<String, Integer> columns, DataFormatter formatter,
                                       FormulaEvaluator evaluator) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String header : HEADERS) {
            Integer column = columns.get(header);
            Cell cell = row == null ? null : row.getCell(column);
            values.put(header, cell == null ? "" : formatter.formatCellValue(cell, evaluator));
        }
        return values;
    }

    private ReplayIssueRow toRow(String sheetName, int rowIndex, Map<String, String> values,
                                 String issueId, String issueKey) {
        String domain = values.get("领域").trim();
        ReplayIssueStatus status = values.get("问题状态").isBlank()
                ? ReplayIssueStatus.OPEN
                : ReplayIssueStatus.fromDisplayValue(values.get("问题状态"));
        boolean sandbox = parseSandbox(sheetName, rowIndex, values.get("是否沙箱"));
        return new ReplayIssueRow(null, sheetName, domain, sandbox, rowIndex,
                domain, "", values.get("批次"), values.get("交易码"), values.get("交易名称"),
                values.get("问题级别"), values.get("登记日期"), values.get("字段名"), values.get("问题描述"),
                values.get("交易负责人"), values.get("问题类型"), values.get("初步问题分析"),
                values.get("最终处理方案"), values.get("解决日期"), values.get("需协同组"), "",
                values.get("流水号"), null, values.get("备注"), values.get("该问题出现过的交易笔数"),
                issueId, issueKey, values.get("历史出现次数"), values.get("首次出现日期"),
                values.get("上次出现日期"), null, status, null, null, null,
                values.get("协同人").trim(), null);
    }

    private boolean parseSandbox(String sheetName, int rowIndex, String value) {
        return switch (value.trim()) {
            case "是" -> true;
            case "否" -> false;
            default -> throw new IllegalArgumentException(location(sheetName, rowIndex) + " 是否沙箱只能填写“是”或“否”");
        };
    }

    private String normalizeHeader(String value) {
        String header = value == null ? "" : value.trim();
        if ("issue_id".equalsIgnoreCase(header)) {
            return "issue_id";
        }
        if ("issue_key".equalsIgnoreCase(header)) {
            return "issue_key";
        }
        return header;
    }

    private String location(String sheetName, int rowIndex) {
        return "页签“" + sheetName + "”第 " + (rowIndex + 1) + " 行";
    }

    private record HeaderMapping(int rowIndex, Map<String, Integer> columns) {
    }

    private record SourceLocation(String sheetName, int rowIndex) {
    }

    public record ParsedWorkbook(List<ReplayIssueRow> rows, Map<String, Integer> rowsBySheet,
                                 int generatedIdentityRows, int sandboxRows, int nonSandboxRows) {
        public ParsedWorkbook {
            rows = List.copyOf(rows);
            rowsBySheet = Collections.unmodifiableMap(new LinkedHashMap<>(rowsBySheet));
        }
    }
}
