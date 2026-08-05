package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import java.util.concurrent.Semaphore;

/** Serializes replay issue imports while atomically replacing the active snapshot. */
@Service
public class ReplayIssueImportService {

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ReplayIssueExcelParser parser;
    private final ReplayIssueDao dao;
    private final ReplayIssueMergeService mergeService;
    private final Clock clock;
    private final Semaphore importPermit;

    @Autowired
    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao) {
        this(parser, dao, new ReplayIssueMergeService(dao), Clock.systemDefaultZone(), new Semaphore(1));
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao, Clock clock,
                             Semaphore importPermit) {
        this(parser, dao, new ReplayIssueMergeService(dao, clock,
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), clock,
                importPermit);
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao, ReplayIssueMergeService mergeService,
                             Clock clock, Semaphore importPermit) {
        this.parser = parser;
        this.dao = dao;
        this.mergeService = mergeService;
        this.clock = clock;
        this.importPermit = importPermit;
    }

    public ReplayIssueImportResult importFile(MultipartFile file) throws IOException {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 50MB");
        }
        if (!importPermit.tryAcquire()) {
            throw new ReplayIssueImportBusyException();
        }
        try {
            ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file);
            LocalDateTime importedAt = LocalDateTime.now(clock);
            return mergeService.merge(parsed, importedAt.toLocalDate(), ReplayIssueOperator.system());
        } finally {
            importPermit.release();
        }
    }
}
