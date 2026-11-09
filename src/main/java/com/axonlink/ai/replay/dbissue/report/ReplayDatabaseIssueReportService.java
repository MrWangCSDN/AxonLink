package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.ai.replay.dbissue.ReplayDatabaseIssueExcelParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.axonlink.ai.replay.dbissue.report.ReplayDatabaseIssueReport.Row;

/** Builds a read-only, full-batch report from imported facts and current classifications. */
@Service
public class ReplayDatabaseIssueReportService {
    private static final List<String> STANDARD_DOMAINS = List.of("公共组", "存款组", "结算组", "贷款组");
    private final ReplayDatabaseIssueReportDao dao;
    private final ObjectMapper json = new ObjectMapper();

    public ReplayDatabaseIssueReportService(ReplayDatabaseIssueReportDao dao) {
        this.dao = dao;
    }

    public List<String> batchNames() {
        return dao.readConsistently(() -> List.copyOf(dao.listBatchNames()));
    }

    public ReplayDatabaseIssueReport build(String batchNo) {
        String requested = normalized(batchNo);
        if (!requested.isEmpty()) ReplayDatabaseIssueExcelParser.batchDate(requested);
        return dao.readConsistently(() -> buildInside(requested));
    }

    private ReplayDatabaseIssueReport buildInside(String requested) {
        String batch = requested.isEmpty() ? dao.latestBatchName() : requested;
        if (!requested.isEmpty() && !dao.batchExists(requested)) throw new IllegalArgumentException("所选批次不存在，请刷新报表批次后重试");
        var registration = batch == null ? null : dao.registrationSnapshot(batch);
        TreeSet<String> types = new TreeSet<>();
        dao.currentIssueTypes().stream().map(ReplayDatabaseIssueReportService::normalized).filter(value -> !value.isEmpty()).forEach(types::add);
        Map<String, DomainTotals> domains = new LinkedHashMap<>();
        STANDARD_DOMAINS.forEach(name -> domains.put(name, new DomainTotals(name)));
        Map<String, TableTotals> tables = new LinkedHashMap<>();
        boolean[] legacy = {false};
        boolean[] excludedMember = {false};
        if (batch != null) {
            dao.forEachImportedIssue(batch, input -> {
                if ("不参与比对".equals(input.issueStatus())) {
                    excludedMember[0] = true;
                    return;
                }
                String domainName = normalized(registration == null ? input.domain() : registration.table(input.tableName()).domain());
                if (domainName.isEmpty()) domainName = "未分配领域";
                DomainTotals domain = domains.computeIfAbsent(domainName, DomainTotals::new);
                JsonNode snapshot = readSnapshot(input);
                long difference = number(snapshot, "differenceCount", input);
                String type = normalized(input.issueType());
                if (!type.isEmpty()) types.add(type);
                if (difference > 0) {
                    domain.issues = add(domain.issues, 1);
                    if (type.isEmpty()) domain.unprocessed = add(domain.unprocessed, 1);
                    else domain.types.merge(type, 1L, ReplayDatabaseIssueReportService::add);
                }
                TableTotals table = tables.computeIfAbsent(input.tableName(), ignored -> new TableTotals(domain.name));
                if ("表级".equals(input.issueLevel())) {
                    table.hasTableInput = true;
                    table.total528 = Math.max(table.total528, number(snapshot, "total528", input));
                    table.totalCcbs = Math.max(table.totalCcbs, number(snapshot, "totalCcbs", input));
                    table.only528 = Math.max(table.only528, number(snapshot, "only528Count", input));
                    table.onlyCcbs = Math.max(table.onlyCcbs, number(snapshot, "onlyCcbsCount", input));
                    table.tableDifference = Math.max(table.tableDifference, difference);
                    if (snapshot.hasNonNull("sameKeyDifferentRowCount")) {
                        table.sameKeyDifferentRows = Math.max(table.sameKeyDifferentRows,
                                number(snapshot, "sameKeyDifferentRowCount", input));
                    } else table.sameKeyDifferentRowsUnknown = true;
                } else if ("字段级".equals(input.issueLevel())) {
                    table.fieldDifference = add(table.fieldDifference, difference);
                    domain.fieldDifference = add(domain.fieldDifference, difference);
                } else {
                    legacy[0] = true;
                }
            });
        }
        // An existing batch with no retained inputs may be an old all-consistent import, not proof of zero compared tables.
        boolean tableMetricsComplete = batch == null
                || (tables.isEmpty() ? excludedMember[0] : tables.values().stream().allMatch(table -> table.hasTableInput));
        if (tables.isEmpty() && !tableMetricsComplete) {
            domains.values().forEach(domain -> domain.sameKeyDifferentRowsUnknown = true);
        }
        List<String> participating = tables.entrySet().stream().filter(entry -> entry.getValue().hasTableInput).map(Map.Entry::getKey).toList();
        Map<String, Long> configuredFields = registration == null ? dao.configuredFieldCounts(participating) : Map.of();
        tables.forEach((name, table) -> {
            DomainTotals domain = domains.get(table.domain);
            if (!table.hasTableInput) {
                domain.sameKeyDifferentRowsUnknown = true;
                return;
            }
            domain.total528 = add(domain.total528, table.total528);
            domain.totalCcbs = add(domain.totalCcbs, table.totalCcbs);
            domain.only528 = add(domain.only528, table.only528);
            domain.onlyCcbs = add(domain.onlyCcbs, table.onlyCcbs);
            domain.tables = add(domain.tables, 1);
            if (table.tableDifference == 0 && table.fieldDifference == 0 && table.sameKeyDifferentRows == 0) {
                domain.identical = add(domain.identical, 1);
            }
            domain.sameKeyDifferentRows = add(domain.sameKeyDifferentRows, table.sameKeyDifferentRows);
            domain.sameKeyDifferentRowsUnknown |= table.sameKeyDifferentRowsUnknown;
            Long count = registration == null ? configuredFields.get(name) : Long.valueOf(registration.table(name).fieldCount());
            if (count == null) domain.fieldsUnknown = true;
            else domain.fields = add(domain.fields, count);
        });
        List<String> orderedDomains = new ArrayList<>(STANDARD_DOMAINS);
        domains.keySet().stream().filter(name -> !STANDARD_DOMAINS.contains(name)).sorted().forEach(orderedDomains::add);
        List<Row> rows = new ArrayList<>();
        DomainTotals total = new DomainTotals("合计");
        for (String name : orderedDomains) {
            DomainTotals current = domains.get(name);
            rows.add(current.row(types));
            total.include(current);
        }
        List<String> notes = new ArrayList<>(List.of(
                "统计批次：" + (batch == null ? "暂无已导入批次" : batch) + "；空批次参数取业务日期最新的已导入批次。",
                "汇总所选批次问题，不受列表分页、排序及其他列筛选影响。当前问题状态为不参与比对的记录排除在全部指标之外，历史批次使用相同规则。",
                "指标使用该批次最后一次导入仍实际出现的原始快照；旧数据没有该批次导入历史时使用当前批次快照。",
                "问题总数为差异笔数大于零的表级及字段级问题数量，包含目前已修复的问题；问题类型使用数据库当前实际值。",
                "未处理表示当前问题类型为空；排查进度=(问题总数-未处理)/问题总数，合计按总问题数计算，空数据为0%。",
                "主键相同、字段不同取所选批次表明细保存的原始数量，每张参与表累计一次，再按领域汇总。字段级差异数量用于字段问题统计。",
                registration == null
                        ? "字段总数取参与表当前唯一有效登记配置，不代表历史批次原始字段数；缺登记或多份有效登记时留空。"
                        : "登记历史版本为 " + registration.versionNo() + "。字段总数取本批次参与表在该版本中的字段数量，零字段计为0。",
                registration == null
                        ? "领域取当前表人员清单；无领域映射的表保留在未分配领域。"
                        : "领域取本批次关联的登记历史版本，与参与表字段总数使用相同快照。"));
        if (total.sameKeyDifferentRowsUnknown) {
            notes.add("部分参与表未保存主键相同、字段不同的原始数量，对应领域及合计显示为—。需从原始Excel补回该批次每表数量后重新生成。历史批次使用自己的导入快照。普通重新导入仍会执行现有的问题状态更新规则。");
        }
        if (!tableMetricsComplete) notes.add("此批次含旧格式或遗留问题，缺少完整表级原始明细；528/ccbs总量、两侧独有数、参与表数、字段总数及一致表数无法可靠还原，在汇总表显示为—，不以0代替未知值。字段差异和问题分类仍按已保存输入统计。");
        else if (legacy[0]) notes.add("此批次含旧格式问题级别（仅528/仅ccbs等），未知级别未作为字段级差异累计；表级指标仅使用完整表级原始明细。");
        return new ReplayDatabaseIssueReport(batch, new ArrayList<>(types), rows, total.row(types), notes,
                tableMetricsComplete, registration == null ? null : registration.versionNo());
    }

