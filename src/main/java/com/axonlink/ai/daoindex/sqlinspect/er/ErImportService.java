package com.axonlink.ai.daoindex.sqlinspect.er;

import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErRelation;
import com.axonlink.ai.daoindex.sqlinspect.er.persistence.ErRelationDao;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ER 图 Excel 导入：把「导出的关系清单 Excel 经人工修改后的结果」整库替换该 env 的关系（v5）。
 *
 * <p>解析<b>导出列序</b>（{@code ErController} 导出表头）：
 * <pre>0 主表(被引用) | 1 从表(引用) | 2 关联列 | 3 键类型 | 4 键列数 | 5 置信度 | 6 状态 | …</pre>
 * 跳表头；缺 主表/从表/关联列 跳过；状态=已忽略/IGNORED 跳过（防呆）。
 * <p>导入行统一 {@code confidence='HIGH'}（让仅-HIGH 展示把每条都画出来）、{@code status='AUTO'}
 * （由 {@code ErRelationDao.batchInsertImported} 固定，让后续重算可覆盖导入）。
 */
@Service
public class ErImportService {

    private static final Logger log = LoggerFactory.getLogger(ErImportService.class);

    private static final int C_FROM = 0;
    private static final int C_TO = 1;
    private static final int C_JOIN = 2;
    private static final int C_KEYTYPE = 3;
    private static final int C_KEYCOUNT = 4;
    // 5=置信度（导入强制 HIGH，忽略文件值）、6=状态

    private final ErRelationDao dao;

    public ErImportService(ErRelationDao dao) {
        this.dao = dao;
    }

    /** 整库替换该 env：解析 → deleteByEnv → 批量插入 → 写绘制元信息。返回 {env,imported,deletedOld,skipped}。 */
    public Map<String, Object> importFile(MultipartFile file, String env) throws IOException {
        String effEnv = (env == null) ? "" : env.trim();
        List<ErRelation> rels = new ArrayList<>();
        int[] skipped = {0};
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".csv")) {
            parseCsv(file, rels, skipped);
        } else {
            parseXlsx(file, rels, skipped);
        }

        // 安全护栏：解析不到任何有效关系 → 中止，不删旧数据（防止误传错误格式文件把整库清空）
        if (rels.isEmpty()) {
            throw new IllegalArgumentException(
                    "未解析到有效关系行（请确认是「导出的关系清单 Excel」格式：列序 主表/从表/关联列/…）；已取消，未改动现有关系");
        }

        // 整库替换：先删后插
        int deleted = dao.deleteByEnv(effEnv);
        dao.batchInsertImported(effEnv, rels);
        dao.upsertMeta(effEnv, "IMPORT", LocalDateTime.now(), rels.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("env", effEnv.isEmpty() ? "(default)" : effEnv);
        result.put("imported", rels.size());
        result.put("deletedOld", deleted);
        result.put("skipped", skipped[0]);
        log.info("[er-import] env={} 结果={}", effEnv, result);
        return result;
    }

    private void parseXlsx(MultipartFile file, List<ErRelation> out, int[] skipped) throws IOException {
        try (InputStream raw = file.getInputStream();
             BufferedInputStream is = new BufferedInputStream(raw);
             Workbook wb = WorkbookFactory.create(is)) {
            if (wb.getNumberOfSheets() == 0) return;
            Sheet sheet = wb.getSheetAt(0);
            boolean header = true;
            for (Row row : sheet) {
                if (header) { header = false; continue; }   // 跳首行表头
                addRow(out, skipped,
                        cell(row, C_FROM), cell(row, C_TO), cell(row, C_JOIN),
                        cell(row, C_KEYTYPE), cell(row, C_KEYCOUNT), cell(row, 6));
            }
        }
    }

    private void parseCsv(MultipartFile file, List<ErRelation> out, int[] skipped) throws IOException {
        try (java.io.Reader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            reader.mark(1);
            int first = reader.read();
            if (first != 0xFEFF && first != -1) reader.reset();   // 去 BOM
            org.apache.commons.csv.CSVFormat fmt = org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                    .setIgnoreEmptyLines(true).setIgnoreSurroundingSpaces(true)
                    .setAllowMissingColumnNames(true).build();
            try (org.apache.commons.csv.CSVParser parser = new org.apache.commons.csv.CSVParser(reader, fmt)) {
                boolean header = true;
                for (org.apache.commons.csv.CSVRecord rec : parser) {
                    if (header) { header = false; continue; }   // 跳首行表头
                    addRow(out, skipped,
                            csv(rec, C_FROM), csv(rec, C_TO), csv(rec, C_JOIN),
                            csv(rec, C_KEYTYPE), csv(rec, C_KEYCOUNT), csv(rec, 6));
                }
            }
        }
    }

    /** 组装一条关系；缺 from/to/关联列 或 状态=已忽略 → 跳过计数。confidence 强制 HIGH。 */
    private void addRow(List<ErRelation> out, int[] skipped,
                        String from, String to, String join,
                        String keyType, String keyCount, String status) {
        String f = lower(from);
        String t = lower(to);
        if (f.isEmpty() || t.isEmpty() || join == null || join.trim().isEmpty()) { skipped[0]++; return; }
        if (isIgnored(status)) { skipped[0]++; return; }

        List<String> cols = new ArrayList<>();
        for (String c : join.split(",")) {
            String cc = c.trim().toLowerCase(Locale.ROOT);
            if (!cc.isEmpty()) cols.add(cc);
        }
        if (cols.isEmpty()) { skipped[0]++; return; }

        int kc = parseIntOr(keyCount, cols.size());
        String kt = (keyType == null || keyType.isBlank()) ? "PK" : keyType.trim();
        // confidence 强制 HIGH：导入即权威，保证仅-HIGH 展示把每条都画出来
        out.add(new ErRelation(f, t, cols, kt, kc, "HIGH"));
    }

    private static boolean isIgnored(String status) {
        if (status == null) return false;
        String s = status.trim();
        return s.contains("忽略") || "IGNORED".equalsIgnoreCase(s);
    }

    private static int parseIntOr(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            // 兼容 "3" 与 "3.0"（POI 数值单元格）
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private String csv(org.apache.commons.csv.CSVRecord rec, int idx) {
        return rec.size() > idx ? rec.get(idx) : null;
    }

    private String cell(Row row, int idx) {
        Cell c = row.getCell(idx);
        if (c == null) return null;
        try {
            switch (c.getCellType()) {
                case STRING:  return c.getStringCellValue();
                case NUMERIC: return String.valueOf(c.getNumericCellValue());
                case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
                case FORMULA: return c.getCellFormula();
                default:      return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
