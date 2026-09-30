package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.service.ReplayConfigExportService;
import com.axonlink.security.UserPrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 忽略清单导出：把已审核的四类配置导出为一个 Excel（四个 sheet）。
 */
@RestController
@RequestMapping("/api/ai/parallel-replay/config/export")
public class ReplayConfigExportController extends AbstractReplayConfigController {

    private static final Logger log = LoggerFactory.getLogger(ReplayConfigExportController.class);

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReplayConfigExportService exportService;

    public ReplayConfigExportController(ReplayConfigExportService exportService,
                                        UserPrincipalResolver userResolver) {
        super(userResolver);
        this.exportService = exportService;
    }

    @GetMapping
    public ResponseEntity<byte[]> export() throws IOException {
        byte[] body = exportService.exportReviewedConfigs();

        String fileName = "忽略清单-" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));

        log.info("[replay-config] 忽略清单导出完成，{} 字节", body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
