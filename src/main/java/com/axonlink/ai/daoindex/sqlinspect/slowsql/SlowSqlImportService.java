package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.service.NsqlIdProjectIndex;
import com.axonlink.ai.daoindex.sqlinspect.service.SlowSqlOptimizeService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 慢SQL导入 v2：5 列 (serviceName / 抽象SQL / 参数 / 耗时 / 来源文件)，
 * 解析时按 <b>(serviceName, 抽象SQL哈希)</b> 内存聚合（最大耗时代表行 + 出现次数），
 * 轮次由调用方输入（同轮先删后插=覆盖），落库前回填 E 列领域与「重复出现轮次」。
 */
@Service
public class SlowSqlImportService {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlImportService.class);

    // v2 列序：A服务名 B抽象SQL C参数 D耗时 E来源文件
    private static final int COL_SERVICE = 0;
    private static final int COL_ABSTRACT = 1;
    private static final int COL_PARAMS = 2;
    private static final int COL_COST = 3;
    private static final int COL_LOCATION = 4;
    private static final int ABSTRACT_SQL_MAX = 60_000;
    private static final int LOCATION_MAX = 512;
    /** 分块入库大小：配合 rewriteBatchedStatements 规避 max_allowed_packet。 */
    private static final int CHUNK_SIZE = 2000;

    private final DiiSlowSqlDao dao;
    private final com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlCollectFilterDao filterDao;
    private final NsqlIdProjectIndex nsqlIndex;
    private final OdbLocationDomainResolver odbResolver;
    private final ObjectProvider<WhitelistApplicationService> whitelistServiceProvider;
    private final ObjectProvider<SlowSqlOptimizeService> optimizeServiceProvider;

    public SlowSqlImportService(DiiSlowSqlDao dao,
                                com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlCollectFilterDao filterDao,
                                NsqlIdProjectIndex nsqlIndex,
                                OdbLocationDomainResolver odbResolver,
                                ObjectProvider<WhitelistApplicationService> whitelistServiceProvider,
                                ObjectProvider<SlowSqlOptimizeService> optimizeServiceProvider) {
        this.dao = dao;
        this.filterDao = filterDao;
        this.nsqlIndex = nsqlIndex;
        this.odbResolver = odbResolver;
        this.whitelistServiceProvider = whitelistServiceProvider;
        this.optimizeServiceProvider = optimizeServiceProvider;
    }

    /**
     * 入口：解析整文件→内存聚合→回填领域/重复轮次→同轮覆盖入库→批量继承白名单。
     *
     * @param round 用户输入轮次（如 20260103-20260107）；非空、≤20 字符、不含逗号
     * @return {round, rawRows, aggregatedRows, repeatHit, skipped}
     */
    public Map<String, Object> importFile(MultipartFile file, String env, String round) throws IOException {
        String r = validateRound(round);

        // ⓪ 采集过滤名单（V23）：抽象SQL 以任一前缀开头（大小写不敏感）→ 不纳入采集
        List<String> filterPrefixes;
        try {
            filterPrefixes = filterDao.listPrefixes();
        } catch (Exception e) {
            log.warn("[slow-sql-import] 读取采集过滤名单失败（按无过滤继续）: {}", e.getMessage());
            filterPrefixes = List.of();
        }

        // ① 解析整文件 → (serviceName + "\n" + hash) → 聚合行。
        //    30 万原始行聚合后通常只剩几千键，Map 内存可控（值只存代表行字段+计数）。
        Map<String, ParsedSlowSqlRow> agg = new LinkedHashMap<>();
        int[] rawRows = {0};
        int[] skipped = {0};
        int[] filtered = {0};
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".csv")) {
            parseCsv(file, agg, rawRows, skipped, filterPrefixes, filtered);
        } else {
            parseXlsx(file, agg, rawRows, skipped, filterPrefixes, filtered);
        }

        // ② 回填领域（按代表行的 E 列）+ 重复出现轮次（与库中所有其他轮次比）
        Map<String, TreeSet<String>> earlier = dao.roundsByKeyExcluding(r);
        int repeatHit = 0;
        for (Map.Entry<String, ParsedSlowSqlRow> e : agg.entrySet()) {
            ParsedSlowSqlRow row = e.getValue();
            row.domain = resolveDomain(row.sourceLocation);
            TreeSet<String> rounds = earlier.get(e.getKey());
            if (rounds != null && !rounds.isEmpty()) {
                row.repeatRounds = String.join(",", rounds);
                repeatHit++;
            }
        }

        // ③ 同轮覆盖：先删后插（分块）
        int deleted = dao.deleteByRound(r);
        LocalDateTime now = LocalDateTime.now();
        List<ParsedSlowSqlRow> buffer = new ArrayList<>(CHUNK_SIZE);
        for (ParsedSlowSqlRow row : agg.values()) {
            buffer.add(row);
            if (buffer.size() >= CHUNK_SIZE) {
                dao.batchInsert(buffer, r, now);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) dao.batchInsert(buffer, r, now);

        // ④ 批量继承白名单（1 次 SELECT + 命中数次 UPDATE），失败不阻断。
        //    v2：白名单粒度=(微服务+抽象SQL)——聚合 Map 的键(svc+"\n"+hash)正好就是继承键
        if (!agg.isEmpty()) {
            WhitelistApplicationService wl = whitelistServiceProvider.getIfAvailable();
            if (wl != null) {
                try {
                    wl.inheritOnSlowSqlImportBatch(agg.keySet());
                } catch (Exception ex) {
                    log.warn("[slow-sql-import] 批量继承白名单失败: {}", ex.getMessage());
                }
            }
        }

        // ⑤ 已优化状态继承 + 跨轮次「优化未生效」检测（仿白名单，失败不阻断）。
        int reappearedHit = 0;
        if (!agg.isEmpty()) {
            SlowSqlOptimizeService opt = optimizeServiceProvider.getIfAvailable();
            if (opt != null) {
                try {
                    reappearedHit = opt.inheritAndDetectReappearOnImport(agg.keySet(), r);
                } catch (Exception ex) {
                    log.warn("[slow-sql-import] 已优化状态继承/检测失败: {}", ex.getMessage());
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("round", r);
        result.put("rawRows", rawRows[0]);
        result.put("aggregatedRows", agg.size());
        result.put("repeatHit", repeatHit);
        result.put("skipped", skipped[0]);
        result.put("filtered", filtered[0]);   // v3：被采集过滤名单排除的行数
        result.put("overwritten", deleted);
        result.put("reappearedHit", reappearedHit);   // 本轮新判「优化未生效」条数
        log.info("[slow-sql-import] env={} 结果={}", env, result);
        return result;
    }

    /** 轮次校验：非空、≤20 字符（列宽）、不含逗号（repeat_rounds 逗号分隔）。 */
    static String validateRound(String round) {
        String r = round == null ? "" : round.trim();
        if (r.isEmpty()) throw new IllegalArgumentException("轮次不能为空（如 20260103-20260107）");
        if (r.length() > 20) throw new IllegalArgumentException("轮次过长（≤20 字符）");
        if (r.contains(",")) throw new IllegalArgumentException("轮次不能包含逗号");
        return r;
    }

    /**
     * E 列 → 中文领域：
     * 无":"→平台；含Entity→odb（{@link OdbLocationDomainResolver} 查模块）；
     * 否则 nsql（sqlsId 走 {@link NsqlIdProjectIndex}）。模块名再映射 dept→存款 等。
     */
    private String resolveDomain(String location) {
        SlowSqlParser.LocationKind kind = SlowSqlParser.locationKindOf(location);
        switch (kind) {
            case PLATFORM:
                return SlowSqlParser.PLATFORM;
            case ODB: {
                String module = null;
                try {
                    module = odbResolver.resolveModule(SlowSqlParser.locationAfterColon(location));
                } catch (Exception e) {
                    log.debug("[slow-sql-import] odb 模块解析异常 loc={}: {}", location, e.getMessage());
                }
                // 口径（2026-06-12）：odb 文件匹配不到模块 → 归「平台」
                if (module == null || module.isBlank()) return SlowSqlParser.PLATFORM;
                return SlowSqlParser.domainOfModule(module);
            }
            case NSQL:
            default: {
                String module = nsqlIndex.lookupProject(SlowSqlParser.nsqlIdOf(location));
                // 口径（2026-06-12）：nsql 文件匹配不到模块（索引回 "other"）→ 归「平台」
                if (module == null || module.isBlank()
                        || NsqlIdProjectIndex.UNKNOWN_PROJECT.equals(module)) {
                    return SlowSqlParser.PLATFORM;
                }
                return SlowSqlParser.domainOfModule(module);
            }
        }
    }

    private void parseXlsx(MultipartFile file, Map<String, ParsedSlowSqlRow> agg,
                           int[] rawRows, int[] skipped,
                           List<String> filterPrefixes, int[] filtered) throws IOException {
        try (InputStream raw = file.getInputStream();
             BufferedInputStream is = new BufferedInputStream(raw);
             Workbook wb = WorkbookFactory.create(is)) {
            if (wb.getNumberOfSheets() == 0) return;
            Sheet sheet = wb.getSheetAt(0);
            boolean header = true;
            for (Row row : sheet) {
                if (header) { header = false; continue; }   // 跳首行表头
                addRow(agg, rawRows, skipped, filterPrefixes, filtered,
                        cell(row, COL_SERVICE), cell(row, COL_ABSTRACT),
                        cell(row, COL_PARAMS), cell(row, COL_COST), cell(row, COL_LOCATION));
            }
        }
    }

    private void parseCsv(MultipartFile file, Map<String, ParsedSlowSqlRow> agg,
                          int[] rawRows, int[] skipped,
                          List<String> filterPrefixes, int[] filtered) throws IOException {
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
                    addRow(agg, rawRows, skipped, filterPrefixes, filtered,
                            get(rec, COL_SERVICE), get(rec, COL_ABSTRACT),
                            get(rec, COL_PARAMS), get(rec, COL_COST), get(rec, COL_LOCATION));
                }
            }
        }
    }

    private static String get(org.apache.commons.csv.CSVRecord rec, int idx) {
        return rec.size() > idx ? rec.get(idx) : "";
    }

    /**
     * 一行喂进聚合 Map：键=(serviceName + "\n" + 抽象SQL哈希)；B 列空/超长 → skipped；
     * 命中采集过滤名单前缀（大小写不敏感）→ filtered，不纳入采集。
     */
    private void addRow(Map<String, ParsedSlowSqlRow> agg, int[] rawRows, int[] skipped,
                        List<String> filterPrefixes, int[] filtered,
                        String serviceName, String abs, String params, String cost, String location) {
        String abstractSql = abs == null ? "" : abs.trim();
        if (abstractSql.isEmpty() || abstractSql.length() > ABSTRACT_SQL_MAX) {
            skipped[0]++;
            return;
        }
        // v3：采集过滤名单——以名单前缀开头的抽象SQL不采集（如 EXPLAIN / SET）
        if (SlowSqlParser.startsWithAnyPrefix(abstractSql, filterPrefixes)) {
            filtered[0]++;
            return;
        }
        rawRows[0]++;
        String svc = serviceName == null ? "" : serviceName.trim();
        long ms = SlowSqlParser.parseCostMs(cost);
        String costRaw = cost == null ? null : cost.trim();
        String p = params == null ? null : params.trim();
        // 新版导出 E 列带 [] 包裹——规范化后入库（"[]"→空→存 null）
        String loc = SlowSqlParser.normalizeLocation(location);
        if (loc.isEmpty()) loc = null;
        if (loc != null && loc.length() > LOCATION_MAX) loc = loc.substring(0, LOCATION_MAX);

        String hash = SlowSqlParser.sha256Hex(abstractSql);
        String key = svc + "\n" + hash;
        ParsedSlowSqlRow exist = agg.get(key);
        if (exist == null) {
            agg.put(key, new ParsedSlowSqlRow(
                    svc, SlowSqlParser.bizTypeOf(svc), abstractSql, hash, ms, costRaw, p, loc));
        } else {
            exist.absorb(ms, costRaw, p, loc);
        }
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
