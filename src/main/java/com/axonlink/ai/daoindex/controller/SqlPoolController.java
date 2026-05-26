package com.axonlink.ai.daoindex.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao;
import com.axonlink.ai.daoindex.sqlinspect.service.SqlPoolImportService;
import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 池 / 白名单接口。
 *
 * <p>设计要点：
 * <ul>
 *   <li>导入与白名单写接口都走口令保护（{@code X-DII-Trigger-Token}），与
 *       {@link DaoIndexController#triggerBatch} 共享 {@code dao-index-analysis.batch-trigger.token}</li>
 *   <li>只读接口（列表 / 工程下拉）不要求口令——前端浏览不应被门槛拦截</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/dao-index")
public class SqlPoolController {

    private static final Logger log = LoggerFactory.getLogger(SqlPoolController.class);

    private final SqlPoolImportService importService;
    private final DiiSqlPoolDao poolDao;
    private final DiiAnalysisItemDao itemDao;
    private final DaoIndexAnalysisProperties props;

    public SqlPoolController(SqlPoolImportService importService,
                             DiiSqlPoolDao poolDao,
                             DiiAnalysisItemDao itemDao,
                             DaoIndexAnalysisProperties props) {
        this.importService = importService;
        this.poolDao = poolDao;
        this.itemDao = itemDao;
        this.props = props;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 导入 Excel（受口令保护）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 导入「IndexWarnLog」WARN 行式日志 Excel 到 SQL 池。
     *
     * <p>v2 改动：去掉 {@code projectName} 入参——project_name 由后端按命名 SQL 前缀
     * 反查 {@code NsqlIdProjectIndex} 自动确定（dept-bcc/loan-bcc/sett-bcc/comm-bcc/other）。
     *
     * <p>示例：
     * <pre>{@code
     * curl -X POST 'http://host/api/ai/dao-index/sql-pool/import' \
     *      -H 'X-DII-Trigger-Token: sunline300348' \
     *      -F 'file=@warnlog.xlsx' \
     *      -F 'env=uat'
     * }</pre>
     */
    @PostMapping(value = "/sql-pool/import", consumes = "multipart/form-data")
    public ResponseEntity<R<Map<String, Object>>> importExcel(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "env", required = false) String env,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {

        // 口令校验：与 triggerBatch 同口径，配置为空跳过；非空+不匹配→401
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request, "sql-pool/import");
        if (denied != null) return denied;

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(R.fail("文件为空"));
        }

        try {
            Map<String, Object> stats = importService.importExcel(file, env);
            return ResponseEntity.ok(R.ok(stats));
        } catch (Exception e) {
            log.error("[sql-pool-import] 导入失败 file={}",
                    file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError()
                    .body(R.<Map<String, Object>>fail("导入失败：" + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 列表查询（只读，无口令）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 分页查询 SQL 池。
     *
     * @param project   工程名等值过滤
     * @param whitelist {@code null}=全部；{@code 0}=非白名单；{@code 1}=白名单
     * @param keyword   命名 SQL / SQL 文本模糊
     */
    @GetMapping("/sql-pool")
    public R<Map<String, Object>> listPool(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) Integer whitelist,
            @RequestParam(required = false) String keyword) {
        List<Map<String, Object>> items = poolDao.search(project, whitelist, keyword, limit, offset);
        long total = poolDao.count(project, whitelist, keyword);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("items", items);
        return R.ok(payload);
    }

    /** 列出去重的 project_name，给前端下拉框用。 */
    @GetMapping("/sql-pool/projects")
    public R<List<String>> listProjects() {
        return R.ok(poolDao.listDistinctProjects());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 白名单切换（受口令保护）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 切换池表行白名单状态。
     *
     * <p>示例：
     * <pre>{@code
     * POST /api/ai/dao-index/sql-pool/123/whitelist?value=1
     * Header: X-DII-Trigger-Token: <token>
     * }</pre>
     */
    @PostMapping("/sql-pool/{id}/whitelist")
    public ResponseEntity<R<Map<String, Object>>> setPoolWhitelist(
            @PathVariable long id,
            @RequestParam int value,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request, "sql-pool/whitelist");
        if (denied != null) return denied;

        int affected = poolDao.setWhitelist(id, value);
        if (affected == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(R.fail("未找到 id=" + id));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("isWhitelist", value == 0 ? 0 : 1);
        return ResponseEntity.ok(R.ok(payload));
    }

    /**
     * 切换 item 表（巡检明细）的白名单状态。
     */
    @PostMapping("/analysis-items/{id}/whitelist")
    public ResponseEntity<R<Map<String, Object>>> setItemWhitelist(
            @PathVariable long id,
            @RequestParam int value,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<Map<String, Object>>> denied = checkToken(token, request, "item/whitelist");
        if (denied != null) return denied;

        int affected = itemDao.setWhitelist(id, value);
        if (affected == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(R.fail("未找到 itemId=" + id));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("isWhitelist", value == 0 ? 0 : 1);
        return ResponseEntity.ok(R.ok(payload));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 口令校验（与 DaoIndexController#triggerBatch 同口径，共享配置）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 校验 X-DII-Trigger-Token。
     * <p>配置为空（含全空白）→ 跳过校验（仅开发环境用）；非空+不匹配 → 401。
     * @return 拒绝响应；null=放行
     */
    private ResponseEntity<R<Map<String, Object>>> checkToken(String token,
                                                              HttpServletRequest request,
                                                              String endpoint) {
        String expected = props.getBatchTrigger().getToken();
        if (expected == null || expected.trim().isEmpty()) {
            return null;
        }
        if (token == null || !expected.equals(token)) {
            log.warn("[sql-pool] 口令校验失败 endpoint={} remoteAddr={} hasToken={}",
                    endpoint, request.getRemoteAddr(), token != null);
            R<Map<String, Object>> body = R.fail("口令错误");
            body.setCode(401);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        return null;
    }
}
