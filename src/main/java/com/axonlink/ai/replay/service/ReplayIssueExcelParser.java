package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueRow;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the fixed replay-issue workbook layout into rows ready for persistence. */
@Service
public class ReplayIssueExcelParser {

    static final List<String> HEADERS = List.of(
            "领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期", "字段名",
            "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期",
            "需协同组", "解决人员", "流水号", "数据修复日期", "备注", "该问题出现在的交易笔数",
            "issue_id", "issue_key", "历史出现次数", "首次出现日期", "上次出现日期");

    /** Repair dates are lifecycle-managed fields and are optional in imported workbooks. */
    private static final List<String> REQUIRED_HEADERS = HEADERS.stream()
            .filter(header -> !header.equals("数据修复日期"))
            .toList();

    private static final List<SheetMetadata> TARGET_SHEETS = List.of(
            new SheetMetadata("公共组", "公共组", false),
            new SheetMetadata("存款组", "存款组", false),
            new SheetMetadata("贷款组", "贷款组", false),
            new SheetMetadata("结算组", "结算组", false),
            new SheetMetadata("沙箱-公共组", "公共组", true),
            new SheetMetadata("沙箱-存款组", "存款组", true),
            new SheetMetadata("沙箱-贷款组", "贷款组", true),
            new SheetMetadata("沙箱-结算组", "结算组", true));

    public ParsedWorkbook parse(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            validateTargetSheets(workbook);

            List<ReplayIssueRow> rows = new ArrayList<>();
            Map<String, Integer> rowsBySheet = new LinkedHashMap<>();
            int sandboxRows = 0;

            for (SheetMetadata metadata : TARGET_SHEETS) {
                Sheet sheet = workbook.getSheet(metadata.name());
                HeaderMapping headerMapping = findHeaderMapping(sheet, formatter, evaluator);
                int sheetRows = 0;
                for (int rowIndex = headerMapping.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    List<String> values = values(row, headerMapping.columns(), formatter, evaluator);
                    if (values.stream().allMatch(String::isEmpty)) {
                        continue;
                    }
                    rows.add(toReplayIssueRow(metadata, rowIndex, values));
                    sheetRows++;
                }
                rowsBySheet.put(metadata.name(), sheetRows);
                if (metadata.sandbox()) {
                    sandboxRows += sheetRows;
                }
            }

            if (rows.isEmpty()) {
                throw new IllegalArgumentException("目标页签中没有可导入数据");
            }
            return new ParsedWorkbook(rows, rowsBySheet, sandboxRows, rows.size() - sandboxRows);
        }
    }

    private void validateTargetSheets(Workbook workbook) {
        List<String> missing = TARGET_SHEETS.stream()
                .map(SheetMetadata::name)
                .filter(name -> workbook.getSheet(name) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("缺少目标页签：" + String.join("、", missing));
        }
    }

    private HeaderMapping findHeaderMapping(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int rowIndex = 0; rowIndex <= Math.min(19, sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Map<String, Integer> columns = headerColumns(row, formatter, evaluator);
            if (columns.containsKey("领域") && columns.containsKey("issue_key")) {
                rejectDuplicateHeaders(row, formatter, evaluator, sheet.getSheetName());
                validateRequiredHeaders(sheet.getSheetName(), columns);
                return new HeaderMapping(rowIndex, columns);
            }
        }
        throw new IllegalArgumentException("页签“" + sheet.getSheetName() + "”未找到包含领域和issue_key的表头");
    }

    private Map<String, Integer> headerColumns(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : row) {
            String header = normalizeHeader(formatter.formatCellValue(cell, evaluator).trim());
            if (header.isEmpty()) {
                continue;
            }
            columns.putIfAbsent(header, cell.getColumnIndex());
        }
        return columns;
    }

    private void rejectDuplicateHeaders(Row row, DataFormatter formatter, FormulaEvaluator evaluator, String sheetName) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : row) {
            String header = normalizeHeader(formatter.formatCellValue(cell, evaluator).trim());
            if (!header.isEmpty() && columns.putIfAbsent(header, cell.getColumnIndex()) != null) {
                throw new IllegalArgumentException("页签“" + sheetName + "”存在重复表头：" + header);
            }
        }
    }

    private void validateRequiredHeaders(String sheetName, Map<String, Integer> columns) {
        for (String header : REQUIRED_HEADERS) {
            if (!columns.containsKey(normalizeHeader(header))) {
                throw new IllegalArgumentException("页签“" + sheetName + "”缺少表头：" + header);
            }
        }
    }

    private List<String> values(Row row, Map<String, Integer> columns, DataFormatter formatter,
                                FormulaEvaluator evaluator) {
        List<String> values = new ArrayList<>(HEADERS.size());
        for (String header : HEADERS) {
            Integer column = columns.get(normalizeHeader(header));
            Cell cell = row == null || column == null ? null : row.getCell(column);
            values.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator));
        }
        return values;
    }

    private ReplayIssueRow toReplayIssueRow(SheetMetadata metadata, int rowIndex, List<String> values) {
        return new ReplayIssueRow(null, metadata.name(), metadata.groupName(), metadata.sandbox(), rowIndex,
                metadata.groupName(), values.get(1), values.get(2), values.get(3), values.get(4), values.get(5),
                values.get(6), values.get(7), values.get(8), values.get(9), values.get(10), values.get(11),
                values.get(12), values.get(13), values.get(14), values.get(15), values.get(16), "",
                values.get(18), values.get(19), values.get(20), values.get(21), values.get(22), values.get(23),
                values.get(24), null);
    }

    private String normalizeHeader(String header) {
        String asciiLowerCase = asciiLowerCase(header);
        if (asciiLowerCase.equals("issue_id")) {
            return "issue_id";
        }
        if (asciiLowerCase.equals("issue_key")) {
            return "issue_key";
        }
        return header;
    }

    private String asciiLowerCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append(character >= 'A' && character <= 'Z'
                    ? (char) (character + ('a' - 'A'))
                    : character);
        }
        return result.toString();
    }

    private record SheetMetadata(String name, String groupName, boolean sandbox) {
    }

    private record HeaderMapping(int rowIndex, Map<String, Integer> columns) {
    }

    public record ParsedWorkbook(List<ReplayIssueRow> rows, Map<String, Integer> rowsBySheet,
                                 int sandboxRows, int nonSandboxRows) {
        public ParsedWorkbook {
            rows = List.copyOf(rows);
            rowsBySheet = Collections.unmodifiableMap(new LinkedHashMap<>(rowsBySheet));
        }
    }
}
