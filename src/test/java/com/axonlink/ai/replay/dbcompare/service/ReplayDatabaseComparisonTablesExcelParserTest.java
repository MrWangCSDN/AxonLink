package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.ValidationError;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseComparisonTablesExcelParserTest {
    private final ReplayDatabaseComparisonTablesExcelParser parser = new ReplayDatabaseComparisonTablesExcelParser();

    @Test void readsTablesSheetHeaderAliasesAndSkipsStyledEmptyRows() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("说明").createRow(0).createCell(0).setCellValue("说明文字");
            var sheet = workbook.createSheet("tables");
            sheet.createRow(0).createCell(0).setCellValue("表清单");
            var header = sheet.createRow(2);
            String[] names = {"表名", "表中文名", "分组", "负责人", "最大hash分区数"};
            for (int i=0; i<names.length; i++) header.createCell(i).setCellValue(names[i]);
            var row = sheet.createRow(3);
            row.createCell(0).setCellValue(" ACCT_MASTER ");
            row.createCell(1).setCellValue("账户");
            row.createCell(2).setCellValue("存款组");
            row.createCell(3).setCellValue("负责人");
            row.createCell(4).setCellValue(100);
            sheet.createRow(499).createCell(4).setCellStyle(workbook.createCellStyle());

            var tables = parser.parse(input(workbook));
            assertEquals(1, tables.size());
            assertEquals(4, tables.get(0).row());
            assertEquals("acct_master", tables.get(0).table().tableName());
            assertEquals("账户", tables.get(0).table().tableComment());
            assertEquals(100, tables.get(0).table().maxPartitionNum().intValueExact());
        }
    }

    @Test void supportsXlsEnglishHeadersAndMissingOptionalColumns() throws Exception {
        try (var workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("table_name");
            sheet.getRow(0).createCell(1).setCellValue("group");
            sheet.createRow(1).createCell(0).setCellValue("acct_master");
            sheet.getRow(1).createCell(1).setCellValue("存款组");
            var table = parser.parse(input(workbook)).get(0).table();
            assertEquals(64, table.maxPartitionNum().intValueExact());
            assertEquals("", table.ownerName());
            assertEquals("", table.tableComment());
        }
    }

    @Test void acceptsWholeLimitsIncludingNonPowersOfTwoAndRejectsFractionalOrOutOfRangeValues() throws Exception {
        for (String valid : List.of("1", "64", "100", "256", "64.0")) {
            try (var workbook = oneTable(valid)) {
                assertEquals(new java.math.BigDecimal(valid).intValueExact(),
                        parser.parse(input(workbook)).get(0).table().maxPartitionNum().intValueExact());
            }
        }
        for (String invalid : List.of("0", "257", "64.5", "bad")) {
            try (var workbook = oneTable(invalid)) {
                var error = assertThrows(ReplayDatabaseComparisonGenerationException.class,
                        () -> parser.parse(input(workbook)));
                assertEquals(422, error.status().value());
                var detail = errors(error).get(0);
                assertEquals(2, detail.row());
                assertEquals("acct_master", detail.tableName());
                assertEquals("最大hash分区数", detail.fieldName());
            }
        }
    }

    @Test void rejectsDuplicateTablesCaseInsensitivelyAndReportsTheirExcelRow() throws Exception {
        try (var workbook = oneTable("64")) {
            var row = workbook.getSheetAt(0).createRow(4);
            row.createCell(0).setCellValue("ACCT_MASTER");
            row.createCell(1).setCellValue("存款组");
            var error = assertThrows(ReplayDatabaseComparisonGenerationException.class,
                    () -> parser.parse(input(workbook)));
            assertTrue(errors(error).stream().anyMatch(item -> item.row()==5 && item.reason().contains("重复")));
        }
    }

    @Test void numericLimitsAreValidatedBeforeExcelDisplayFormattingCanRoundThem() throws Exception {
        for (double value : new double[]{64.5,256.4}) {
            try (var workbook = oneTable("64")) {
                var cell = workbook.getSheetAt(0).getRow(1).getCell(2);
                cell.setCellValue(value);
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.createDataFormat().getFormat("0"));
                cell.setCellStyle(style);
                var error = assertThrows(ReplayDatabaseComparisonGenerationException.class,
                        () -> parser.parse(input(workbook)));
                assertEquals(422,error.status().value());
                assertTrue(errors(error).stream().anyMatch(item -> item.row()==2
                        && "最大hash分区数".equals(item.fieldName())));
            }
        }
    }

    @Test void rejectsMissingHeadersInvalidDomainsAndEmptyData() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("tables").createRow(0).createCell(0).setCellValue("错误表头");
            assertThrows(ReplayDatabaseComparisonGenerationException.class, () -> parser.parse(input(workbook)));
        }
        try (var workbook = oneTable("64")) {
            workbook.getSheetAt(0).getRow(1).getCell(1).setCellValue("未知组");
            assertThrows(ReplayDatabaseComparisonGenerationException.class, () -> parser.parse(input(workbook)));
            workbook.getSheetAt(0).removeRow(workbook.getSheetAt(0).getRow(1));
            assertThrows(ReplayDatabaseComparisonGenerationException.class, () -> parser.parse(input(workbook)));
        }
    }

    @Test void rejectsMoreThanFiveThousandDataRows() throws Exception {
        try (var workbook = oneTable("64")) {
            var sheet = workbook.getSheetAt(0);
            for (int i=2; i<=5001; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue("table_"+i);
                row.createCell(1).setCellValue("存款组");
            }
            var error = assertThrows(ReplayDatabaseComparisonGenerationException.class,
                    () -> parser.parse(input(workbook)));
            assertTrue(errors(error).stream().anyMatch(item -> item.reason().contains("5000")));
        }
    }

    private XSSFWorkbook oneTable(String max) {
        var workbook = new XSSFWorkbook();
        var sheet = workbook.createSheet("tables");
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("表名");
        header.createCell(1).setCellValue("分组");
        header.createCell(2).setCellValue("最大hash分区数");
        var row = sheet.createRow(1);
        row.createCell(0).setCellValue("acct_master");
        row.createCell(1).setCellValue("存款组");
        row.createCell(2).setCellValue(max);
        return workbook;
    }

    private ByteArrayInputStream input(Workbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return new ByteArrayInputStream(output.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private List<ValidationError> errors(ReplayDatabaseComparisonGenerationException exception) {
        return (List<ValidationError>) ((java.util.Map<?,?>) exception.data()).get("errors");
    }
}
