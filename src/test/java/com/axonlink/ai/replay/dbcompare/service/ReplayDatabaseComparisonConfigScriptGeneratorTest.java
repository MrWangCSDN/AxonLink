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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonConfigScriptGeneratorTest {

    private final ReplayDatabaseComparisonConfigScriptGenerator generator =
            new ReplayDatabaseComparisonConfigScriptGenerator();

    @Test
    void newTargetCarriesImmutableCodePhysicalSourceNameAndFullScopeButNamedRemainsFourteenColumns() {
        var input = scopedTable("(status = 'A\\B')", null, field("id", "编号", true, 1))
                .withConfigurationIdentity(new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigurationIdentity(
                        "physical_acct", "账户'一\\组"));
        String result = sql(generator.generateNew("multi", List.of(input)));
        assertTrue(result.contains("bcomp_max_partition_num,source_table_name,configuration_name,configuration_scope)"));
        assertTrue(result.contains("'dept','txn'"));
        assertTrue(result.contains(HexFormat.of().formatHex(
                "from physical_acct where (status = 'A\\B')".getBytes(StandardCharsets.UTF_8))));
        assertTrue(result.contains("'physical_acct',CONVERT(0x"));
        assertTrue(result.contains(HexFormat.of().formatHex("账户'一\\组".getBytes(StandardCharsets.UTF_8))));
        assertTrue(result.contains(HexFormat.of().formatHex("(status = 'A\\B')".getBytes(StandardCharsets.UTF_8))));
        String named = sql(generator.generateNamed("named", List.of(input), Map.of("txn", 64)));
        assertFalse(named.contains("configuration_scope"));
        assertTrue(named.contains("bcomp_max_partition_num)"));
    }

    @Test
    void newTargetRejectsOversizedConfigurationCodesAndDuplicateCodes() {
        var tooLong = table("a".repeat(101), "账户", "存款组", field("id", "编号", true, 1));
        assertThrows(ReplayDatabaseComparisonGenerationException.class,
                () -> generator.generateNew("multi", List.of(tooLong)));
        var input = table("acct_master", "账户", "存款组", field("id", "编号", true, 1));
        assertThrows(ReplayDatabaseComparisonGenerationException.class,
                () -> generator.generateNew("multi", List.of(input, input)));
    }

    @Test
    void legacyRejectsNewAdditionalConfigurationsWithoutChangingHistoricalCustomSqlAliases() {
        var input = table("acct_master_1", "账户", "存款组", field("id", "编号", true, 1))
                .withConfigurationIdentity(new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigurationIdentity(
                        "acct_master", "第二配置"));
        assertThrows(ReplayDatabaseComparisonGenerationException.class, () -> generator.generate("multi", List.of(input)));
    }

    @Test void allNewConfigurationsIncludeTheFourteenthMaxPartitionColumn() {
        var input = table("acct_master", "账户", "存款组", field("id", "编号", true, 1));
        var originalNew = sql(generator.generateNew("v1",List.of(input)));
        var named = sql(generator.generateNamed("named-1",List.of(input),Map.of("acct_master",100)));
        assertTrue(originalNew.contains("bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num,source_table_name,configuration_name,configuration_scope)"));
        assertTrue(originalNew.contains(",1,'HASH',100,'张三','zhangsan',64,'acct_master','默认配置','')"));
        assertTrue(named.contains("bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num)"));
        assertTrue(named.contains(",1,'HASH',100,'张三','zhangsan',100)"));
        assertTrue(named.contains("INSERT INTO tss_bcomp_conf_new"));
        assertEquals(originalNew,sql(generator.generateNew("v1",List.of(input))));
    }

    @Test void allNewConfigurationsPreserveBackslashTextForBdImport() {
        var input=table("acct_master","A\\B","存款组",field("id","A\\'B",true,1));
        String builtin=sql(generator.generateNew("v1",List.of(input)));
        String named=sql(generator.generateNamed("named-1",List.of(input),Map.of("acct_master",64)));
        assertTrue(builtin.contains("CONVERT(0x415c42e6af94e5afb9 USING utf8mb4)"));
        assertTrue(builtin.contains("CONVERT(0x415c2742 USING utf8mb4)"));
        assertTrue(sql(generator.generate("v1",List.of(input))).contains("'A\\B比对'"));
        assertTrue(named.contains("CONVERT(0x415c42e6af94e5afb9 USING utf8mb4)"));
        assertTrue(named.contains("CONVERT(0x415c2742 USING utf8mb4)"));
    }

    @Test void newTargetUsesEachSnapshotsMaximumAndKeepsNamedOverridesIndependent() {
        var first = table("acct_master", "账户", "存款组", field("id", "编号", true, 1))
                .withPartitionNum(8).withSampleLimit(33).withMaxPartitionNum(100);
        var second = table("loan_detail", "贷款", "贷款组", field("id", "编号", true, 1))
                .withMaxPartitionNum(256);
        String builtin = sql(generator.generateNew("v2",List.of(first, second)));
        assertTrue(builtin.contains(",1,'HASH',33,'张三','zhangsan',100,'acct_master'"));
        assertTrue(builtin.contains(",1,'HASH',100,'张三','zhangsan',256,'loan_detail'"));
        assertFalse(sql(generator.generate("v2",List.of(first))).contains("bcomp_max_partition_num"));
        String named = sql(generator.generateNamed("profile",List.of(first),Map.of("acct_master",64)));
        assertTrue(named.contains(",1,'HASH',33,'张三','zhangsan',64)"));
        for (int invalid : List.of(0,257)) {
            assertThrows(ReplayDatabaseComparisonGenerationException.class,
                    () -> generator.generateNew("v2",List.of(first.withMaxPartitionNum(invalid))));
        }
    }

    @Test
    void customSqlProjectsSelectedAliasesInOrderForBothTargetsAndLimitsOutsideAggregation() {
        var definition = new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSql(
                "select tenant_id, sum(amount) as \"Total_Amount\" from acct_master group by tenant_id;",
                "acct_master", List.of(
                        new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn("tenant_id", "tenant_id", "varchar", 1),
                        new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn("total_amount", "Total_Amount", "numeric", 2)));
        var custom = new ReplayDbCompareVersionTableItem(1L, 1L, "base_schema", "SQL_123456", "汇总", "存款组",
                "100", "creator", "创建人", "101", "owner", "负责人",
                null, null, 25L, LocalDate.of(2026, 10, 15), 2,
                List.of(new ReplayDbCompareVersionField("total_amount", "金额", 2, false, null, 1),
                        new ReplayDbCompareVersionField("tenant_id", "租户", 1, false, null, 2)))
                .withCustomSql(definition)
                .withComparisonKey(new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection(
                        "SQL_RESULT_KEY", "RESULT_KEY", List.of("tenant_id")));

        for (var result : List.of(generator.generate("v1", List.of(custom)), generator.generateNew("v1", List.of(custom)))) {
            String output = sql(result);
            assertTrue(output.contains("select result_q.\"Total_Amount\" as \"total_amount\",result_q.\"tenant_id\" as \"tenant_id\" from ("));
            assertTrue(output.contains(") result_q order by \"tenant_id\" limit 25) orig"));
            assertTrue(output.contains(") result_q order by \"tenant_id\" limit 25) dest"));
            assertTrue(output.contains("(1,'total_amount','1','金额','1','1','0','0')"));
            assertTrue(output.contains("(1,'tenant_id','1','租户','2','2','1','0')"));
            assertFalse(output.contains(";) result_q"));
            assertTrue(output.contains("'SQL_123456','汇总比对'"));
        }
    }

    @Test
    void customSqlRequiresResultKeyAndCompleteProjectedMetadata() {
        var definition = new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSql(
                "select id from acct_master", "acct_master", List.of(
                        new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn("id", "id", "bigint", 1)));
        var custom = table("SQL_123456", "自定义查询", "存款组", field("id", "编号", false, 1))
                .withCustomSql(definition);
        assertThrows(ReplayDatabaseComparisonGenerationException.class,
                () -> generator.generate("v1", List.of(custom)));
        var missing = custom.withComparisonKey(new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection(
                "SQL_RESULT_KEY", "RESULT_KEY", List.of("id"))).withCustomSql(
                new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSql(
                        "select amount as other from acct_master", "acct_master", List.of(
                                new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn("other", "other", "numeric", 1))));
        assertThrows(ReplayDatabaseComparisonGenerationException.class,
                () -> generator.generateNew("v1", List.of(missing)));
    }

    @Test
    void customSqlQuotesCanonicalAliasesAndKeysThatAreSqlKeywords() {
        var definition = new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSql(
                "select id as \"select\" from acct_master", "acct_master", List.of(
                        new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn("select", "select", "bigint", 1)));
        var custom = scopedTable(null, 3L, field("select", "编号", false, 1))
                .withCustomSql(definition)
                .withComparisonKey(new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection(
                        "SQL_RESULT_KEY", "RESULT_KEY", List.of("select")));

        String result = sql(generator.generate("v1", List.of(custom)));
        assertTrue(result.contains("result_q.\"select\" as \"select\""));
        assertTrue(result.contains("order by \"select\" limit 3"));
    }

    @Test
    void customSqlBackslashesUseUtf8HexExpressionsIndependentOfMysqlStringEscapeMode() {
        var definition = new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSql(
                "select 'a\\b' as result_key from acct_master", "acct_master", List.of(
                        new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn("result_key", "result_key", "varchar", 1)));
        var custom = table("SQL_123456", "自定义查询", "存款组", field("result_key", "编号", false, 1))
                .withCustomSql(definition)
                .withComparisonKey(new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection(
                        "SQL_RESULT_KEY", "RESULT_KEY", List.of("result_key")));

        for (var generated : List.of(generator.generate("v1", List.of(custom)), generator.generateNew("v1", List.of(custom)))) {
            var matcher = java.util.regex.Pattern.compile("CONVERT\\(0x([0-9a-f]+) USING utf8mb4\\)").matcher(sql(generated));
            List<String> queries = new ArrayList<>();
            while (matcher.find()) {
                String text = new String(HexFormat.of().parseHex(matcher.group(1)), StandardCharsets.UTF_8);
                if (text.endsWith(") orig") || text.endsWith(") dest")) queries.add(text);
            }
            assertEquals(2, queries.size());
            assertTrue(queries.get(0).contains("a\\b"));
            assertTrue(queries.get(0).endsWith(") orig"));
            assertTrue(queries.get(1).endsWith(") dest"));
        }
    }

    @Test
    void ordinaryTableOutputRemainsByteCompatible() {
        String expected = """
                START TRANSACTION;

                TRUNCATE TABLE tss_bcomp_field;
                TRUNCATE TABLE tss_bcomp_table_sql;
                TRUNCATE TABLE tss_bcomp_conf;

                INSERT INTO tss_bcomp_conf
                  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit)
                VALUES
                (1,'dept','acct_master','账户比对','2','1','3','1',1,'HASH',100);

                INSERT INTO tss_bcomp_field
                  (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
                VALUES
                (1,'id','1','编号','1','1','1','0');

                INSERT INTO tss_bcomp_table_sql
                  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
                VALUES
                (1,'(select id from acct_master) orig',1,'(select id from acct_master) dest',2);

                COMMIT;

                -- Version: v1
                -- Tables: 1
                -- Fields: 1
                """;
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), generator.generate("v1",
                List.of(table("acct_master", "账户", "存款组", field("id", "编号", true, 1)))).sqlContent());
    }

    @Test void selectedUniqueSnapshotControlsFlagAndLimitForBothTargets() {
        var selected = new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection("ref_unique", "UNIQUE_INDEX", List.of("ref", "tenant"));
        var table = new ReplayDbCompareVersionTableItem(1L, 1L, "base", "acct_master", "账户", "存款组", "100", "creator", "创建人", "101", "owner", "负责人",
                null, null, 25L, LocalDate.of(2026, 10, 15), 3,
                List.of(new ReplayDbCompareVersionField("id", "编号", 1, true, 1, 1),
                        new ReplayDbCompareVersionField("tenant", "租户", 2, false, null, 2),
                        new ReplayDbCompareVersionField("ref", "引用", 3, false, null, 3))).withComparisonKey(selected);
        for (ConfigScriptTarget target : ConfigScriptTarget.values()) {
            String result = target.convert(sql(generator.generate("v1", List.of(table))));
            assertTrue(result.contains("order by ref,tenant limit 25"));
            assertTrue(result.contains("(1,'id','1','编号','1','1','0','0')"));
            assertTrue(result.contains("(1,'tenant','1','租户','2','2','1','0')"));
            assertTrue(result.contains("(1,'ref','1','引用','3','3','1','0')"));
        }
    }

    @Test void exportsPerTableSampleLimitIncludingZero() {
        var table = table("acct_master", "账户", "存款组", field("id", "编号", true, 1));
        assertTrue(sql(generator.generate("v1", List.of(table.withSampleLimit(17)))).contains("'HASH',17)"));
        assertTrue(sql(generator.generate("v1", List.of(table.withSampleLimit(0)))).contains("'HASH',0)"));
        assertThrows(ReplayDatabaseComparisonGenerationException.class,
                () -> generator.generate("v1", List.of(table.withSampleLimit(10001))));
    }

    @Test
    void newScriptExportsAllDifferencesWithoutWideningTheSourceQuery() {
        var primaryKey = new ReplayDbCompareVersionField("id", "编号", 1, true, 1, 1);
        var table = scopedTable("(status = '1')", 7L, primaryKey).withSampleLimit(-1);
        String script = assertDoesNotThrow(() -> sql(generator.generateNew("v1", List.of(table))));
        assertTrue(script.contains("bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num,source_table_name,configuration_name,configuration_scope)"));
        assertTrue(script.contains("'HASH',-1,'张三','zhangsan',64,'txn'"));
        assertTrue(script.contains("(select id from txn where (status = ''1'') order by id limit 7) orig"));
        assertThrows(ReplayDatabaseComparisonGenerationException.class,
                () -> generator.generateNew("v1", List.of(table.withSampleLimit(-2))));
    }

    @Test
    void historicalSnapshotPartitionCountDoesNotControlNewSql() {
        var table = table("acct_master", "账户", "存款组", field("id", "编号", true, 1));
        assertTrue(sql(generator.generate("v1", List.of(table.withPartitionNum(257))))
                .contains("'2','1','3','1',1,'HASH',100)"));
    }

    @Test
    void exportsPartitionCountAndFixedHashStrategyWithoutChangingFieldSchema() {
        var table = table("acct_master", "账户", "存款组", field("id", "编号", true, 1))
                .withPartitionNum(16);
        String sql = sql(generator.generate("v1", List.of(table)));
        assertTrue(sql.contains("bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit)"));
        assertTrue(sql.contains("'2','1','3','1',1,'HASH',100)"));
        assertTrue(sql.contains("(1,'id','1','编号','1','1','1','0')"));
    }

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

        assertBefore(sql, "(1,'dept','a_deposit','账户主表比对','2','1','3','1',1,'HASH',100)",
                "(2,'dept','z_deposit','存款配置比对','2','1','3','1',1,'HASH',100)");
        assertBefore(sql, "(2,'dept'", "(3,'loan'");
        assertBefore(sql, "(3,'loan'", "(4,'comm'");
        assertBefore(sql, "(4,'comm','comm_table','comm_table比对'", "(5,'sett'");
        assertBefore(sql, "(5,'sett'", "(6,'platform'");
        for (ConfigScriptTarget target : ConfigScriptTarget.values()) {
            String download = target.convert(sql);
            for (String module : List.of("dept", "loan", "comm", "sett", "platform")) {
                assertTrue(download.contains("," + "'" + module + "'" + ","));
            }
            assertFalse(download.contains(",'存款组',"));
        }
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
        assertEquals(6, errors.size());
        assertTrue(errors.stream().map(ReplayDbCompareConfigScriptValidationError.class::cast)
                .anyMatch(item -> item.reason().contains("真实来源表名")));
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


    @Test
    void blankHistoricalDomainsReturnReadableValidationErrorsForBothTargets() {
        for (String domain : java.util.Arrays.asList(null, "", "   ")) {
            List<ReplayDbCompareVersionTableItem> snapshot = List.of(table(
                    "acct_master", "账户主表", domain, field("acct_no", "账号", true, 1)));
            ReplayDatabaseComparisonGenerationException legacy = assertThrows(
                    ReplayDatabaseComparisonGenerationException.class,
                    () -> generator.generate("v1", snapshot));
            ReplayDatabaseComparisonGenerationException current = assertThrows(
                    ReplayDatabaseComparisonGenerationException.class,
                    () -> generator.generateNew("v1", snapshot));
            for (var error : List.of(legacy, current)) {
                assertEquals("CONFIG_SCRIPT_VALIDATION_FAILED", error.errorCode());
                List<?> errors = (List<?>) ((Map<?, ?>) error.data()).get("errors");
                var failure = (ReplayDbCompareConfigScriptValidationError) errors.get(0);
                assertEquals("acct_master", failure.tableName());
                assertTrue(failure.reason().contains("领域为空"));
                assertTrue(failure.reason().contains("人员清单"));
            }
        }
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
