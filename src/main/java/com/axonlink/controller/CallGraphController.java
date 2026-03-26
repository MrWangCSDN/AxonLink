package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.service.CallGraphScanner;
import com.axonlink.service.CallGraphService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 方法调用关系网 REST 接口
 *
 * POST /api/callgraph/scan          全量扫描（同步，返回统计）
 * POST /api/callgraph/scan/async    全量扫描（异步，立即返回）
 * GET  /api/callgraph/scan/progress 查询扫描进度
 * POST /api/callgraph/refresh       增量刷新（传入变更文件列表）
 *
 * GET  /api/callgraph/impact?class=DpAccLimitBcsImpl&depth=8
 *      反向影响面：被哪些 PBS 调用，影响哪些交易
 *
 * GET  /api/callgraph/downstream?sig=DpAccLimitBcsImpl&depth=5
 *      正向调用链展开
 *
 * GET  /api/callgraph/sysutil?class=IoDpAccLimitSvtp
 *      查询通过 SysUtil 调用该接口/类的所有调用方
 *
 * GET  /api/callgraph/stats
 *      统计概览（方法数、边数、热点构件）
 */
@RestController
@RequestMapping("/api/callgraph")
public class CallGraphController {

    private final CallGraphScanner  scanner;
    private final CallGraphService  service;

    public CallGraphController(CallGraphScanner scanner, CallGraphService service) {
        this.scanner = scanner;
        this.service = service;
    }

    /** 全量扫描（同步）—— 扫描 4000+ 文件约 20-35 秒 */
    @PostMapping("/scan")
    public R<Map<String, Object>> fullScan() {
        return R.ok(scanner.fullScanSync());
    }

    /** 全量扫描（异步）—— 立即返回，通过 /progress 轮询进度 */
    @PostMapping("/scan/async")
    public R<String> fullScanAsync() {
        scanner.startFullScan();
        return R.ok("扫描已启动，通过 GET /api/callgraph/scan/progress 查询进度");
    }

    /** 查询扫描进度 */
    @GetMapping("/scan/progress")
    public R<Map<String, Object>> scanProgress() {
        return R.ok(scanner.getProgress());
    }

    /**
     * 增量刷新
     * Body: { "files": ["/path/to/DpAccLimitBcsImpl.java", ...] }
     */
    @PostMapping("/refresh")
    public R<Map<String, Object>> incrementalRefresh(@RequestBody Map<String, List<String>> body) {
        List<String> files = body.getOrDefault("files", List.of());
        if (files.isEmpty()) return R.fail("files 参数不能为空");
        return R.ok(scanner.incrementalRefresh(files));
    }

    /**
     * 反向影响面分析
     * @param cls   类名关键词，如 DpAccLimitBcsImpl 或 DpAccLimit
     * @param depth 向上追溯的最大深度，默认 8
     */
    @GetMapping("/impact")
    public R<Map<String, Object>> impact(
            @RequestParam("class") String cls,
            @RequestParam(value = "depth", defaultValue = "8") int depth) {
        return R.ok(service.analyzeImpact(cls, depth));
    }

    /**
     * 正向调用链展开
     * @param sig   方法签名关键词
     * @param depth 向下追踪最大深度，默认 5
     */
    @GetMapping("/downstream")
    public R<Map<String, Object>> downstream(
            @RequestParam("sig") String sig,
            @RequestParam(value = "depth", defaultValue = "5") int depth) {
        return R.ok(service.traceDownstream(sig, depth));
    }

    /**
     * 查询通过 SysUtil 调用某接口/类的所有调用方
     * @param cls 类名关键词
     */
    @GetMapping("/sysutil")
    public R<Object> sysutilCallers(@RequestParam("class") String cls) {
        return R.ok(service.querySysUtilCallers(cls));
    }

    /**
     * 影响图谱汇总（前端服务/构件卡片按钮使用）
     * @param fqn       全限定类名（从 NODE_FILE_REGISTRY 取）
     * @param className 简单类名（备用）
     * @param limit     截断阈值，默认 10
     */
    @GetMapping("/impact-summary")
    public R<Map<String, Object>> impactSummary(
            @RequestParam(required = false, defaultValue = "") String fqn,
            @RequestParam(required = false, defaultValue = "") String className,
            @RequestParam(required = false, defaultValue = "") String nodeCode,
            @RequestParam(required = false, defaultValue = "") String nodeType,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        if (fqn.isBlank() && className.isBlank() && nodeCode.isBlank()) return R.fail("fqn/className/nodeCode 不能同时为空");
        return R.ok(service.getImpactSummary(fqn, className, nodeCode, nodeType, limit));
    }

