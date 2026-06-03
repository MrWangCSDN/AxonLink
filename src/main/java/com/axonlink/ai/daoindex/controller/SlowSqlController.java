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
    private final DaoIndexAnalysisProperties props;

    public SlowSqlController(SlowSqlImportService importService, DiiSlowSqlDao dao,
                             DaoIndexAnalysisProperties props) {
        this.importService = importService;
        this.dao = dao;
        this.props = props;
    }

    // ── 导入（口令）──
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<R<Map<String, Object>>> importFile(
            @RequestPart("file") MultipartFile file,
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
            Map<String, Object> stats = importService.importFile(file, env);
            return ResponseEntity.ok(R.ok(stats));
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
            @RequestParam(required = false) String round) {
        List<Map<String, Object>> items = dao.listAggregated(domain, bizType, keyword, whitelistStatus, round, limit, offset);
        long total = dao.countAggregated(domain, bizType, keyword, whitelistStatus, round);
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

    // ── 导出（全量透视，不联动筛选）──
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String env) {
        try {
            byte[] bytes = buildWorkbook();
            String fname = "slow-sql-" + (env == null || env.isBlank() ? "all" : env.trim()) + ".xlsx";
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

    /** 透视装配：领域|抽象SQL|执行参数|最大耗时|总出现次数|<每轮次>出现次数。 */
    private byte[] buildWorkbook() throws Exception {
        List<String> rounds = dao.distinctRoundsSorted();
        // hash → (round → cnt)
        Map<String, Map<String, Long>> perRound = new HashMap<>();
        for (Map<String, Object> r : dao.roundCounts()) {
            String h = String.valueOf(r.get("abstract_hash"));
            String rd = String.valueOf(r.get("round"));
            long c = ((Number) r.get("cnt")).longValue();
            perRound.computeIfAbsent(h, k -> new HashMap<>()).put(rd, c);
        }
        // hash → total
        Map<String, Long> totals = new HashMap<>();
        for (Map<String, Object> r : dao.totalCounts()) {
            totals.put(String.valueOf(r.get("abstract_hash")), ((Number) r.get("cnt")).longValue());
        }
        List<Map<String, Object>> reps = dao.representativeRows();

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("慢SQL维度");
            Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            CellStyle hs = wb.createCellStyle();
            hs.setFont(hf);
            hs.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            hs.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            List<String> headers = new ArrayList<>(List.of("服务名", "领域", "类型", "抽象SQL", "执行参数", "最大执行耗时(ms)", "总出现次数"));
            for (String rd : rounds) headers.add(rd + " 出现次数");
            Row hr = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(hs);
            }
            sheet.createFreezePane(0, 1);

            int rowIdx = 1;
            for (Map<String, Object> rep : reps) {
                String h = String.valueOf(rep.get("abstract_hash"));
                Row er = sheet.createRow(rowIdx++);
                int col = 0;
                er.createCell(col++).setCellValue(str(rep.get("service_name")));
                er.createCell(col++).setCellValue(str(rep.get("domain")));
                er.createCell(col++).setCellValue(str(rep.get("biz_type")));
                er.createCell(col++).setCellValue(str(rep.get("abstract_sql")));
                er.createCell(col++).setCellValue(str(rep.get("exec_params")));
                er.createCell(col++).setCellValue(((Number) rep.get("time_cost_ms")).doubleValue());
                er.createCell(col++).setCellValue(totals.getOrDefault(h, 0L));
                Map<String, Long> rc = perRound.getOrDefault(h, Map.of());
                for (String rd : rounds) {
                    er.createCell(col++).setCellValue(rc.getOrDefault(rd, 0L));
                }
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
            log.warn("[slow-sql] 口令校验失败 remoteAddr={} hasToken={}", request.getRemoteAddr(), token != null);
            R<Map<String, Object>> body = R.fail("口令错误");
            body.setCode(401);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        return null;
    }
}
