package com.axonlink.ai.replay.dbissue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayDatabaseIssueExcelParserTest {
    @Test
    void createsOneTableIssueWithStatisticsAndZeroDifferenceRows() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var table = workbook.createSheet("表明细");
            row(table, 0, "表", "表中文名称", "528总量", "ccbs总量", "仅528存在", "仅ccbs存在", "主键相同、字段不同", "批次号");
            row(table, 1, "orders", "订单", "100", "98", "0", "0", "0", "DB20261020-01");
            var fields = workbook.createSheet("表字段明细");
            row(fields, 0, "表", "字段名称", "差异数量", "批次号");
            row(fields, 1, "orders", "order_id", "0", "DB20261020-01");
            var bytes = new ByteArrayOutputStream(); workbook.write(bytes);
            var parsed = new ReplayDatabaseIssueExcelParser().parse(new MockMultipartFile("file", "x.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()), "DB20261020-01");
            assertEquals(2, parsed.issues().size());
            var tableIssue = parsed.issues().get(0);
            assertEquals("orders", tableIssue.issueId());
            assertEquals("表级", tableIssue.issueLevel());
            assertEquals(100, tableIssue.total528());
            assertEquals(98, tableIssue.totalCcbs());
            assertEquals(0, tableIssue.only528());
            assertEquals(0, tableIssue.onlyCcbs());
            assertEquals(0, new ObjectMapper().valueToTree(tableIssue)
                    .path("sameKeyDifferentRowCount").asLong(-1));
            assertEquals(0, parsed.issues().get(1).differenceCount());
        }
    }

    @Test
    void retainsOneDifferingRowWhenTwoFieldsEachDifferOnce() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var table = workbook.createSheet("表明细");
            row(table, 0, "表", "仅528存在", "仅ccbs存在", "主键相同、字段不同", "批次号");
            row(table, 1, "orders", "4", "3", "1", "DB20261020-01");
            var fields = workbook.createSheet("表字段明细");
            row(fields, 0, "表", "字段名称", "差异数量", "批次号");
            row(fields, 1, "orders", "amount", "1", "DB20261020-01");
            row(fields, 2, "orders", "status", "1", "DB20261020-01");
            var bytes = new ByteArrayOutputStream();
            workbook.write(bytes);

            var parsed = new ReplayDatabaseIssueExcelParser().parse(new MockMultipartFile(
                    "file", "x.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    bytes.toByteArray()), null);

            assertEquals(3, parsed.issues().size());
            var tableIssue = parsed.issues().get(0);
            assertEquals(1, new ObjectMapper().valueToTree(tableIssue)
                    .path("sameKeyDifferentRowCount").asLong(-1));
            assertEquals(7, tableIssue.differenceCount());
            assertEquals(1, parsed.issues().get(1).differenceCount());
            assertEquals(1, parsed.issues().get(2).differenceCount());
        }
    }

    private static void row(org.apache.poi.ss.usermodel.Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
