package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ReplayIssueImportService.class);
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ReplayIssueExcelParser parser;
    private final ReplayIssueDao dao;
    private final ReplayIssueMergeService mergeService;
    private final ReplayIssueSummaryParser summaryParser;
    private final ReplayIssueDailyReportService dailyReportService;
    private final Clock clock;
    private final ReplayIssueImportGate importGate;

    @Autowired
    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao,
                                    ReplayIssueSummaryParser summaryParser,
                                    ReplayIssueDailyReportService dailyReportService,
                                    ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao), summaryParser, dailyReportService,
                Clock.systemDefaultZone(), importGate);
    }

    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao) {
        this(parser, dao, new ReplayIssueMergeService(dao), new ReplayIssueSummaryParser(), null,
                Clock.systemDefaultZone(), new ReplayIssueImportGate());
    }

    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao,
                                    ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao), new ReplayIssueSummaryParser(), null,
                Clock.systemDefaultZone(), importGate);
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao, Clock clock,
                             ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao, clock,
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new ReplayIssueSummaryParser(), null, clock, importGate);
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao, ReplayIssueMergeService mergeService,
                             Clock clock, ReplayIssueImportGate importGate) {
        this(parser, dao, mergeService, new ReplayIssueSummaryParser(), null, clock, importGate);
    }

    private ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao,
                                     ReplayIssueMergeService mergeService, ReplayIssueSummaryParser summaryParser,
                                     ReplayIssueDailyReportService dailyReportService,
                                     Clock clock, ReplayIssueImportGate importGate) {
        this.parser = parser;
        this.dao = dao;
        this.mergeService = mergeService;
        this.summaryParser = summaryParser;
        this.dailyReportService = dailyReportService;
        this.clock = clock;
        this.importGate = importGate;
    }

    public ReplayIssueImportResult importFile(MultipartFile file) throws IOException {
        return importFile(file, ReplayIssueImportMode.QUERY);
    }

    public ReplayIssueImportResult importFile(MultipartFile file, ReplayIssueImportMode mode) throws IOException {
        ReplayIssueImportMode effectiveMode = mode == null ? ReplayIssueImportMode.QUERY : mode;
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 50MB");
        }
        return importGate.execute(() -> {
            ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file, effectiveMode);
            LocalDateTime importedAt = LocalDateTime.now(clock);
            String coverageRound = importedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            ReplayIssueImportResult result = mergeService.merge(parsed, importedAt.toLocalDate(),
                    ReplayIssueOperator.system(), coverageRound);
            ReplayIssueSummaryParser.ParsedSummary summary = summaryParser.parse(file, effectiveMode);
            persistDailyReport(importedAt, summary);
            return result;
        });
    }

    /**
     * 导入成功后落盘日报快照（滚动窗口设计：上次日报下半 = 本次上半，Excel 下半 = 本次下半）。
     * <p>日报未装配（旧测试构造器）时跳过；生成失败仅 log，不影响导入返回值。
     */
    private void persistDailyReport(LocalDateTime importedAt, ReplayIssueSummaryParser.ParsedSummary summary) {
        if (dailyReportService == null || summary == null || !summary.sheetFound()) {
            return;
        }
        // 当前批次 = occurrence 表最近一次出现的 batch_name（用户 Excel「汇总信息」sheet 下半里填的批次号）
        // 如果没有 summary 或 summary 下半部分为空，fallback 到日报 service 自查 occurrence 表
        String currentBatch = summary == null || summary.lowerRows().isEmpty()
                ? null
                : summary.lowerRows().get(0).batchNo();
        if (currentBatch == null || currentBatch.isBlank()) {
            // 没有「汇总信息」sheet 时，从 occurrence 表推断
            currentBatch = dao.currentBatchName();
        }
        try {
            dailyReportService.generateNext(currentBatch, importedAt, summary);
        } catch (Exception exception) {
            log.warn("[daily-report] 落盘失败（不影响导入）: {}", exception.getMessage(), exception);
        }
    }

}
