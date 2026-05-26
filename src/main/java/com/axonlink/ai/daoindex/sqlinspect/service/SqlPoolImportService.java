package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao.UpsertOutcome;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 池 Excel 导入服务（v2：含时间戳 + project 自动匹配）。
 *
 * <p>解析规则（取自需求方截图）：
 * <pre>
 *   行格式: [yyyy-MM-dd HH:mm:ss.SSS][cc-N][...][...][...][IndexWarnLog][WARN]
 *           ->[ClassName.methodName] [select ... from ...] failed to hit the index
 *
 *   提取:
 *     ① 第一个 [...]   → 日志时间戳，落 created_at / updated_at
 *     ② "->" 后第一个 [...] → named_sql；取首个 "." 前段作 sqls_id 查 project
 *     ③ 紧随其后的 [...]    → sql_text
 *
 *   过滤:
 *     - named_sql 含子串 "Entity" → 整行 SKIP（框架自带 entity selectAll，与业务无关）
 *     - 无法匹配双 [] → 整行 SKIP（malformed）
 *     - 时间戳解析失败 → 时间字段回退 NOW，行依旧入库（不丢数据）
 *
 *   入库:
 *     - (named_sql, sql_hash) 唯一键已存在 → 仅更新 updated_at（决策：时间不同只更新时间）
 *     - 不存在 → 插入；created_at / updated_at 都用第一个 [] 解析出的时间
 *
 *   后处理:
 *     - 按 (named_sql, sql_text) 字典序排序
 *     - 按 (named_sql, sql_hash) 批内去重（同条 SQL 在 Excel 内出现多次只入库一次）
 * </pre>
 */
@Service
public class SqlPoolImportService {

    private static final Logger log = LoggerFactory.getLogger(SqlPoolImportService.class);

