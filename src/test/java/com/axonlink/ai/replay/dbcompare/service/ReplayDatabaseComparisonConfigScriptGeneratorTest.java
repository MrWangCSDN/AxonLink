package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptValidationError;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTableItem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonConfigScriptGeneratorTest {

    private final ReplayDatabaseComparisonConfigScriptGenerator generator =
            new ReplayDatabaseComparisonConfigScriptGenerator();

    @Test
    void ordersDomainsAndTablesAndMapsConfigurationRows() {
        List<ReplayDbCompareVersionTableItem> tables = List.of(
                table("z_platform", "平台配置", "平台组", field("id", "编号", true, 1)),
                table("z_deposit", "存款配置比对", "存款组", field("id", "编号", true, 1)),
                table("sett_table", "结算配置", "结算组", field("id", "编号", true, 1)),
                table("comm_table", "", "公共组", field("id", "编号", true, 1)),
                table("loan_table", "贷款配置", "贷款组", field("id", "编号", true, 1)),
                table("a_deposit", "账户主表", "存款组", field("id", "编号", true, 1)));

        String sql = sql(generator.generate("20260914-172637", tables));

        assertBefore(sql, "(1,'dept','a_deposit','账户主表比对','2','1','3','1')",
                "(2,'dept','z_deposit','存款配置比对','2','1','3','1')");
        assertBefore(sql, "(2,'dept'", "(3,'loan'");
        assertBefore(sql, "(3,'loan'", "(4,'comm'");
        assertBefore(sql, "(4,'comm','comm_table','comm_table比对'", "(5,'sett'");
        assertBefore(sql, "(5,'sett'", "(6,'platform'");
    }

    @Test
    void mapsOrderedFieldsPrimaryKeysAndTableSelectSql() {
        ReplayDbCompareVersionTableItem account = table(
                "acct_master", "账户主表", "存款组",
                field("customer_no", "", false, 2),
                field("acct_no", "账号", true, 1));

        String sql = sql(generator.generate("20260914-172637", List.of(account)));

        assertTrue(sql.contains("(1,'acct_no','1','账号','1','1','1','0')"));
        assertTrue(sql.contains("(1,'customer_no','1','customer_no','2','2','0','0')"));
        assertTrue(sql.contains("(1,'(select acct_no,customer_no from acct_master) orig',1,"
                + "'(select acct_no,customer_no from acct_master) dest',2)"));
    }

    @Test
    void generatesAllScopeShapesAndUsesCompositePrimaryKeyOrderForLimit() {
        ReplayDbCompareVersionField a = new ReplayDbCompareVersionField(
                "a", "A", 1, true, 2, 1);
        ReplayDbCompareVersionField b = new ReplayDbCompareVersionField(
                "b", "B", 2, true, 1, 2);
        ReplayDbCompareVersionField c = new ReplayDbCompareVersionField(
                "c", "C", 3, false, null, 3);

        String full = sql(generator.generate("v1", List.of(scopedTable(null, null, a, b, c))));
        String conditionOnly = sql(generator.generate(
                "v2", List.of(scopedTable("(status = '1')", null, a, b, c))));
        String limitOnly = sql(generator.generate(
                "v3", List.of(scopedTable(null, 1000L, a, b, c))));
        String both = sql(generator.generate(
                "v4", List.of(scopedTable("(status = '1' OR status = '2')", 1000L, a, b, c))));

        assertTrue(full.contains("(select a,b,c from txn) orig"));
        assertTrue(conditionOnly.contains("(select a,b,c from txn where (status = ''1'')) orig"));
        assertTrue(limitOnly.contains("(select a,b,c from txn order by b,a limit 1000) orig"));
        assertTrue(both.contains("(select a,b,c from txn where (status = ''1'' OR status = ''2'')"
                + " order by b,a limit 1000) orig"));
    }

    private ReplayDbCompareVersionTableItem scopedTable(
            String whereSql,
            Long compareLimit,
            ReplayDbCompareVersionField... fields) {
        return new ReplayDbCompareVersionTableItem(
                1L, 1L, "base_schema", "txn", "交易", "存款组",
                "100", "zhangsan", "张三", "200", null, "李经理",
                null, whereSql, compareLimit, LocalDate.of(2026, 9, 14),
                fields.length, List.of(fields));
    }

    @Test
    void escapesQuotesBatchesAtFiveHundredAndWritesVerifiableMetadata() throws Exception {
        List<ReplayDbCompareVersionField> fields = new ArrayList<>();
        for (int index = 1; index <= 501; index++) {
            fields.add(field("field_" + index, index == 1 ? "客户'账户" : "字段" + index,
                    index == 1, index));
        }

        ReplayDatabaseComparisonConfigScriptGenerator.GeneratedScript result = generator.generate(
                "20260914-172637", List.of(table("acct_master", "账户主表", "存款组",
                        fields.toArray(ReplayDbCompareVersionField[]::new))));
        String sql = sql(result);

        assertTrue(sql.contains("客户''账户"));
        assertEquals(2, count(sql, "INSERT INTO tss_bcomp_field"));
        assertTrue(sql.contains("-- Version: 20260914-172637"));
        assertTrue(sql.contains("-- Tables: 1"));
        assertTrue(sql.contains("-- Fields: 501"));
        assertFalse(sql.contains("SHA-256"));
        assertTrue(sql.contains("COMMIT;"));
        assertEquals(result.sqlContent().length, result.scriptSize());
        assertEquals(result.gzipContent().length, result.compressedSize());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(result.sqlContent())), result.sha256());
        assertArrayEquals(result.sqlContent(), gunzip(result.gzipContent()));
    }

    @Test
    void returnsEveryInvalidTableAndFieldInOneValidationFailure() {
        List<ReplayDbCompareVersionTableItem> tables = List.of(
                table("bad-table", "错误表", "未知组"),
                table("valid_table", "有效表", "存款组",
                        field("bad field", "错误字段", false, 1),
                        field("good_field", "重复顺序", false, 1)));

        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> generator.generate("20260914-172637", tables));

        assertEquals("CONFIG_SCRIPT_VALIDATION_FAILED", error.errorCode());
        assertEquals(422, error.status().value());
        Map<?, ?> data = (Map<?, ?>) error.data();
        List<?> errors = (List<?>) data.get("errors");
        assertEquals(5, errors.size());
        assertTrue(errors.stream().map(ReplayDbCompareConfigScriptValidationError.class::cast)
                .anyMatch(item -> item.reason().contains("领域")));
        assertTrue(errors.stream().map(ReplayDbCompareConfigScriptValidationError.class::cast)
                .anyMatch(item -> item.reason().contains("表英文名")));
        assertTrue(errors.stream().map(ReplayDbCompareConfigScriptValidationError.class::cast)
                .anyMatch(item -> item.reason().contains("字段英文名")));
        assertTrue(errors.stream().map(ReplayDbCompareConfigScriptValidationError.class::cast)
                .anyMatch(item -> item.reason().contains("比对顺序")));
        assertTrue(errors.stream().map(ReplayDbCompareConfigScriptValidationError.class::cast)
                .anyMatch(item -> item.reason().contains("没有比对字段")));
    }

    @Test
    void rendersOneHundredThousandFieldsInFiveHundredRowStatements() {
        List<ReplayDbCompareVersionTableItem> tables = new ArrayList<>();
        for (int tableIndex = 1; tableIndex <= 200; tableIndex++) {
            List<ReplayDbCompareVersionField> fields = new ArrayList<>();
            for (int fieldIndex = 1; fieldIndex <= 500; fieldIndex++) {
                fields.add(field("field_" + fieldIndex, "字段" + fieldIndex,
                        fieldIndex == 1, fieldIndex));
            }
            tables.add(table("table_" + tableIndex, "表" + tableIndex, "存款组",
                    fields.toArray(ReplayDbCompareVersionField[]::new)));
        }

        ReplayDatabaseComparisonConfigScriptGenerator.GeneratedScript result =
                generator.generate("20260914-172637", tables);
        String sql = sql(result);

        assertEquals(100_000, result.fieldCount());
        assertEquals(result.sqlContent().length, result.scriptSize());
        assertTrue(result.compressedSize() < result.scriptSize());
        assertEquals(200, count(sql, "INSERT INTO tss_bcomp_field"));
    }

    private ReplayDbCompareVersionTableItem table(
            String tableName,
            String tableComment,
            String domain,
            ReplayDbCompareVersionField... fields) {
        return new ReplayDbCompareVersionTableItem(
                1L, 1L, "base_schema", tableName, tableComment, domain,
                "100", "zhangsan", "张三", "200", "李经理",
                LocalDate.of(2026, 9, 14), fields.length, List.of(fields));
    }

    private ReplayDbCompareVersionField field(
            String name,
            String comment,
            boolean primaryKey,
            int comparisonOrder) {
        return new ReplayDbCompareVersionField(
                name, comment, comparisonOrder, primaryKey, comparisonOrder);
    }

    private String sql(ReplayDatabaseComparisonConfigScriptGenerator.GeneratedScript result) {
        return new String(result.sqlContent(), StandardCharsets.UTF_8);
    }

    private byte[] gunzip(byte[] compressed) throws Exception {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private int count(String value, String target) {
        int count = 0;
        int start = 0;
        while ((start = value.indexOf(target, start)) >= 0) {
            count++;
            start += target.length();
        }
        return count;
    }

    private void assertBefore(String value, String first, String second) {
        assertTrue(value.indexOf(first) >= 0, first);
        assertTrue(value.indexOf(second) > value.indexOf(first), second);
    }
}
