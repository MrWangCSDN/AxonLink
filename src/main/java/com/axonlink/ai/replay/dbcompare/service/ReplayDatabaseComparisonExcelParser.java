package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportError;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ReplayDatabaseComparisonExcelParser {

    private static final int TABLE_NAME_COLUMN = 2;
    private static final int FIELD_NAME_COLUMN = 4;
    private static final int REVISER_COLUMN = 6;
    private static final Map<String, String> REQUIRED_SHEETS = requiredSheets();

    public ParsedImport parse(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Excel 文件不能为空");
        }
        DataFormatter formatter = new DataFormatter();
        Map<String, MutableTable> tables = new LinkedHashMap<>();
        List<ReplayDbCompareImportError> errors = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            for (Map.Entry<String, String> required : REQUIRED_SHEETS.entrySet()) {
                Sheet sheet = workbook.getSheet(required.getKey());
                if (sheet == null) {
                    errors.add(new ReplayDbCompareImportError(
                            required.getKey(), null, null, null, null,
                            "缺少必要 Sheet：" + required.getKey()));
                    continue;
                }
                parseRows(sheet, required.getValue(), formatter, tables, errors);
            }
        }
        tables.values().forEach(table -> table.addConflicts(errors));
        return new ParsedImport(tables.values().stream().map(MutableTable::toParsed).toList(), errors);
    }

    private void parseRows(
            Sheet sheet,
            String domainName,
            DataFormatter formatter,
            Map<String, MutableTable> tables,
            List<ReplayDbCompareImportError> errors) {
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            String tableName = identifier(value(row, TABLE_NAME_COLUMN, formatter));
            String fieldName = identifier(value(row, FIELD_NAME_COLUMN, formatter));
            String reviserInput = value(row, REVISER_COLUMN, formatter).trim();
            if (tableName.isBlank() && fieldName.isBlank() && reviserInput.isBlank()) {
                continue;
            }
            if (tableName.isBlank()) {
                errors.add(error(sheet.getSheetName(), rowIndex + 1, tableName, fieldName,
                        reviserInput, "表英文名不能为空"));
            }
            if (fieldName.isBlank()) {
                errors.add(error(sheet.getSheetName(), rowIndex + 1, tableName, fieldName,
                        reviserInput, "字段英文名不能为空"));
            }
            if (tableName.isBlank() || fieldName.isBlank()) {
                continue;
            }
            ParsedRow parsedRow = new ParsedRow(
                    sheet.getSheetName(), rowIndex + 1, domainName,
                    tableName, fieldName, reviserInput);
            tables.computeIfAbsent(tableName, MutableTable::new).add(parsedRow);
        }
    }

    private String value(Row row, int column, DataFormatter formatter) {
        return row == null ? "" : formatter.formatCellValue(row.getCell(column));
    }

    private ReplayDbCompareImportError error(
            String sheetName,
            Integer rowNumber,
            String tableName,
            String fieldName,
            String reviserInput,
            String reason) {
        return new ReplayDbCompareImportError(
                sheetName, rowNumber, tableName, fieldName, reviserInput, reason);
    }

    private String identifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> requiredSheets() {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("存款", "存款组");
        sheets.put("贷款", "贷款组");
        sheets.put("公共", "公共组");
        sheets.put("结算", "结算组");
        return Collections.unmodifiableMap(sheets);
    }

    public record ParsedImport(
            List<ParsedTable> tables,
            List<ReplayDbCompareImportError> errors) {

        public ParsedImport {
            tables = List.copyOf(tables);
            errors = List.copyOf(errors);
        }
    }

    public record ParsedRow(
            String sheetName,
            int rowNumber,
            String domainName,
            String tableName,
            String fieldName,
            String reviserInput) {
    }

    public record ParsedTable(
            String sourceSheet,
            String tableName,
            String domainName,
            String reviserInput,
            List<String> fieldNames,
            List<ParsedRow> rows) {

        public ParsedTable {
            fieldNames = List.copyOf(fieldNames);
            rows = List.copyOf(rows);
        }

        public String groupOwnerDisplay() {
            return reviserInput;
        }

        public List<Integer> sourceRows() {
            return rows.stream().map(ParsedRow::rowNumber).toList();
        }
    }

    private final class MutableTable {
        private final String tableName;
        private final List<ParsedRow> rows = new ArrayList<>();
        private final Set<String> fieldNames = new LinkedHashSet<>();

        private MutableTable(String tableName) {
            this.tableName = tableName;
        }

        private void add(ParsedRow row) {
            rows.add(row);
            fieldNames.add(row.fieldName());
        }

        private void addConflicts(List<ReplayDbCompareImportError> errors) {
            if (rows.stream().map(ParsedRow::sheetName).distinct().count() > 1) {
                rows.forEach(row -> errors.add(error(row.sheetName(), row.rowNumber(),
                        row.tableName(), row.fieldName(), row.reviserInput(),
                        "同一表出现在不同 Sheet")));
            }
            if (rows.stream().map(ParsedRow::reviserInput).filter(value -> !value.isBlank()).distinct().count() > 1) {
                rows.forEach(row -> errors.add(error(row.sheetName(), row.rowNumber(),
                        row.tableName(), row.fieldName(), row.reviserInput(),
                        "同一表的负责人不一致")));
            }
        }

        private ParsedTable toParsed() {
            ParsedRow first = rows.get(0);
            String reviserInput = rows.stream().map(ParsedRow::reviserInput)
                    .filter(value -> !value.isBlank()).findFirst().orElse("");
            return new ParsedTable(first.sheetName(), tableName, first.domainName(),
                    reviserInput, List.copyOf(fieldNames), List.copyOf(rows));
        }
    }
}
