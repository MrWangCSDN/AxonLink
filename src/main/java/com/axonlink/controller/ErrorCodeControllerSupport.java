package com.axonlink.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import com.axonlink.ai.daoindex.errorcode.export.ErrorCodeExportService;
import com.axonlink.ai.daoindex.errorcode.scan.ErrorCodeScanService;
import com.axonlink.common.R;
import com.axonlink.service.FlowtranImpactExportService.ExportFile;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 错误码相关接口（与 FlowtranController 共享前缀 /api/flowtran）。
 * 单列控制器以便 standalone MockMvc 独立装配，不污染 FlowtranController 既有依赖。
 */
@RestController
@RequestMapping("/api/flowtran")
public class ErrorCodeControllerSupport {

    private static final Logger log = LoggerFactory.getLogger(ErrorCodeControllerSupport.class);

    private final DiiErrorCodeDao dao;
    private final ErrorCodeExportService exportService;
    private final ErrorCodeScanService scanService;
    private final DaoIndexAnalysisProperties props;

    public ErrorCodeControllerSupport(DiiErrorCodeDao dao,
                                      ErrorCodeExportService exportService,
                                      ErrorCodeScanService scanService,
                                      DaoIndexAnalysisProperties props) {
        this.dao = dao;
        this.exportService = exportService;
        this.scanService = scanService;
        this.props = props;
    }

    /** 交易错误码：喂徽章 + 展开列表。count=COUNT(*)，distinctCount=COUNT(DISTINCT error_code)。 */
    @GetMapping("/transactions/{txId}/error-codes")
    public R<Map<String, Object>> errorCodes(@PathVariable String txId) {
        List<TxErrorCodeRow> list = dao.listByTxId(txId);
        Map<String, Object> body = new HashMap<>();
        body.put("count", dao.countByTxId(txId));
        body.put("distinctCount", dao.distinctCountByTxId(txId));
        body.put("list", list);
        return R.ok(body);
    }

    /** 单交易导出。 */
    @GetMapping("/transactions/{txId}/error-codes/export")
    public ResponseEntity<byte[]> exportSingle(@PathVariable String txId) {
        return asExcel(exportService.exportSingle(txId));
    }

    /** 全量导出（交易维度），domain 可选。 */
    @GetMapping("/error-codes/export")
    public ResponseEntity<byte[]> exportAll(@RequestParam(required = false) String domain) {
        return asExcel(exportService.exportAll(domain));
    }

    /** 手动重扫（口令复用 batch-trigger，空白放行；非空不匹配 401）。 */
    @PostMapping("/error-codes/rescan")
    public ResponseEntity<R<?>> rescan(
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        String expected = props.getBatchTrigger().getToken();
        if (expected == null || expected.trim().isEmpty()) {
            scanService.triggerRescan();
            return ResponseEntity.ok(R.ok("Rescan started"));
        }
        if (token == null || !expected.equals(token)) {
            log.warn("[error-code] 口令校验失败 remoteAddr={} hasToken={}",
                    request.getRemoteAddr(), token != null);
            R<?> body = R.fail("口令错误");   // R 无 fail(String,int) 重载
            body.setCode(401);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        scanService.triggerRescan();
        return ResponseEntity.ok(R.ok("Rescan started"));
    }

    private ResponseEntity<byte[]> asExcel(ExportFile exportFile) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exportFile.getFileName(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(exportFile.getContent(), headers, HttpStatus.OK);
    }
}
