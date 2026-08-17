package com.axonlink.ai.replay.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class ReplayTransactionPersonExcelParser {
    private static final List<String> HEADERS = List.of("领域", "老交易码", "老交易名称", "开发人员", "行方负责人");

    public ParsedWorkbook parse(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("Excel 没有可用 Sheet");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> headerIndexes = findHeaders(sheet, formatter, evaluator);
            List<RawRow> rows = new ArrayList<>();
            for (int i = headerRow(headerIndexes, sheet, formatter, evaluator) + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String[] values = new String[HEADERS.size()];
                boolean blank = true;
                for (int c = 0; c < HEADERS.size(); c++) {
                    values[c] = value(row.getCell(headerIndexes.get(HEADERS.get(c))), formatter, evaluator);
                    blank &= values[c].isBlank();
                }
                if (!blank) rows.add(new RawRow(i + 1, values[0], values[1], values[2], values[3], values[4]));
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("Excel 中没有可导入数据");
            return new ParsedWorkbook(rows);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Excel 文件无法读取，请检查文件是否损坏或加密", e);
        }
    }

    private Map<String, Integer> findHeaders(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int r = 0; r < Math.min(sheet.getLastRowNum() + 1, 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, Integer> indexes = new LinkedHashMap<>();
            for (Cell cell : row) {
                String text = normalizeHeader(formatter.formatCellValue(cell, evaluator).trim());
                if (HEADERS.contains(text)) indexes.put(text, cell.getColumnIndex());
            }
            if (indexes.keySet().containsAll(HEADERS)) return indexes;
        }
        throw new IllegalArgumentException("Excel 首个 Sheet 缺少必要表头：领域、老交易码、老交易名称、开发人员、行方负责人（兼容“行内负责人”）");
    }

    private int headerRow(Map<String, Integer> indexes, Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int r = 0; r < Math.min(sheet.getLastRowNum() + 1, 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean found = true;
            for (Map.Entry<String, Integer> entry : indexes.entrySet()) {
                if (!entry.getKey().equals(normalizeHeader(formatter.formatCellValue(row.getCell(entry.getValue()), evaluator).trim()))) { found = false; break; }
            }
            if (found) return r;
        }
        throw new IllegalArgumentException("Excel 表头格式错误");
    }

    private String value(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }

    private String normalizeHeader(String value) {
        return "行内负责人".equals(value) ? "行方负责人" : value;
    }

    public record ParsedWorkbook(List<RawRow> rows) {
        public ParsedWorkbook { rows = List.copyOf(rows); }
    }

    public record RawRow(int rowNumber, String domain, String oldTransactionCode, String oldTransactionName,
                         String developer, String bankOwner) {}
}
