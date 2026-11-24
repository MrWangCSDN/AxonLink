package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreReasonImportRow;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 解析无条件忽略原因导入文件；文件第一行即数据，不读取表头。 */
@Service
public class ReplayUnconditionalIgnoreReasonExcelParser {

    public List<ReplayUnconditionalIgnoreReasonImportRow> parse(MultipartFile file) throws IOException {
        return parse(file, 3).stream().map(row -> new ReplayUnconditionalIgnoreReasonImportRow(
                row.rowNumber(), row.cells().get(0), row.cells().get(1), row.cells().get(2),
                row.validColumnCount())).toList();
    }

    List<ExcelRow> parse(MultipartFile file, int columns) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 Excel 文件");
        }
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 没有可用 Sheet");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<ExcelRow> rows = new ArrayList<>();
            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row, formatter, evaluator)) {
                    continue;
                }
                List<String> cells = new ArrayList<>(columns);
                for (int column = 0; column < columns; column++) {
                    cells.add(value(row, column, formatter, evaluator));
                }
                boolean validColumnCount = hasNoNonBlankCellAfter(row, columns - 1, formatter, evaluator)
                        && row.getLastCellNum() >= columns;
                rows.add(new ExcelRow(index + 1, List.copyOf(cells), validColumnCount));
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Excel 中没有可导入数据");
            }
            return List.copyOf(rows);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel 文件无法读取，请检查文件是否损坏或加密", exception);
        }
    }

    record ExcelRow(int rowNumber, List<String> cells, boolean validColumnCount) {}

    private boolean isBlank(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int index = 0; index < row.getLastCellNum(); index++) {
            if (!value(row, index, formatter, evaluator).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasNoNonBlankCellAfter(Row row, int lastExpectedColumn,
                                           DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int index = lastExpectedColumn + 1; index < row.getLastCellNum(); index++) {
            if (!value(row, index, formatter, evaluator).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String value(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row.getCell(column) == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(column), evaluator).trim();
    }
}
