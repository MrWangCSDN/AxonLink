package com.axonlink.ai.replay.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;

@Service
public class ReplayDatabaseTablePersonExcelParser {
    public static final List<String> HEADERS = List.of("领域", "表英文名称", "表中文名称", "开发负责人", "科技负责人");

    public List<RawRow> parse(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("Excel 没有可用 Sheet");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int headerRow = -1;
            Map<String,Integer> columns = new HashMap<>();
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 19); r++) {
                Row row = sheet.getRow(r); if (row == null) continue;
                columns.clear();
                for (Cell cell : row) {
                    String label = formatter.formatCellValue(cell, evaluator).trim();
                    if (HEADERS.contains(label)) columns.put(label, cell.getColumnIndex());
                }
                if (columns.keySet().containsAll(HEADERS)) { headerRow = r; break; }
            }
            if (headerRow < 0) throw new IllegalArgumentException("Excel 首个 Sheet 缺少必要表头：" + String.join("、", HEADERS));
            List<RawRow> result = new ArrayList<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r); if (row == null) continue;
                String[] values = new String[5]; boolean blank = true;
                for (int c = 0; c < 5; c++) {
                    Cell cell = row.getCell(columns.get(HEADERS.get(c)));
                    values[c] = cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
                    blank &= values[c].isBlank();
                }
                if (!blank) result.add(new RawRow(r + 1, values[0], values[1], values[2], values[3], values[4]));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Excel 中没有可导入数据");
            return result;
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new IllegalArgumentException("Excel 文件无法读取，请检查文件是否损坏或加密", e); }
    }

    public record RawRow(int rowNumber, String domain, String tableName, String tableComment,
                         String developer, String technologyOwner) {}
}
