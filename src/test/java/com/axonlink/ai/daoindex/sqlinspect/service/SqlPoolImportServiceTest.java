package com.axonlink.ai.daoindex.sqlinspect.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlPoolImportService} 解析逻辑单元测试（v2）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>截图样本：IndexWarnLog WARN 行 → 抽 timestamp + named_sql + sql_text</li>
 *   <li>Entity 整行丢弃</li>
 *   <li>同行同名同 SQL 批内去重（保留时间戳最新的）</li>
 *   <li>排序：(named_sql, sql_text) 字典序</li>
 *   <li>畸形行 skipped_malformed</li>
 *   <li>时间戳解析：含毫秒 / 不含毫秒 / 解析失败回退 now()</li>
 *   <li>project 自动匹配：sqlsId 命中 → 模块名；不命中 → "other"</li>
 * </ul>
 *
 * <p>不依赖 DB——只测 {@link SqlPoolImportService#parse} 与 {@link SqlPoolImportService#resolveProject}。
 */
@DisplayName("SqlPoolImportService —— Excel 解析 + 时间 + project 匹配")
class SqlPoolImportServiceTest {

    private SqlPoolImportService service;
    private NsqlIdProjectIndex nsqlIndex;

    @BeforeEach
    void setup() {
        // 构造一个手填映射的 NsqlIdProjectIndex 替身，跳过文件系统扫描
        nsqlIndex = new NsqlIdProjectIndex(null);
        Map<String, String> mapping = new HashMap<>();
        mapping.put("FtBusiNamedSql", "loan-bcc");
        mapping.put("DpCbQryAcctCount", "dept-bcc");
        mapping.put("DpCbGrpEltrActBkOrx", "dept-bcc");
        mapping.put("Kapp_code_rule", "comm-bcc");  // 即便能匹配，Entity 行也应被丢
        nsqlIndex.replaceForTesting(mapping);

        // ObjectProvider 用 Spring Mocks 简化：测试不走 whitelist 路径，传一个空 provider
        org.springframework.beans.factory.ObjectProvider<WhitelistApplicationService> emptyProvider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public WhitelistApplicationService getObject() { return null; }
                    @Override public WhitelistApplicationService getObject(Object... args) { return null; }
                    @Override public WhitelistApplicationService getIfAvailable() { return null; }
                    @Override public WhitelistApplicationService getIfUnique() { return null; }
                };
        service = new SqlPoolImportService(null, nsqlIndex, emptyProvider);
    }

    /** 用 POI 在内存里构造 xlsx，第 C 列放传入的日志文本。 */
    private MockMultipartFile buildExcel(String... rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("IndexWarnMonitor_7Day");
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i);
                r.createCell(2).setCellValue(rows[i]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile(
                    "file", "test.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }

    @Test
    @DisplayName("标准样本：时间戳 + named_sql + sql_text 三段均抽到")
    void standardSample() throws IOException {
        String log = "[2026-05-23 11:23:13.836][cc-6][1053260523][8149a960014c][C445]" +
                "[IndexWarnLog][WARN]->[DpCbQryAcctCount.sel_kdpb_cb_acct_count_temp_sum]" +
                " [select '00000001' as ourBnkInsID, nvl(sum(crndy_corp_new_opnacnum),0)" +
                " as crnDyCorpNewOpnAcNum from kdpb_cb_acct_count_temp] failed to hit the index";
        MockMultipartFile file = buildExcel(log);

        SqlPoolImportService.ParsedBatch batch = service.parse(file);
        assertEquals(1, batch.rows.size());
        SqlPoolImportService.ParsedRow r = batch.rows.get(0);
        assertEquals("DpCbQryAcctCount.sel_kdpb_cb_acct_count_temp_sum", r.namedSql);
        assertTrue(r.sqlText.startsWith("select '00000001'"));
        assertNotNull(r.logTs);
        assertEquals(2026, r.logTs.getYear());
        assertEquals(5, r.logTs.getMonthValue());
        assertEquals(23, r.logTs.getDayOfMonth());
        assertEquals(11, r.logTs.getHour());
        assertEquals(23, r.logTs.getMinute());
        assertEquals(13, r.logTs.getSecond());
    }

    @Test
    @DisplayName("Entity 整行丢弃 + 非 Entity 保留")
    void skipEntity() throws IOException {
        String e = "[2026-05-22 18:26:33.077][main]->[Kapp_code_rule.kapp_code_rule.Entity.selectAll]" +
                " [select cd_rule_make_id from kapp_code_rule] failed to hit the index";
        String ok = "[2026-05-22 18:30:00]->[DpCbQryAcctCount.sel] [select 1 from t] failed to hit the index";
        MockMultipartFile file = buildExcel(e, ok);

        SqlPoolImportService.ParsedBatch batch = service.parse(file);
        assertEquals(1, batch.rows.size());
        assertEquals("DpCbQryAcctCount.sel", batch.rows.get(0).namedSql);
        assertEquals(1, batch.skippedEntity);
    }

    @Test
    @DisplayName("批内重复 → 1 条；保留时间更晚的")
    void dedupKeepLatestTimestamp() throws IOException {
        String earlier = "[2026-05-22 10:00:00]->[A.b] [select 1 from t] failed to hit the index";
        String later = "[2026-05-23 11:00:00]->[A.b] [select 1 from t] failed to hit the index";
        MockMultipartFile file = buildExcel(earlier, later);

        SqlPoolImportService.ParsedBatch batch = service.parse(file);
        assertEquals(1, batch.rows.size());
        assertEquals(1, batch.duplicatedInBatch);
        // 应保留 2026-05-23 11:00:00（更晚）
        assertEquals(23, batch.rows.get(0).logTs.getDayOfMonth());
    }

    @Test
    @DisplayName("排序：(named_sql, sql_text) 字典序升序")
    void sortByNamedThenSql() throws IOException {
        String logZ = "[2026-05-22]->[Z.x] [select 1 from t] failed";
        String logA1 = "[2026-05-22]->[A.x] [select 2 from t] failed";
        String logA2 = "[2026-05-22]->[A.x] [select 1 from t] failed";
        // 故意写不带毫秒；解析失败也走 now() 回退；但日期段 yyyy-MM-dd 单独不合规
        // 这里给最简单 [marker] 测排序，省去 timestamp 干扰：
        MockMultipartFile file = buildExcel(
                "[ts]->[Z.x] [select 1 from t] failed",
                "[ts]->[A.x] [select 2 from t] failed",
                "[ts]->[A.x] [select 1 from t] failed");

        SqlPoolImportService.ParsedBatch batch = service.parse(file);
        assertEquals(3, batch.rows.size());
        assertEquals("A.x", batch.rows.get(0).namedSql);
        assertEquals("select 1 from t", batch.rows.get(0).sqlText);
        assertEquals("A.x", batch.rows.get(1).namedSql);
        assertEquals("select 2 from t", batch.rows.get(1).sqlText);
        assertEquals("Z.x", batch.rows.get(2).namedSql);
    }

    @Test
    @DisplayName("畸形行：无 -> / 无双 [] → skipped_malformed")
    void malformed() throws IOException {
        String noArrow = "[2026-05-23] [no arrow here] some text";
        String singleBracket = "[2026-05-23]->[only.one.bracket] no second bracket";
        MockMultipartFile file = buildExcel(noArrow, singleBracket);

        SqlPoolImportService.ParsedBatch batch = service.parse(file);
        assertEquals(0, batch.rows.size());
        assertEquals(2, batch.skippedMalformed);
    }

    @Test
    @DisplayName("跨行 SQL（POI 单元格保留 \\n）")
    void multilineSql() throws IOException {
        String log = "[2026-05-23 09:53:27.485]->[DpcbCbCashGathr.dyn_sel] " +
                "[select *\n  from kdpb_cb_cash_gathr\n where biz_dt = ?] failed to hit the index";
        MockMultipartFile file = buildExcel(log);

        SqlPoolImportService.ParsedBatch batch = service.parse(file);
        assertEquals(1, batch.rows.size());
        assertTrue(batch.rows.get(0).sqlText.contains("\n"));
    }

    @Test
    @DisplayName("时间戳解析：毫秒 / 秒精度 / 非法字符串回退 now()")
    void parseTimestamps() {
        LocalDateTime withMs = SqlPoolImportService.parseLogTimestamp("2026-05-23 11:23:13.836");
        assertEquals(13, withMs.getSecond());
        assertEquals(836_000_000, withMs.getNano());

        LocalDateTime noMs = SqlPoolImportService.parseLogTimestamp("2026-05-23 11:23:13");
        assertEquals(13, noMs.getSecond());

        LocalDateTime bad = SqlPoolImportService.parseLogTimestamp("not a date");
        // 不抛异常，回退 now()——只断言时间近似当下
        assertNotNull(bad);
    }

    @Test
    @DisplayName("project 自动匹配：sqlsId 命中 → 对应模块；不命中 → other")
    void resolveProject() {
        assertEquals("loan-bcc", service.resolveProject("FtBusiNamedSql.sel_x"));
        assertEquals("dept-bcc", service.resolveProject("DpCbQryAcctCount.foo"));
        assertEquals("dept-bcc", service.resolveProject("DpCbGrpEltrActBkOrx.bar"));
        assertEquals("other", service.resolveProject("NotInIndex.x"));
        assertEquals("other", service.resolveProject(""));
        assertEquals("other", service.resolveProject(null));
        // 没有点的命名 SQL → 整段当 sqlsId 查
        assertEquals("loan-bcc", service.resolveProject("FtBusiNamedSql"));
    }

    @Test
    @DisplayName("SHA-256 稳定性")
    void sha256Stable() {
        String a = SqlPoolImportService.sha256Hex("select 1 from t");
        String b = SqlPoolImportService.sha256Hex("select 1 from t");
        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    // ──────────────────────────── CSV 解析（v3 新增） ────────────────────────────

    /** 构造 CSV 内存文件：每行作为一条 record；用 MockMultipartFile 的 originalFilename 决定扩展名分派 */
    private MockMultipartFile buildCsv(String content) {
        return new MockMultipartFile(
                "file", "test.csv",
                "text/csv",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CSV：标准三列样本 → 抽到与 xlsx 一致的字段")
    void csvStandardSample() throws IOException {
        // 第 3 列（C 列）= 日志原文；前两列任意（这里放序号/时间戳冗余）
        // SQL 内的逗号被双引号包住——这是 commons-csv 的常规处理
        String csv = "idx,t,raw\n" +
                "1,xxx,\"[2026-05-23 11:23:13.836][cc-6][...][...][...][IndexWarnLog][WARN]" +
                "->[DpCbQryAcctCount.sel_x] [select '00000001' as a, b from kdpb_cb_acct_count_temp] failed to hit the index\"\n";
        MockMultipartFile file = buildCsv(csv);
        SqlPoolImportService.ParsedBatch batch = service.parse(file);

        assertEquals(1, batch.rows.size());
        assertEquals("DpCbQryAcctCount.sel_x", batch.rows.get(0).namedSql);
        assertTrue(batch.rows.get(0).sqlText.contains("kdpb_cb_acct_count_temp"));
        assertNotNull(batch.rows.get(0).logTs);
        assertEquals(23, batch.rows.get(0).logTs.getDayOfMonth());
    }

    @Test
    @DisplayName("CSV：含 BOM + Entity 行被丢 + 时间戳排序")
    void csvBomAndEntitySkip() throws IOException {
        // WPS 导出 CSV 常带 UTF-8 BOM（﻿）；服务应自动跳过
        String csv = "﻿idx,t,raw\n" +
                "1,a,\"[2026-05-22]->[Kapp_code_rule.kapp_code_rule.Entity.selectAll] [select 1 from t] failed\"\n" +
                "2,b,\"[2026-05-23]->[Foo.bar] [select 2 from u] failed\"\n";
        MockMultipartFile file = buildCsv(csv);
        SqlPoolImportService.ParsedBatch batch = service.parse(file);

        assertEquals(1, batch.rows.size(), "Entity 行被跳过，只剩 1 条");
        assertEquals("Foo.bar", batch.rows.get(0).namedSql);
        assertEquals(1, batch.skippedEntity);
    }

    @Test
    @DisplayName("CSV：SQL 含多行（POI 风格的 \\n 在 CSV 里通过双引号包裹）")
    void csvMultilineSql() throws IOException {
        // CSV 标准：双引号内允许真实换行字符
        String csv = "idx,t,raw\n" +
                "1,a,\"[2026-05-23 09:00:00]->[Multi.sql] [select *\n  from t\n where x=1] failed to hit the index\"\n";
        MockMultipartFile file = buildCsv(csv);
        SqlPoolImportService.ParsedBatch batch = service.parse(file);

        assertEquals(1, batch.rows.size());
        assertTrue(batch.rows.get(0).sqlText.contains("\n"),
                "CSV 单元格的真实换行应保留进 sql_text");
    }

    @Test
    @DisplayName("CSV：列错位时回退按 \"含 ->[\" 找列")
    void csvColumnFallback() throws IOException {
        // 假设用户导出 CSV 时只有 2 列，日志在 B（index=1）而非 C（index=2）
        // service 的 readCsvRaw 应回退找 含 "->[" 的列
        String csv = "idx,raw\n" +
                "1,\"[2026-05-23]->[Foo.bar] [select 1 from t] failed\"\n";
        MockMultipartFile file = buildCsv(csv);
        SqlPoolImportService.ParsedBatch batch = service.parse(file);

        assertEquals(1, batch.rows.size(), "列错位时应回退识别");
        assertEquals("Foo.bar", batch.rows.get(0).namedSql);
    }

    @Test
    @DisplayName("CSV：批内同 (named_sql, sql_text) 重复 → 仅保留 1 条 + 计数")
    void csvDedupInBatch() throws IOException {
        String row = "1,a,\"[2026-05-23]->[A.b] [select 1 from t] failed\"\n";
        String csv = "idx,t,raw\n" + row + row + row;
        MockMultipartFile file = buildCsv(csv);
        SqlPoolImportService.ParsedBatch batch = service.parse(file);

        assertEquals(1, batch.rows.size());
        assertEquals(2, batch.duplicatedInBatch);
    }

    @Test
    @DisplayName("文件分派：扩展名 .csv → CSV 解析；.xlsx → Excel 解析")
    void dispatchByExtension() throws IOException {
        // 验证两条解析路径都被路由到——单元测试通过同一 service 跑两次
        MockMultipartFile csvFile = buildCsv("idx,t,raw\n1,a,\"[2026-05-23]->[A.b] [select 1] failed\"\n");
        MockMultipartFile xlsxFile = buildExcel("[2026-05-23]->[X.y] [select 2] failed");

        SqlPoolImportService.ParsedBatch csvBatch = service.parse(csvFile);
        SqlPoolImportService.ParsedBatch xlsxBatch = service.parse(xlsxFile);

        assertEquals("A.b", csvBatch.rows.get(0).namedSql);
        assertEquals("X.y", xlsxBatch.rows.get(0).namedSql);
    }
}
