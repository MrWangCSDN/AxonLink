package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonImportResult;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.service.ReplayTransactionPersonImportService;
import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/parallel-replay/transaction-persons")
public class ReplayTransactionPersonController {
    private final ReplayTransactionPersonDao dao;
    private final ReplayTransactionPersonImportService importService;
    private final DaoIndexAnalysisProperties properties;

    public ReplayTransactionPersonController(ReplayTransactionPersonDao dao,
                                             ReplayTransactionPersonImportService importService,
                                             DaoIndexAnalysisProperties properties) {
        this.dao = dao; this.importService = importService; this.properties = properties;
    }

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(Map.of("total", dao.count(keyword), "items", dao.list(keyword, limit, offset)));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<ReplayTransactionPersonImportResult>> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<R<ReplayTransactionPersonImportResult>> denied = checkToken(token);
        if (denied != null) return denied;
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(R.fail("文件为空"));
        try {
            ReplayTransactionPersonImportResult result = importService.importFile(file);
            if (!result.imported()) return ResponseEntity.badRequest().body(R.fail(400, "导入校验失败", result));
            return ResponseEntity.ok(R.ok(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(R.fail("交易人员清单导入失败"));
        }
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("全量交易人员清单");
            String[] headers = {"领域", "老交易码", "老交易名称", "开发人员", "行方负责人"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            int rowIndex = 1;
            for (var item : dao.listAll(keyword)) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(value(item.domain()));
                row.createCell(1).setCellValue(value(item.oldTransactionCode()));
                row.createCell(2).setCellValue(value(item.oldTransactionName()));
                row.createCell(3).setCellValue(value(item.developer()));
                row.createCell(4).setCellValue(value(item.bankOwner()));
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            String filename = URLEncoder.encode("全量交易人员清单.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename).body(output.toByteArray());
        }
    }

    private ResponseEntity<R<ReplayTransactionPersonImportResult>> checkToken(String token) {
        String expected = properties.getBatchTrigger().getToken();
        if (expected != null && !expected.isBlank() && !expected.equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail("口令错误"));
        }
        return null;
    }
    private String value(String value) { return value == null ? "" : value; }
}
