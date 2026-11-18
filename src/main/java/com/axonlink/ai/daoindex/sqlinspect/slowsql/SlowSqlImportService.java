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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 慢SQL导入：按 (serviceName, 抽象SQL哈希) 合并，取最大耗时代表行。
 * 可选 sssCount 提供已汇总的执行次数，没有该列时每条输入计一次。
 * 轮次由调用方输入（同轮先删后插=覆盖），落库前按来源回填领域与「重复出现轮次」。
 */
@Service
public class SlowSqlImportService {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlImportService.class);

    // v2 默认列序：A服务名 B抽象SQL C参数 D耗时 E来源文件。
    // 兼容新导出：A slowSqlStatement B ServiceName，其余列不变。
    private static final int COL_SERVICE = 0;
    private static final int COL_ABSTRACT = 1;
    private static final int COL_PARAMS = 2;
    private static final int COL_COST = 3;
    private static final int COL_LOCATION = 4;
    private static final int ABSTRACT_SQL_MAX = 60_000;
    private static final int LOCATION_MAX = 512;
    private static final Pattern EMPTY_PARAMETERS = Pattern.compile("\\[\\s*\\]");

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
     * @return 轮次、输入行数、SQL类数、计数模式及导入统计
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
        boolean preAggregated = name.endsWith(".csv")
                ? parseCsv(file, agg, rawRows, skipped, filterPrefixes, filtered)
                : parseXlsx(file, agg, rawRows, skipped, filterPrefixes, filtered);

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

        // ③ 全文件校验完成后，在同一事务内覆盖该轮次。
        int deleted = dao.replaceRound(r, new ArrayList<>(agg.values()), LocalDateTime.now());

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
        result.put("preAggregated", preAggregated);
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

    private boolean parseXlsx(MultipartFile file, Map<String, ParsedSlowSqlRow> agg,
                           int[] rawRows, int[] skipped,
                           List<String> filterPrefixes, int[] filtered) throws IOException {
        try (InputStream raw = file.getInputStream();
             BufferedInputStream is = new BufferedInputStream(raw);
             Workbook wb = WorkbookFactory.create(is)) {
            if (wb.getNumberOfSheets() == 0) return false;
            Sheet sheet = wb.getSheetAt(0);
            boolean header = true;
            ColumnLayout layout = ColumnLayout.legacy();
            for (Row row : sheet) {
                if (header) {
                    header = false;
                    List<String> headers = new ArrayList<>();
                    for (int index = 0; index < row.getLastCellNum(); index++) headers.add(cell(row, index));
                    layout = columnLayout(headers);
                    continue;
                }
                addRow(agg, rawRows, skipped, filterPrefixes, filtered,
                        cell(row, layout.serviceIndex()), cell(row, layout.abstractIndex()),
                        cell(row, layout.paramsIndex()), cell(row, layout.costIndex()), cell(row, layout.locationIndex()),
                        layout.countIndex() >= 0, cell(row, layout.countIndex()), row.getRowNum() + 1L);
            }
            return layout.countIndex() >= 0;
        }
    }

    private boolean parseCsv(MultipartFile file, Map<String, ParsedSlowSqlRow> agg,
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
                ColumnLayout layout = ColumnLayout.legacy();
                for (org.apache.commons.csv.CSVRecord rec : parser) {
                    if (header) {
                        header = false;
                        List<String> headers = new ArrayList<>();
                        rec.forEach(headers::add);
                        layout = columnLayout(headers);
                        continue;
                    }
                    addRow(agg, rawRows, skipped, filterPrefixes, filtered,
                            get(rec, layout.serviceIndex()), get(rec, layout.abstractIndex()),
                            get(rec, layout.paramsIndex()), get(rec, layout.costIndex()), get(rec, layout.locationIndex()),
                            layout.countIndex() >= 0, get(rec, layout.countIndex()), rec.getRecordNumber());
                }
                return layout.countIndex() >= 0;
            }
        }
    }

    private static String get(org.apache.commons.csv.CSVRecord rec, int idx) {
        return idx >= 0 && rec.size() > idx ? rec.get(idx) : "";
    }

    /**
     * 根据前两列表头识别导出版本。没有明确表头时保留历史行为，按 A=ServiceName、B=SQL 解析。
     * A 表头为空且 B 已标明微服务时，兼容 A=SQL、B=ServiceName。
     */
    static ColumnLayout columnLayout(String first, String second) {
        return columnLayout(Arrays.asList(first, second));
    }

    static ColumnLayout columnLayout(List<String> headers) {
        List<String> names = headers.stream().map(SlowSqlImportService::normalizeHeader).toList();
        String first = names.isEmpty() ? "" : names.get(0);
        String second = names.size() < 2 ? "" : names.get(1);
        ColumnLayout fallback = (isSqlHeader(first) || first.isBlank()) && isServiceHeader(second)
                ? new ColumnLayout(1, 0) : ColumnLayout.legacy();
        int count = names.indexOf("ssscount");
        if (count >= 0 && names.lastIndexOf("ssscount") != count) {
            throw new IllegalArgumentException("表头中存在重复的 sssCount 列");
        }
        ColumnLayout layout = new ColumnLayout(
                headerIndex(names, fallback.serviceIndex(), "servicename", "service", "微服务", "服务名"),
                headerIndex(names, fallback.abstractIndex(), "slowsqlstatement", "abstractsql", "抽象sql", "sql"),
                headerIndex(names, COL_PARAMS, "slowsqlparameter", "slowsqlparameters", "slowsqlparams", "execparams", "params", "执行参数", "参数"),
                headerIndex(names, COL_COST, "slowsqltimecost", "timecost", "timecostms", "cost", "执行耗时", "耗时", "最大执行耗时"),
                headerIndex(names, COL_LOCATION, "slowsqllocation", "sourcelocation", "location", "来源文件", "来源"), count);
        if (count >= 0 && java.util.stream.IntStream.of(layout.serviceIndex(), layout.abstractIndex(),
                layout.paramsIndex(), layout.costIndex(), layout.locationIndex(), count).distinct().count() != 6) {
            throw new IllegalArgumentException("检测到 sssCount，请为微服务、SQL、参数、耗时和来源提供可识别的表头");
        }
        return layout;
    }

    private static int headerIndex(List<String> headers, int fallback, String... aliases) {
        for (String alias : aliases) {
            int index = headers.indexOf(alias);
            if (index >= 0) return index;
        }
        return fallback;
    }

    private static boolean isSqlHeader(String header) {
        return "slowsqlstatement".equals(header)
                || "abstractsql".equals(header)
                || "抽象sql".equals(header)
                || "sql".equals(header);
    }

    private static boolean isServiceHeader(String header) {
        return "servicename".equals(header)
                || "service".equals(header)
                || "微服务".equals(header)
                || "服务名".equals(header);
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.replace("\uFEFF", "").trim().replace("_", "")
                .replace("-", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    record ColumnLayout(int serviceIndex, int abstractIndex, int paramsIndex, int costIndex,
                        int locationIndex, int countIndex) {
        ColumnLayout(int serviceIndex, int abstractIndex) {
            this(serviceIndex, abstractIndex, COL_PARAMS, COL_COST, COL_LOCATION, -1);
        }
        static ColumnLayout legacy() {
            return new ColumnLayout(COL_SERVICE, COL_ABSTRACT);
        }
    }

    /**
     * 一行喂进聚合 Map：键=(serviceName + "\n" + 抽象SQL哈希)；B 列空/超长 → skipped；
     * 命中采集过滤名单前缀（大小写不敏感）→ filtered，不纳入采集。
     */
    private void addRow(Map<String, ParsedSlowSqlRow> agg, int[] rawRows, int[] skipped,
                        List<String> filterPrefixes, int[] filtered,
                        String serviceName, String abs, String params, String cost, String location,
                        boolean preAggregated, String count, long rowNumber) {
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
        int executions = preAggregated ? executionCount(count, rowNumber) : 1;
        String svc = serviceName == null ? "" : serviceName.trim();
        long ms = SlowSqlParser.parseCostMs(cost);
        String costRaw = cost == null ? null : cost.trim();
        String p = params == null ? null : params.trim();
        if (p != null && (p.isEmpty() || EMPTY_PARAMETERS.matcher(p).matches())) p = null;
        // 新版导出 E 列带 [] 包裹——规范化后入库（"[]"→空→存 null）
        String loc = SlowSqlParser.normalizeLocation(location);
        if (loc.isEmpty()) loc = null;
        if (loc != null && loc.length() > LOCATION_MAX) loc = loc.substring(0, LOCATION_MAX);

        String hash = SlowSqlParser.sha256Hex(abstractSql);
        String key = svc + "\n" + hash;
        ParsedSlowSqlRow exist = agg.get(key);
        if (exist == null) {
            agg.put(key, new ParsedSlowSqlRow(
                    svc, SlowSqlParser.bizTypeOf(svc), abstractSql, hash, ms, costRaw, p, loc, executions));
        } else {
            try {
                exist.absorb(ms, costRaw, p, loc, executions);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("第" + rowNumber + "行 " + (preAggregated ? "sssCount" : "执行次数")
                        + " 累计值超过2147483647");
            }
        }
    }

    private static int executionCount(String value, long rowNumber) {
        try {
            int count = new BigDecimal(value == null ? "" : value.trim()).intValueExact();
            if (count >= 0) return count;
        } catch (NumberFormatException | ArithmeticException exception) {
            // 统一给出输入行号，避免把非法的预聚合次数静默改成一次。
        }
        throw new IllegalArgumentException("第" + rowNumber + "行 sssCount 必须是0至2147483647的整数");
    }

    private String cell(Row row, int idx) {
        if (idx < 0) return null;
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
