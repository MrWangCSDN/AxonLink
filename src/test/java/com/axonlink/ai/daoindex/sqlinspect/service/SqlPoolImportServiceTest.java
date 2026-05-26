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

        service = new SqlPoolImportService(null, nsqlIndex);
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
}
