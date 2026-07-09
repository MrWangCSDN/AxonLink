package com.axonlink.ai.daoindex.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.slowsql.SlowSqlImportService;
import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 慢SQL维度分析接口（V20）。前缀 {@code /api/ai/dao-index/slow-sql}。
 * 导入受口令保护(X-DII-Trigger-Token)；列表/导出只读。
 */
@RestController
@RequestMapping("/api/ai/dao-index/slow-sql")
public class SlowSqlController {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlController.class);

    private final SlowSqlImportService importService;
    private final DiiSlowSqlDao dao;
    private final com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlCollectFilterDao filterDao;
    private final DaoIndexAnalysisProperties props;
    private final com.axonlink.ai.daoindex.sqlinspect.service.SlowSqlOptimizeService optimizeService;
    private final com.axonlink.security.UserPrincipalResolver userResolver;

    public SlowSqlController(SlowSqlImportService importService, DiiSlowSqlDao dao,
                             com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlCollectFilterDao filterDao,
                             DaoIndexAnalysisProperties props,
                             com.axonlink.ai.daoindex.sqlinspect.service.SlowSqlOptimizeService optimizeService,
                             com.axonlink.security.UserPrincipalResolver userResolver) {
        this.importService = importService;
        this.dao = dao;
        this.filterDao = filterDao;
        this.props = props;
        this.optimizeService = optimizeService;
        this.userResolver = userResolver;
    }

    // ── 导入（口令）──
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<R<Map<String, Object>>> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "round") String round,
            @RequestParam(value = "env", required = false) String env,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request);
        if (denied != null) return denied;
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(R.fail("文件为空"));
        }
        String fname = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!fname.endsWith(".xlsx") && !fname.endsWith(".xls") && !fname.endsWith(".csv")) {
            return ResponseEntity.badRequest().body(R.fail("仅支持 .xlsx / .xls / .csv"));
        }
        try {
            // v2：轮次由前端输入（如 20260103-20260107），同轮先删后插=覆盖
            Map<String, Object> stats = importService.importFile(file, env, round);
            return ResponseEntity.ok(R.ok(stats));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
        } catch (Exception e) {
            log.error("[slow-sql-import] 失败 file={}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body(R.<Map<String, Object>>fail("导入失败：" + e.getMessage()));
        }
    }

    // ── 聚合列表（只读）──
    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String whitelistStatus,
            @RequestParam(required = false) String optimizeStatus,
            @RequestParam(required = false) String round,
            @RequestParam(required = false) String approverUser) {
        List<Map<String, Object>> items = dao.listAggregated(domain, bizType, keyword, whitelistStatus, optimizeStatus, round, approverUser, limit, offset);
        long total = dao.countAggregated(domain, bizType, keyword, whitelistStatus, optimizeStatus, round, approverUser);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("items", items);
        return R.ok(payload);
    }

    /** 去重 domain（中文领域）下拉。 */
    @GetMapping("/domains")
    public R<List<String>> domains() {
        return R.ok(dao.listDistinctDomains());
    }

    /** 去重 biz_type（中文类型）下拉。 */
    @GetMapping("/biz-types")
    public R<List<String>> bizTypes() {
        return R.ok(dao.listDistinctBizTypes());
    }

    /** v2：全部轮次（升序）→ 页面轮次下拉（前端默认选最新=末位）。 */
    @GetMapping("/rounds")
    public R<List<String>> rounds() {
        return R.ok(dao.distinctRoundsSorted());
    }

    /** v4：概览仪表盘——慢SQL按轮次统计（最近 lastN 轮，升序）。 */
    @GetMapping("/round-stats")
    public R<List<Map<String, Object>>> roundStats(@RequestParam(defaultValue = "7") int lastN) {
        return R.ok(dao.aggregateByRound(lastN));
    }

    /** v4：概览仪表盘——慢SQL按领域分布（横向堆叠条；{domain,total,wl_applying,wl_approved}）。 */
    @GetMapping("/domain-stats")
    public R<List<Map<String, Object>>> domainStats() {
        return R.ok(dao.aggregateByDomain());
    }

    // ── 标记「已优化」（无口令、无审批；工号/姓名取当前登录用户，需填优化内容）──

    @PostMapping("/optimize")
    public R<Map<String, Object>> markOptimized(@RequestBody Map<String, String> body,
                                                HttpServletRequest request) {
        String serviceName = body.get("serviceName");
        String abstractHash = body.get("abstractHash");
        String note = body.get("note");
        if (serviceName == null || serviceName.isBlank() || abstractHash == null || abstractHash.isBlank()) {
            return R.fail("serviceName / abstractHash 不能为空");
        }
        if (note == null || note.trim().isEmpty()) {
            return R.fail("优化内容不能为空");
        }
        note = note.trim();
        if (note.length() > 200) {
            return R.fail("优化内容不能超过 200 字");
        }
        // 工号/姓名取当前登录用户：优先解析 sys_user（工号 empNo + 姓名 realName），取不到回退登录名。
        String empNo = request.getRemoteUser();
        String realName = null;
        try {
            com.axonlink.security.UserPrincipalResolver.Resolved r = userResolver.resolve(request);
            if (r != null && r.user != null) {
                empNo = firstNonBlank2(r.user.getEmpNo(), r.principal, empNo);
                realName = r.user.getRealName();
            }
        } catch (Exception e) {
            log.warn("[slow-sql-optimize] 解析优化人失败，回退登录名 {}: {}", empNo, e.getMessage());
        }
        try {
            String r0 = optimizeService.mark(serviceName.trim(), abstractHash.trim(), empNo, realName, note);
            return R.ok(Map.of("status", "OPTIMIZED", "optimizedRound", r0 == null ? "" : r0));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    private static String firstNonBlank2(String a, String b, String c) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return c;
    }

    /** 撤销优化（互斥出口：生效中的 SQL 想走白名单须先撤销；记撤销人工号/姓名+时间，路线留痕）。 */
    @PostMapping("/optimize/revoke")
    public R<Map<String, Object>> revokeOptimized(@RequestBody Map<String, String> body,
                                                  HttpServletRequest request) {
        String serviceName = body.get("serviceName");
        String abstractHash = body.get("abstractHash");
        if (serviceName == null || serviceName.isBlank() || abstractHash == null || abstractHash.isBlank()) {
            return R.fail("serviceName / abstractHash 不能为空");
        }
        String empNo = request.getRemoteUser();
        String realName = null;
        try {
            com.axonlink.security.UserPrincipalResolver.Resolved r = userResolver.resolve(request);
            if (r != null && r.user != null) {
                empNo = firstNonBlank2(r.user.getEmpNo(), r.principal, empNo);
                realName = r.user.getRealName();
            }
        } catch (Exception e) {
            log.warn("[slow-sql-optimize] 解析撤销人失败，回退登录名 {}: {}", empNo, e.getMessage());
        }
        try {
            optimizeService.revoke(serviceName.trim(), abstractHash.trim(), empNo, realName);
            return R.ok(Map.of("status", "NONE"));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    /** 优化路线（悬浮弹层用）：该 (微服务, 抽象SQL) 的全部优化尝试，升序=第1次→最新。 */
    @GetMapping("/optimize/history")
    public R<List<Map<String, Object>>> optimizeHistory(@RequestParam String serviceName,
                                                        @RequestParam String abstractHash) {
        if (serviceName.isBlank() || abstractHash.isBlank()) {
            return R.fail("serviceName / abstractHash 不能为空");
        }
        return R.ok(optimizeService.listHistory(serviceName.trim(), abstractHash.trim()));
    }

    // ── v3：采集过滤名单（抽象SQL 以名单前缀开头 → 导入不纳入采集）──

    /** 名单列表（只读，不需口令）。 */
    @GetMapping("/collect-filters")
    public R<List<Map<String, Object>>> listCollectFilters() {
        return R.ok(filterDao.listAll());
    }

    /** 新增前缀（口令保护，与导入口令一致）。 */
    @PostMapping("/collect-filters")
    public ResponseEntity<R<Map<String, Object>>> addCollectFilter(
            @RequestParam("prefix") String prefix,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request);
        if (denied != null) return denied;
        String p = prefix == null ? "" : prefix.trim();
        if (p.isEmpty()) return ResponseEntity.badRequest().body(R.fail("前缀不能为空"));
        if (p.length() > 64) return ResponseEntity.badRequest().body(R.fail("前缀过长（≤64 字符）"));
        try {
            filterDao.insert(p);
            return ResponseEntity.ok(R.ok(Map.of("prefix", p)));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
        }
    }

    /** 删除条目（口令保护）。 */
    @DeleteMapping("/collect-filters/{id}")
    public ResponseEntity<R<Map<String, Object>>> deleteCollectFilter(
            @PathVariable long id,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request);
        if (denied != null) return denied;
        int n = filterDao.deleteById(id);
        if (n == 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail("未找到 id=" + id));
        return ResponseEntity.ok(R.ok(Map.of("id", id)));
    }

    // ── 导出（v3：与页面筛选联动；列与页面一致；跨分页全量——筛选 100 条分 5 页导出 100 条）──
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String env,
                                         @RequestParam(required = false) String domain,
                                         @RequestParam(required = false) String bizType,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String whitelistStatus,
                                         @RequestParam(required = false) String optimizeStatus,
                                         @RequestParam(required = false) String round) {
        try {
            byte[] bytes = buildWorkbook(domain, bizType, keyword, whitelistStatus, optimizeStatus, round);
            String scope = round == null || round.isBlank() ? "all" : round.trim();
            String fname = "slow-sql-" + scope + ".xlsx";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.attachment().filename(fname, StandardCharsets.UTF_8).build());
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (Exception e) {
            log.error("[slow-sql-export] 失败", e);
            return ResponseEntity.internalServerError().body(("导出失败：" + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * v3 导出：列与页面完全一致——微服务/领域/类型/抽象SQL/最大执行耗时/执行参数/执行次数/
     * 来源文件/轮次/重复出现轮次/白名单状态/优化状态；数据=当前筛选下的全量（跨分页，不 OFFSET）。
     */
    private byte[] buildWorkbook(String domain, String bizType, String keyword,
                                 String whitelistStatus, String optimizeStatus, String round) throws Exception {
        List<Map<String, Object>> rows =
                dao.listAggregatedAll(domain, bizType, keyword, whitelistStatus, optimizeStatus, round, null);

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("慢SQL维度");
            Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            CellStyle hs = wb.createCellStyle();
            hs.setFont(hf);
            hs.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            hs.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            List<String> headers = List.of("微服务", "领域", "类型", "抽象SQL", "最大执行耗时(ms)",
                    "执行参数", "执行次数", "来源文件", "轮次", "重复出现轮次", "白名单状态", "优化状态",
                    "优化人", "优化内容");
            Row hr = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(hs);
            }
            sheet.createFreezePane(0, 1);

            int rowIdx = 1;
            for (Map<String, Object> r : rows) {
                Row er = sheet.createRow(rowIdx++);
                int col = 0;
                er.createCell(col++).setCellValue(str(r.get("service_name")));
                er.createCell(col++).setCellValue(str(r.get("domain")));
                er.createCell(col++).setCellValue(str(r.get("biz_type")));
                er.createCell(col++).setCellValue(str(r.get("abstract_sql")));
                er.createCell(col++).setCellValue(((Number) r.get("max_time_cost_ms")).doubleValue());
                er.createCell(col++).setCellValue(str(r.get("exec_params")));
                er.createCell(col++).setCellValue(((Number) r.get("exec_count")).doubleValue());
                er.createCell(col++).setCellValue(str(r.get("source_location")));
                er.createCell(col++).setCellValue(str(r.get("round")));
                er.createCell(col++).setCellValue(str(r.get("repeat_rounds")));
                er.createCell(col++).setCellValue(wlLabel((String) r.get("whitelist_status")));
                er.createCell(col++).setCellValue(optLabel(r));
                er.createCell(col++).setCellValue(optOperator(r));
                er.createCell(col++).setCellValue(str(r.get("optimize_note")));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 白名单状态中文（与页面一致）。 */
    private static String wlLabel(String s) {
        if (s == null || s.isBlank()) return "未申请";
        switch (s) {
            case "PENDING_L1":  return "待一级";
            case "PENDING_L2":  return "待二级";
            case "APPROVED":    return "已通过";
            case "REJECTED_L1": return "一级退回";
            case "REJECTED_L2": return "二级退回";
            case "CANCELLED":   return "已取消";
            default:            return s;
        }
    }

    /** 优化状态中文（带轮次上下文，与页面一致）。 */
    private static String optLabel(Map<String, Object> r) {
        String s = (String) r.get("optimize_status");
        if (s == null || s.isBlank()) return "未处理";
        String r0 = str(r.get("optimized_round"));
        if ("OPTIMIZED".equals(s)) return "已优化@" + r0;
        if ("REGRESSED".equals(s)) return "优化未生效(" + r0 + "标→" + str(r.get("reappeared_round")) + "现)";
        return s;
    }

    /** 优化人：姓名(工号)；缺一取其一，都无则空。 */
    private static String optOperator(Map<String, Object> r) {
        String name = str(r.get("optimized_by_name"));
        String emp = str(r.get("optimized_by"));
        if (name.isEmpty() && emp.isEmpty()) return "";
        if (name.isEmpty()) return emp;
        if (emp.isEmpty()) return name;
        return name + "(" + emp + ")";
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    /** 口令校验（与 SqlPoolController 同口径）。 */
    private ResponseEntity<R<Map<String, Object>>> checkToken(String token, HttpServletRequest request) {
        String expected = props.getBatchTrigger().getToken();
        if (expected == null || expected.trim().isEmpty()) return null;
        if (token == null || !expected.equals(token)) {
            log.warn("[slow-sql] 口令校验失败 remoteAddr={} hasToken={}", request.getRemoteAddr(), token != null);
            R<Map<String, Object>> body = R.fail("口令错误");
            body.setCode(401);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        return null;
    }
}
