package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.service.WhitelistApplicationService;
import com.axonlink.ai.daoindex.sqlinspect.slowsql.dto.ParsedSlowSqlRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 慢SQL明细导入：解析 xlsx/csv(列序 0领域 1耗时 2抽象SQL 3执行参数)→
 * 跳表头→逐行(不去重)→生成轮次→批量入库→按抽象SQL继承白名单。
 */
@Service
public class SlowSqlImportService {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlImportService.class);

    private static final int COL_DOMAIN = 0;
    private static final int COL_COST = 1;
    private static final int COL_ABSTRACT = 2;
    private static final int COL_PARAMS = 3;
    private static final int ABSTRACT_SQL_MAX = 60_000;

    private final DiiSlowSqlDao dao;
    private final ObjectProvider<WhitelistApplicationService> whitelistServiceProvider;

    public SlowSqlImportService(DiiSlowSqlDao dao,
                                ObjectProvider<WhitelistApplicationService> whitelistServiceProvider) {
        this.dao = dao;
        this.whitelistServiceProvider = whitelistServiceProvider;
    }

    /** 入口：解析→轮次→入库→继承。返回 {round, rowsImported, distinctAbstractSql, skipped}。 */
    public Map<String, Object> importFile(MultipartFile file, String env) throws IOException {
        List<ParsedSlowSqlRow> rows = new ArrayList<>();
        int[] skipped = {0};
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".csv")) {
            parseCsv(file, rows, skipped);
        } else {
            parseXlsx(file, rows, skipped);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            result.put("round", null);
            result.put("rowsImported", 0);
            result.put("distinctAbstractSql", 0);
            result.put("skipped", skipped[0]);
            log.info("[slow-sql-import] env={} 无有效行 skipped={}", env, skipped[0]);
            return result;
        }

        String round = SlowSqlParser.nextRound(LocalDate.now(), dao.listAllRounds());
        dao.batchInsert(rows, round, LocalDateTime.now());

        // 继承白名单：按本批 distinct abstract_hash
        Set<String> hashes = new LinkedHashSet<>();
        for (ParsedSlowSqlRow r : rows) hashes.add(r.abstractHash);
        WhitelistApplicationService wl = whitelistServiceProvider.getIfAvailable();
        if (wl != null) {
            for (String h : hashes) {
                try {
                    wl.inheritOnSlowSqlImport(h);
                } catch (Exception e) {
                    log.warn("[slow-sql-import] 继承白名单失败 hash={}: {}", h, e.getMessage());
                }
            }
        }

        result.put("round", round);
        result.put("rowsImported", rows.size());
        result.put("distinctAbstractSql", hashes.size());
        result.put("skipped", skipped[0]);
        log.info("[slow-sql-import] env={} 结果={}", env, result);
        return result;
    }

    private void parseXlsx(MultipartFile file, List<ParsedSlowSqlRow> out, int[] skipped) throws IOException {
        try (InputStream raw = file.getInputStream();
             BufferedInputStream is = new BufferedInputStream(raw);
             Workbook wb = WorkbookFactory.create(is)) {
            if (wb.getNumberOfSheets() == 0) return;
            Sheet sheet = wb.getSheetAt(0);
            boolean header = true;
            for (Row row : sheet) {
                if (header) { header = false; continue; }   // 跳首行表头
                String domain = cell(row, COL_DOMAIN);
                String cost = cell(row, COL_COST);
                String abs = cell(row, COL_ABSTRACT);
                String params = cell(row, COL_PARAMS);
                addRow(out, skipped, domain, cost, abs, params);
            }
        }
    }

    private void parseCsv(MultipartFile file, List<ParsedSlowSqlRow> out, int[] skipped) throws IOException {
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
                    String domain = rec.size() > COL_DOMAIN ? rec.get(COL_DOMAIN) : "";
                    String cost = rec.size() > COL_COST ? rec.get(COL_COST) : "";
                    String abs = rec.size() > COL_ABSTRACT ? rec.get(COL_ABSTRACT) : "";
                    String params = rec.size() > COL_PARAMS ? rec.get(COL_PARAMS) : "";
                    addRow(out, skipped, domain, cost, abs, params);
                }
            }
        }
    }

    /** 组装一行；第0列 serviceName 派生 领域+类型；abstract_sql 空或超长 → 跳过计数。 */
    private void addRow(List<ParsedSlowSqlRow> out, int[] skipped,
                        String serviceName, String cost, String abs, String params) {
        String abstractSql = abs == null ? "" : abs.trim();
        if (abstractSql.isEmpty() || abstractSql.length() > ABSTRACT_SQL_MAX) {
            skipped[0]++;
            return;
        }
        String svc = serviceName == null ? "" : serviceName.trim();
        long ms = SlowSqlParser.parseCostMs(cost);
        String hash = SlowSqlParser.sha256Hex(abstractSql);
        out.add(new ParsedSlowSqlRow(
                svc, SlowSqlParser.domainOf(svc), SlowSqlParser.bizTypeOf(svc),
                ms, cost == null ? null : cost.trim(),
                abstractSql, hash, params == null ? null : params.trim()));
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
