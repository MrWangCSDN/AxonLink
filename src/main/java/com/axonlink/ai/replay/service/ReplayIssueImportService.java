package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;

/** Serializes replay issue imports while atomically replacing the active snapshot. */
@Service
public class ReplayIssueImportService {

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ReplayIssueExcelParser parser;
    private final ReplayIssueDao dao;
    private final ReplayIssueMergeService mergeService;
    private final Clock clock;
    private final ReplayIssueImportGate importGate;

    @Autowired
    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao,
                                    ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao), Clock.systemDefaultZone(), importGate);
    }

    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao) {
        this(parser, dao, new ReplayIssueMergeService(dao), Clock.systemDefaultZone(),
                new ReplayIssueImportGate());
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao, Clock clock,
                             ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao, clock,
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), clock,
                importGate);
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao, ReplayIssueMergeService mergeService,
                             Clock clock, ReplayIssueImportGate importGate) {
        this.parser = parser;
        this.dao = dao;
        this.mergeService = mergeService;
        this.clock = clock;
        this.importGate = importGate;
    }

    public ReplayIssueImportResult importFile(MultipartFile file) throws IOException {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 50MB");
        }
        return importGate.execute(() -> {
            ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file);
            LocalDateTime importedAt = LocalDateTime.now(clock);
            String coverageRound = importedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            return mergeService.merge(parsed, importedAt.toLocalDate(), ReplayIssueOperator.system(), coverageRound);
        });
    }
}
