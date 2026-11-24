package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.service.ReplayConfigReasonImportService;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/ai/parallel-replay/config")
public class ReplayConfigReasonImportController extends AbstractReplayConfigController {
    private final ReplayConfigReasonImportService service;

    public ReplayConfigReasonImportController(ReplayConfigReasonImportService service,
                                               UserPrincipalResolver userResolver) {
        super(userResolver);
        this.service = service;
    }

    @PostMapping(value = "/{type:conditional-ignores|sort-fields|error-code-ignores}/import-ignore-reasons",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ReplayConfigReasonImportService.Result> importReasons(
            @PathVariable("type") String type, @RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        return R.ok(service.importReasons(type, file, resolveOperator(request)));
    }

    @PostMapping(value = "/{type:unconditional-ignores|conditional-ignores|sort-fields}/import-field-chinese-names",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ReplayConfigReasonImportService.ChineseNameResult> importChineseNames(
            @PathVariable("type") String type, @RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        return R.ok(service.importChineseNames(type, file, resolveOperator(request)));
    }
}