    /**
     * 整行抽取：第一段 [TIMESTAMP] + 后面 ->[named_sql] [sql_text]。
     * <p>三段都用 DOTALL；时间戳段 {@code [^\]]+} 不会跨 ]；命名 SQL 同；SQL 段懒匹配 {@code (.+?)}。
     */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "\\[([^\\]]+)\\].*?->\\s*\\[([^\\]]+)\\]\\s*\\[(.+?)\\]",
            Pattern.DOTALL);

    /**
     * 日志时间戳格式 {@code 2026-05-23 11:23:13.836}。
     * <p>毫秒可选——某些行可能精度不同。
     */
    private static final DateTimeFormatter LOG_TS_FORMATTER =
            new DateTimeFormatterBuilder().build();

    private static class DateTimeFormatterBuilder {
        DateTimeFormatter build() {
            return new java.time.format.DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(java.time.temporal.ChronoField.MILLI_OF_SECOND, 1, 3, true)
                    .optionalEnd()
                    .toFormatter();
        }
    }

    /** 命名 SQL 中的 "Entity" 子串 → 整行丢弃。 */
    private static final String ENTITY_MARKER = "Entity";

    private static final int SQL_TEXT_MAX_LENGTH = 60_000;
    private static final int NAMED_SQL_MAX_LENGTH = 500;
    /** 用户原话「第 3 列是 excel 具体信息」→ 取索引 2 的列（C 列）做解析。 */
    private static final int RAW_TEXT_COLUMN_INDEX = 2;

    private final DiiSqlPoolDao dao;
    private final NsqlIdProjectIndex nsqlIndex;

    public SqlPoolImportService(DiiSqlPoolDao dao, NsqlIdProjectIndex nsqlIndex) {
        this.dao = dao;
        this.nsqlIndex = nsqlIndex;
    }

    /**
     * 入口：解析 Excel → 排序去重 → 批量 upsert。
     *
     * @param file 前端上传的 xlsx 文件
     * @param env  环境标记（可空）
     * @return 计数明细：inserted / updated / unchanged / skippedEntity / skippedMalformed / ...
     */
    public Map<String, Object> importExcel(MultipartFile file, String env) throws IOException {
        ParsedBatch parsed = parse(file);

        int inserted = 0, updated = 0, unchanged = 0;
        // 按 project 分桶计数，便于运维核对扫描覆盖度
        Map<String, Integer> byProject = new HashMap<>();
        for (ParsedRow row : parsed.rows) {
            String project = resolveProject(row.namedSql);
            byProject.merge(project, 1, Integer::sum);
            UpsertOutcome outcome = dao.upsertWithTimestamp(
                    row.namedSql, row.sqlText, row.sqlHash,
                    project, "EXCEL", env,
                    row.logTs);
            switch (outcome) {
                case INSERTED -> inserted++;
                case UPDATED  -> updated++;
                case UNCHANGED -> unchanged++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("unchanged", unchanged);
        result.put("duplicatedInBatch", parsed.duplicatedInBatch);
        result.put("skippedEntity", parsed.skippedEntity);
        result.put("skippedMalformed", parsed.skippedMalformed);
        result.put("skippedOversize", parsed.skippedOversize);
        result.put("totalRowsScanned", parsed.totalRowsScanned);
        result.put("byProject", byProject);

        log.info("[sql-pool-import] env={} 结果={}", env, result);
        return result;
    }

    /**
     * 反查命名 SQL 所属 project。
     * <p>规则：取 {@code named_sql} 首个 "." 前的段（即 {@code <sqls>} 的 id）→ 查
     * {@link NsqlIdProjectIndex#lookupProject(String)}；不命中走 {@code "other"}。
     */
    String resolveProject(String namedSql) {
        if (namedSql == null) return NsqlIdProjectIndex.UNKNOWN_PROJECT;
        int dot = namedSql.indexOf('.');
        String sqlsId = dot < 0 ? namedSql : namedSql.substring(0, dot);
        return nsqlIndex.lookupProject(sqlsId);
    }

    /**
     * 纯解析阶段：扫 Excel → 抽 (logTs, named_sql, sql_text) → 批内排序+去重。
     * <p>包私有，便于单测不依赖 DB / nsqlIndex 实例验证。
     */
    ParsedBatch parse(MultipartFile file) throws IOException {
        ParsedBatch batch = new ParsedBatch();
        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            if (wb.getNumberOfSheets() == 0) return batch;
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                String rawText = readRawText(row);
                if (rawText == null || rawText.isBlank()) continue;
                batch.totalRowsScanned++;

                Matcher m = LINE_PATTERN.matcher(rawText);
                if (!m.find()) {
                    batch.skippedMalformed++;
                    continue;
                }
                String tsRaw = m.group(1).trim();
                String namedSql = m.group(2).trim();
                String sqlText = m.group(3).trim();

                if (namedSql.contains(ENTITY_MARKER)) {
                    batch.skippedEntity++;
                    continue;
                }
                if (namedSql.length() > NAMED_SQL_MAX_LENGTH) {
                    batch.skippedMalformed++;
                    continue;
                }
                if (sqlText.length() > SQL_TEXT_MAX_LENGTH) {
                    batch.skippedOversize++;
                    continue;
                }
                if (namedSql.isEmpty() || sqlText.isEmpty()) {
                    batch.skippedMalformed++;
                    continue;
                }

                LocalDateTime logTs = parseLogTimestamp(tsRaw);
                String sqlHash = sha256Hex(sqlText);
                batch.candidates.add(new ParsedRow(namedSql, sqlText, sqlHash, logTs));
            }
        }

        batch.candidates.sort(
                Comparator.comparing((ParsedRow r) -> r.namedSql)
                        .thenComparing(r -> r.sqlText));

        // 批内去重：key = (namedSql, sqlHash)；保留出现时间戳最大的（更"新"）作为代表
        Map<String, ParsedRow> dedup = new LinkedHashMap<>();
        for (ParsedRow r : batch.candidates) {
            String key = r.namedSql + "|" + r.sqlHash;
            ParsedRow prev = dedup.get(key);
            if (prev == null) {
                dedup.put(key, r);
            } else {
                batch.duplicatedInBatch++;
                // 保留时间更晚的，让 updated_at 落最新
                if (r.logTs != null && (prev.logTs == null || r.logTs.isAfter(prev.logTs))) {
                    dedup.put(key, r);
                }
            }
        }
        batch.rows.addAll(dedup.values());
        return batch;
    }

    /**
     * 解析日志时间戳；失败 → 回退 {@code LocalDateTime.now()}（行依旧入库）。
     */
    static LocalDateTime parseLogTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(raw.trim(), LOG_TS_FORMATTER);
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    /** 读单元格——优先 C 列；空则回退拼整行。 */
    private String readRawText(Row row) {
        Cell preferred = row.getCell(RAW_TEXT_COLUMN_INDEX);
        String s = cellString(preferred);
        if (s != null && !s.isBlank()) return s;
        StringBuilder sb = new StringBuilder();
        for (Cell c : row) {
            String v = cellString(c);
            if (v != null && !v.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private String cellString(Cell cell) {
        if (cell == null) return null;
        try {
            switch (cell.getCellType()) {
                case STRING:  return cell.getStringCellValue();
                case NUMERIC: return String.valueOf(cell.getNumericCellValue());
                case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
                case FORMULA: return cell.getCellFormula();
                default:      return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static class ParsedBatch {
        List<ParsedRow> candidates = new ArrayList<>();
        List<ParsedRow> rows = new ArrayList<>();
        int totalRowsScanned = 0;
        int skippedEntity = 0;
        int skippedMalformed = 0;
        int skippedOversize = 0;
        int duplicatedInBatch = 0;
    }

    static class ParsedRow {
        final String namedSql;
        final String sqlText;
        final String sqlHash;
        final LocalDateTime logTs;

        ParsedRow(String namedSql, String sqlText, String sqlHash, LocalDateTime logTs) {
            this.namedSql = namedSql;
            this.sqlText = sqlText;
            this.sqlHash = sqlHash;
            this.logTs = logTs;
        }
    }
}