    private JsonNode readSnapshot(ReplayDatabaseIssueReportDao.ImportedIssue input) {
        try {
            JsonNode node = json.readTree(input.rawSnapshot());
            if (node == null || !node.isObject()) throw invalidSnapshot(input);
            return node;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidSnapshot(input);
        }
    }

    private long number(JsonNode snapshot, String field, ReplayDatabaseIssueReportDao.ImportedIssue input) {
        JsonNode value = snapshot.get(field);
        if (value == null || value.isNull()) return 0;
        try {
            if (!value.isNumber() && !value.isTextual()) throw new ArithmeticException();
            long result = new BigDecimal(value.asText()).longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalidSnapshot(input);
        }
    }

    private IllegalArgumentException invalidSnapshot(ReplayDatabaseIssueReportDao.ImportedIssue input) {
        return new IllegalArgumentException("报表数据快照无效：问题 " + input.id() + "（表 " + input.tableName() + "）");
    }

    private static String normalized(String value) { return value == null ? "" : value.strip(); }
    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("报表汇总数值超出整数范围，请检查导入数据"); }
    }

    private static final class TableTotals {
        final String domain;
        boolean hasTableInput;
        long total528, totalCcbs, only528, onlyCcbs, tableDifference, fieldDifference;
        long sameKeyDifferentRows;
        boolean sameKeyDifferentRowsUnknown;
        TableTotals(String domain) { this.domain = domain; }
    }

    private static final class DomainTotals {
        final String name;
        long total528, totalCcbs, only528, onlyCcbs, fieldDifference, tables, fields, identical, issues, unprocessed;
        boolean fieldsUnknown;
        long sameKeyDifferentRows;
        boolean sameKeyDifferentRowsUnknown;
        final Map<String, Long> types = new HashMap<>();
        DomainTotals(String name) { this.name = name; }
        void include(DomainTotals other) {
            total528 = add(total528, other.total528); totalCcbs = add(totalCcbs, other.totalCcbs);
            only528 = add(only528, other.only528); onlyCcbs = add(onlyCcbs, other.onlyCcbs);
            fieldDifference = add(fieldDifference, other.fieldDifference); tables = add(tables, other.tables);
            fields = add(fields, other.fields); identical = add(identical, other.identical);
            issues = add(issues, other.issues); unprocessed = add(unprocessed, other.unprocessed);
            fieldsUnknown |= other.fieldsUnknown;
            sameKeyDifferentRows = add(sameKeyDifferentRows, other.sameKeyDifferentRows);
            sameKeyDifferentRowsUnknown |= other.sameKeyDifferentRowsUnknown;
            other.types.forEach((key, value) -> types.merge(key, value, ReplayDatabaseIssueReportService::add));
        }
        Row row(Iterable<String> catalog) {
            Map<String, Long> counts = new LinkedHashMap<>();
            catalog.forEach(type -> counts.put(type, types.getOrDefault(type, 0L)));
            return new Row(name, total528, totalCcbs, only528, onlyCcbs, fieldDifference, tables,
                    fieldsUnknown ? null : fields, identical, issues, unprocessed, counts,
                    sameKeyDifferentRowsUnknown ? null : sameKeyDifferentRows);
        }
    }
}
