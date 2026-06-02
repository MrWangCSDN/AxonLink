package com.axonlink.ai.daoindex.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.er.ErRebuildService;
import com.axonlink.ai.daoindex.sqlinspect.er.persistence.ErRelationDao;
import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 表关系 ER 图接口（V19）。
 *
 * <p>路径前缀 {@code /api/ai/dao-index/er}。写接口（rebuild / status）受口令保护，
 * 复用 {@code dao-index-analysis.batch-trigger.token} + 请求头 {@code X-DII-Trigger-Token}。
 */
@RestController
@RequestMapping("/api/ai/dao-index/er")
public class ErController {

    private static final Logger log = LoggerFactory.getLogger(ErController.class);

    private final ErRebuildService rebuildService;
    private final ErRelationDao dao;
    private final DaoIndexAnalysisProperties props;

    public ErController(ErRebuildService rebuildService,
                        ErRelationDao dao,
                        DaoIndexAnalysisProperties props) {
        this.rebuildService = rebuildService;
        this.dao = dao;
        this.props = props;
    }

    // ── 重算（口令保护）──
    @PostMapping("/rebuild")
    public ResponseEntity<R<Map<String, Object>>> rebuild(
            @RequestParam(required = false) String env,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(R.ok(rebuildService.rebuild(env)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(R.<Map<String, Object>>fail(e.getMessage()));
        } catch (Exception e) {
            log.error("[er] rebuild 失败 env={}", env, e);
            return ResponseEntity.internalServerError().body(R.<Map<String, Object>>fail("重算失败：" + e.getMessage()));
        }
    }

    // ── 表搜索（画布选中心表）──
    @GetMapping("/tables")
    public R<List<String>> tables(@RequestParam(required = false) String env,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(dao.listTables(effEnv(env), keyword));
    }

    // ── 子图（聚焦 N 跳 / 或全量）──
    @GetMapping("/graph")
    public R<Map<String, Object>> graph(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String table,
            @RequestParam(required = false, defaultValue = "0") int hops,
            @RequestParam(required = false, defaultValue = "false") boolean full,
            @RequestParam(required = false, defaultValue = "HIGH") String minConfidence) {
        String e = effEnv(env);
        int effHops = hops > 0 ? hops : props.getEr().getDefaultHops();

        List<Map<String, Object>> edgeRows;
        if (full) {
            edgeRows = dao.listAll(e, minConfidence, 2000);
        } else {
            if (table == null || table.isBlank()) {
                return R.ok(emptyGraph());
            }
            edgeRows = dao.subgraph(e, table, effHops, minConfidence);
        }
        return R.ok(buildGraph(edgeRows));
    }

    // ── 人工修正 status（口令保护）──
    @PostMapping("/relations/{id}/status")
    public ResponseEntity<R<Map<String, Object>>> setStatus(
            @PathVariable long id,
            @RequestParam String value,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request);
        if (denied != null) return denied;
        String v = value == null ? "" : value.trim().toUpperCase();
        if (!Set.of("AUTO", "CONFIRMED", "IGNORED").contains(v)) {
            return ResponseEntity.badRequest().body(R.fail("status 仅支持 AUTO/CONFIRMED/IGNORED"));
        }
        int n = dao.setStatus(id, v);
        if (n == 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail("未找到 id=" + id));
        return ResponseEntity.ok(R.ok(Map.of("id", id, "status", v)));
    }