    /**
     * Excel 导出（完整数据，不截断）
     * GET /api/callgraph/excel?fqn=xxx&className=xxx&nodeName=xxx
     */
    @GetMapping("/excel")
    public void exportExcel(
            @RequestParam(required = false, defaultValue = "") String fqn,
            @RequestParam(required = false, defaultValue = "") String className,
            @RequestParam(required = false, defaultValue = "影响分析") String nodeName,
            HttpServletResponse response) throws IOException {

        Map<String, Object> data = service.getImpactFull(fqn, className, "", "");

        String filename = URLEncoder.encode(nodeName + "_影响分析.xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // ── 样式 ──────────────────────────────────────────────────────────
            CellStyle titleStyle = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true); titleFont.setFontHeightInPoints((short)12);
            titleStyle.setFont(titleFont);
            titleStyle.setFillForegroundColor(IndexedColors.CORNFLOWER_BLUE.getIndex());
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont(); headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle bodyStyle = wb.createCellStyle();
            bodyStyle.setBorderBottom(BorderStyle.HAIR);

            // ── Sheet1：上游影响（服务 + 交易） ──────────────────────────────
            Sheet upSheet = wb.createSheet("上游影响");
            int rowIdx = 0;

            // 标题行
            Row titleRow = upSheet.createRow(rowIdx++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("【" + nodeName + "】上游影响分析");
            titleCell.setCellStyle(titleStyle);
            upSheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            rowIdx++;

            // 服务区
            Row svcHeader = upSheet.createRow(rowIdx++);
            writeRow(svcHeader, headerStyle, "上游服务（APS层）", "服务编码", "服务名称", "");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> upSvcs = (List<Map<String,Object>>)
                    ((Map<?,?>)data.get("upstream")).get("services");
            for (Map<String, Object> svc : upSvcs) {
                Row r = upSheet.createRow(rowIdx++);
                writeRow(r, bodyStyle, "", str(svc,"service_code"), str(svc,"service_name"), "");
            }
            rowIdx++;

            // 交易区
            Row txHeader = upSheet.createRow(rowIdx++);
            writeRow(txHeader, headerStyle, "影响交易", "交易编码", "交易名称", "领域");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> txList = (List<Map<String,Object>>)
                    ((Map<?,?>)data.get("upstream")).get("transactions");
            for (Map<String, Object> tx : txList) {
                Row r = upSheet.createRow(rowIdx++);
                writeRow(r, bodyStyle, "", str(tx,"tx_code"), str(tx,"tx_name"), str(tx,"domain_name"));
            }
            upSheet.autoSizeColumn(0); upSheet.autoSizeColumn(1);
            upSheet.autoSizeColumn(2); upSheet.autoSizeColumn(3);

            // ── Sheet2：下游调用（构件） ──────────────────────────────────────
            Sheet downSheet = wb.createSheet("下游调用");
            rowIdx = 0;
            Row downTitle = downSheet.createRow(rowIdx++);
            Cell downTitleCell = downTitle.createCell(0);
            downTitleCell.setCellValue("【" + nodeName + "】下游调用构件");
            downTitleCell.setCellStyle(titleStyle);
            downSheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            rowIdx++;

            Row downHeader = downSheet.createRow(rowIdx++);
            writeRow(downHeader, headerStyle, "构件类名", "全限定名", "层次");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comps = (List<Map<String,Object>>)
                    ((Map<?,?>)data.get("downstream")).get("components");
            for (Map<String, Object> comp : comps) {
                Row r = downSheet.createRow(rowIdx++);
                writeRow(r, bodyStyle, str(comp,"class_name"), str(comp,"class_fqn"), str(comp,"layer"));
            }
            downSheet.autoSizeColumn(0); downSheet.autoSizeColumn(1); downSheet.autoSizeColumn(2);

            wb.write(response.getOutputStream());
        }
    }

    private void writeRow(Row row, CellStyle style, String... values) {
        for (int i = 0; i < values.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(values[i] == null ? "" : values[i]);
            c.setCellStyle(style);
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    /** 统计概览 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(service.getStats());
    }
}
