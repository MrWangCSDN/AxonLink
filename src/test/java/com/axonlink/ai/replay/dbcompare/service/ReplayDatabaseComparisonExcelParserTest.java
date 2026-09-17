package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonExcelParserTest {

    private final ReplayDatabaseComparisonExcelParser parser = new ReplayDatabaseComparisonExcelParser();

    @Test
    void mapsFourRequiredSheetsAndReadsOnlyFixedColumns() throws Exception {
        XSSFWorkbook workbook = requiredWorkbook();
        fixedRow(workbook.getSheet("存款"), 1, "ACCT_MASTER", "ACCT_NO", "张三（c-zhangs）");
        fixedRow(workbook.getSheet("存款"), 2, "acct_master", "CUSTOMER_NO", "张三（c-zhangs）");
        fixedRow(workbook.getSheet("存款"), 3, "acct_master", "ACCT_NO", "张三（c-zhangs）");
        fixedRow(workbook.getSheet("贷款"), 1, "LOAN_MASTER", "LOAN_NO", "");
        workbook.createSheet("说明").createRow(0).createCell(0).setCellValue("忽略额外 Sheet");

        ReplayDatabaseComparisonExcelParser.ParsedImport result = parse(workbook);

        assertTrue(result.errors().isEmpty());
        assertEquals(2, result.tables().size());
        ReplayDatabaseComparisonExcelParser.ParsedTable deposit = result.tables().get(0);
        assertEquals("存款", deposit.sourceSheet());
        assertEquals("存款组", deposit.domainName());
        assertEquals("acct_master", deposit.tableName());
        assertEquals("张三（c-zhangs）", deposit.reviserInput());
        assertEquals(List.of("acct_no", "customer_no"), deposit.fieldNames());
        assertEquals(List.of(2, 3, 4), deposit.rows().stream().map(
                ReplayDatabaseComparisonExcelParser.ParsedRow::rowNumber).toList());
        ReplayDatabaseComparisonExcelParser.ParsedTable loan = result.tables().get(1);
        assertEquals("贷款组", loan.domainName());
        assertEquals("", loan.reviserInput());
    }

    @Test
    void requiresAllFourSheetsEvenWhenTheyMayContainNoRows() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        createSheet(workbook, "存款");
        createSheet(workbook, "贷款");
        createSheet(workbook, "公共");

        ReplayDatabaseComparisonExcelParser.ParsedImport result = parse(workbook);

        assertEquals(1, result.errors().size());
        ReplayDbCompareImportError error = result.errors().get(0);
        assertEquals("结算", error.sheetName());
        assertNull(error.rowNumber());
        assertEquals("缺少必要 Sheet：结算", error.reason());
    }

    @Test
    void collectsBlankFixedColumnsAndKeepsOriginalReviserInput() throws Exception {
        XSSFWorkbook workbook = requiredWorkbook();
        fixedRow(workbook.getSheet("公共"), 1, "", "customer_no", "不存在的人");
        fixedRow(workbook.getSheet("公共"), 2, "customer_master", "", "张三");

        ReplayDatabaseComparisonExcelParser.ParsedImport result = parse(workbook);

        assertEquals(2, result.errors().size());
        assertEquals(List.of(2, 3), result.errors().stream().map(ReplayDbCompareImportError::rowNumber).toList());
        assertEquals(List.of("不存在的人", "张三"), result.errors().stream()
                .map(ReplayDbCompareImportError::reviserInput).toList());
        assertTrue(result.errors().stream().anyMatch(error -> error.reason().equals("表英文名不能为空")));
        assertTrue(result.errors().stream().anyMatch(error -> error.reason().equals("字段英文名不能为空")));
    }

    @Test
    void reportsEveryRowOnlyForCrossSheetConflicts() throws Exception {
        XSSFWorkbook workbook = requiredWorkbook();
        fixedRow(workbook.getSheet("存款"), 1, "acct_master", "acct_no", "张三");
        fixedRow(workbook.getSheet("存款"), 2, "acct_master", "customer_no", "李四");
        fixedRow(workbook.getSheet("贷款"), 1, "acct_master", "loan_no", "张三");

        ReplayDatabaseComparisonExcelParser.ParsedImport result = parse(workbook);

        assertEquals(3, result.errors().size());
        assertEquals(3, result.errors().stream().filter(error -> error.reason().equals("同一表出现在不同 Sheet")).count());
        assertEquals(3, result.tables().get(0).rows().size());
    }

    @Test
    void usesTheLastNonBlankReviserForOneTableWithoutTreatingChangesAsConflicts() throws Exception {
        XSSFWorkbook workbook = requiredWorkbook();
        fixedRow(workbook.getSheet("存款"), 1, "acct_master", "acct_no", "张三");
        fixedRow(workbook.getSheet("存款"), 2, "acct_master", "customer_no", "李四（c-lisi）");
        fixedRow(workbook.getSheet("存款"), 3, "acct_master", "currency", "");

        ReplayDatabaseComparisonExcelParser.ParsedImport result = parse(workbook);

        assertTrue(result.errors().isEmpty());
        assertEquals("李四（c-lisi）", result.tables().get(0).reviserInput());
    }

    private static XSSFWorkbook requiredWorkbook() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        createSheet(workbook, "存款");
        createSheet(workbook, "贷款");
        createSheet(workbook, "公共");
        createSheet(workbook, "结算");
        return workbook;
    }

    private static void createSheet(XSSFWorkbook workbook, String name) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        header.createCell(2).setCellValue("表英文名");
        header.createCell(4).setCellValue("字段英文名");
        header.createCell(6).setCellValue("负责人");
    }

    private static void fixedRow(Sheet sheet, int index, String table, String field, String reviser) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue("忽略序号");
        row.createCell(1).setCellValue("忽略领域");
        row.createCell(2).setCellValue(table);
        row.createCell(3).setCellValue("忽略表中文名");
        row.createCell(4).setCellValue(field);
        row.createCell(5).setCellValue("忽略字段中文名");
        row.createCell(6).setCellValue(reviser);
    }

    private ReplayDatabaseComparisonExcelParser.ParsedImport parse(XSSFWorkbook workbook) throws Exception {
        return parser.parse(new ByteArrayInputStream(bytes(workbook)));
    }

    private static byte[] bytes(XSSFWorkbook workbook) throws Exception {
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