    // ── 导出关系清单 Excel ──
    // v4：导出与页面画布完全一致——传 table 时只导该中心表的 N 跳（默认 1 跳）子图，
    // 同 minConfidence（默认 HIGH）、同样排除 IGNORED，保证「所见即所导」。
    // 不传 table 时退化为全量（仅 API 直连用；前端导出按钮恒带 table）。
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String env,
                                         @RequestParam(required = false) String table,
                                         @RequestParam(required = false, defaultValue = "0") int hops,
                                         @RequestParam(required = false, defaultValue = "HIGH") String minConfidence) {
        String e = effEnv(env);
        try {
            List<Map<String, Object>> rows;
            String scope;
            if (table != null && !table.isBlank()) {
                int effHops = hops > 0 ? hops : props.getEr().getDefaultHops();
                // 与 /graph 同一查询：当前中心表的子图边（已排除 IGNORED、已按 minConfidence 过滤）
                rows = new ArrayList<>(dao.subgraph(e, table.trim(), effHops, minConfidence));
                // Excel 内排序：主表→从表，便于阅读（画布无序，导出排好看）
                rows.sort(Comparator
                        .comparing((Map<String, Object> m) -> str(m.get("from_table")))
                        .thenComparing(m -> str(m.get("to_table"))));
                scope = table.trim().toLowerCase();
            } else {
                rows = dao.listForExport(e, minConfidence);
                scope = "all";
            }
            byte[] bytes = buildWorkbook(rows);
            String fname = "er-relations-" + (e.isEmpty() ? "default" : e) + "-" + scope + ".xlsx";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.attachment().filename(fname, StandardCharsets.UTF_8).build());
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (Exception ex) {
            log.error("[er] 导出失败 env={}", env, ex);
            return ResponseEntity.internalServerError()
                    .body(("导出失败：" + ex.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String effEnv(String env) {
        return env == null ? "" : env.trim();
    }

    private Map<String, Object> emptyGraph() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("nodes", List.of());
        g.put("edges", List.of());
        return g;
    }

    /**
     * 由关系行集合构建 {nodes, edges}。
     * 节点列 = 从边推导：作为 from_table 的 join 列 = 该表的键列（key 角色）；
     * 作为 to_table 的 join 列 = 该表的外键列（fk 角色）。同列两角色都标。
     */
    private Map<String, Object> buildGraph(List<Map<String, Object>> edgeRows) {
        // table → (column → role 集合)  role: "key" / "fk"
        Map<String, Map<String, Set<String>>> nodeCols = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        for (Map<String, Object> r : edgeRows) {
            String from = String.valueOf(r.get("from_table"));
            String to = String.valueOf(r.get("to_table"));
            String joinCols = String.valueOf(r.get("join_columns"));
            List<String> cols = Arrays.stream(joinCols.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();

            nodeCols.computeIfAbsent(from, k -> new LinkedHashMap<>());
            nodeCols.computeIfAbsent(to, k -> new LinkedHashMap<>());
            for (String c : cols) {
                nodeCols.get(from).computeIfAbsent(c, k -> new HashSet<>()).add("key");
                nodeCols.get(to).computeIfAbsent(c, k -> new HashSet<>()).add("fk");
            }

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", r.get("id"));
            edge.put("from", from);
            edge.put("to", to);
            edge.put("joinColumns", cols);
            edge.put("keyType", r.get("key_type"));
            edge.put("confidence", r.get("confidence"));
            edge.put("status", r.get("status"));
            edges.add(edge);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Map<String, Set<String>>> e : nodeCols.entrySet()) {
            List<Map<String, Object>> columns = new ArrayList<>();
            for (Map.Entry<String, Set<String>> c : e.getValue().entrySet()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", c.getKey());
                col.put("isKey", c.getValue().contains("key"));
                col.put("isFk", c.getValue().contains("fk"));
                columns.add(col);
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("table", e.getKey());
            node.put("columns", columns);
            nodes.add(node);
        }

        Map<String, Object> g = new LinkedHashMap<>();
        g.put("nodes", nodes);
        g.put("edges", edges);
        g.put("nodeCount", nodes.size());
        g.put("edgeCount", edges.size());
        return g;
    }

    private byte[] buildWorkbook(List<Map<String, Object>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("表关系清单");
            Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            CellStyle hs = wb.createCellStyle();
            hs.setFont(hf);
            hs.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            hs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            hs.setAlignment(HorizontalAlignment.CENTER);

            String[] headers = {"主表(被引用)", "从表(引用)", "关联列", "键类型", "键列数", "置信度", "状态", "创建时间", "更新时间"};
            int[] widths = {32, 32, 36, 10, 8, 10, 12, 20, 20};
            Row hr = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(hs);
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size()), 0, headers.length - 1));

            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                Row er = sheet.createRow(i + 1);
                int col = 0;
                er.createCell(col++).setCellValue(str(row.get("from_table")));
                er.createCell(col++).setCellValue(str(row.get("to_table")));
                er.createCell(col++).setCellValue(str(row.get("join_columns")));
                er.createCell(col++).setCellValue(str(row.get("key_type")));
                er.createCell(col++).setCellValue(str(row.get("key_col_count")));
                er.createCell(col++).setCellValue(str(row.get("confidence")));
                er.createCell(col++).setCellValue(str(row.get("status")));
                er.createCell(col++).setCellValue(str(row.get("created_at")));
                er.createCell(col++).setCellValue(str(row.get("updated_at")));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    /** 口令校验（与 SqlPoolController 同口径）。 */
    private ResponseEntity<R<Map<String, Object>>> checkToken(String token, HttpServletRequest request) {
        String expected = props.getBatchTrigger().getToken();
        if (expected == null || expected.trim().isEmpty()) return null;
        if (token == null || !expected.equals(token)) {
            log.warn("[er] 口令校验失败 remoteAddr={} hasToken={}", request.getRemoteAddr(), token != null);
            R<Map<String, Object>> body = R.fail("口令错误");
            body.setCode(401);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        return null;
    }
}
