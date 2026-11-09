package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Serializes replay issue imports while atomically replacing the active snapshot. */
@Service
public class ReplayIssueImportService {

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ReplayIssueExcelParser parser;
    private final ReplayIssueDao dao;
    private final ReplayIssueMergeService mergeService;
    private final ReplayDailyWorkbookParser dailyWorkbookParser;
    private final ReplayDailyDataDao dailyDataDao;
    private final Clock clock;
    private final ReplayIssueImportGate importGate;

    @Autowired
    public ReplayIssueImportService(ReplayIssueExcelParser parser,
                                    ReplayIssueDao dao,
                                    ReplayIssueMergeService mergeService,
                                    ReplayDailyWorkbookParser dailyWorkbookParser,
                                    ReplayDailyDataDao dailyDataDao,
                                    ReplayIssueImportGate importGate) {
        this(parser, dao, mergeService, dailyWorkbookParser, dailyDataDao,
                Clock.systemDefaultZone(), importGate);
    }

    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao) {
        this(parser, dao, new ReplayIssueMergeService(dao), null, null,
                Clock.systemDefaultZone(), new ReplayIssueImportGate());
    }

    public ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao,
                                    ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao), null, null,
                Clock.systemDefaultZone(), importGate);
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao, Clock clock,
                             ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao, clock,
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                null, null, clock, importGate);
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao,
                             ReplayIssueMergeService mergeService, Clock clock,
                             ReplayIssueImportGate importGate) {
        this(parser, dao, mergeService, null, null, clock, importGate);
    }

    ReplayIssueImportService(ReplayIssueExcelParser parser, ReplayIssueDao dao,
                             ReplayDailyWorkbookParser dailyWorkbookParser,
                             ReplayDailyDataDao dailyDataDao,
                             Clock clock, ReplayIssueImportGate importGate) {
        this(parser, dao, new ReplayIssueMergeService(dao, clock,
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                dailyWorkbookParser, dailyDataDao, clock, importGate);
    }

    private ReplayIssueImportService(ReplayIssueExcelParser parser,
                                     ReplayIssueDao dao,
                                     ReplayIssueMergeService mergeService,
                                     ReplayDailyWorkbookParser dailyWorkbookParser,
                                     ReplayDailyDataDao dailyDataDao,
                                     Clock clock,
                                     ReplayIssueImportGate importGate) {
        this.parser = parser;
        this.dao = dao;
        this.mergeService = mergeService;
        this.dailyWorkbookParser = dailyWorkbookParser;
        this.dailyDataDao = dailyDataDao;
        this.clock = clock;
        this.importGate = importGate;
    }

    public ReplayIssueImportResult importFile(MultipartFile file) throws IOException {
        return importFile(file, ReplayIssueImportMode.QUERY);
    }

    public ReplayIssueImportResult importFile(MultipartFile file, ReplayIssueImportMode mode) throws IOException {
        return importFile(file, mode, null);
    }

    public ReplayIssueImportResult importFile(MultipartFile file, ReplayIssueImportMode mode,
                                               String releaseAt) throws IOException {
        ReplayIssueImportMode effectiveMode = mode == null ? ReplayIssueImportMode.QUERY : mode;
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 50MB");
        }
        return importGate.execute(() -> {
            ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file, effectiveMode);
            ReplayDailyWorkbookData dailyData = dailyWorkbookParser == null
                    ? null : dailyWorkbookParser.parseIfDailySheetsPresent(file, effectiveMode).orElse(null);
            if (dailyWorkbookParser != null && dailyData == null && !parsed.hasIssueSheets()) {
                throw new IllegalArgumentException("缺少可导入的问题清单页签");
            }
            validateDailyBatch(parsed, dailyData);
            Map<String, Long> sentTransactionCounts = sentTransactionCounts(dailyData);
            String sentTransactionCountBatchPrefix = sentTransactionCountBatchPrefix(parsed, dailyData);
            LocalDateTime importedAt = LocalDateTime.now(clock);
            if (releaseAt != null) ReplayReleaseTime.resolve(releaseAt, importedAt.toLocalDate());
            String coverageRound = importedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            return dao.inTransaction(currentDao -> {
                if (dailyData != null) currentDao.beginBatchStatistics(dailyData.batchNo(), importedAt);
                ReplayIssueImportResult result = dailyData == null || parsed.hasIssueSheets()
                        ? mergeService.mergeWithinTransaction(currentDao, parsed, importedAt.toLocalDate(),
                                ReplayIssueOperator.system(), coverageRound,
                                dailyData == null ? null : dailyData.batchNo(), releaseAt)
                        : new ReplayIssueImportResult(0, parsed.rowsBySheet(), 0, 0, importedAt,
                                0, 0, 0, 0, 0, coverageRound);
                if (dailyData != null) dailyDataDao.replaceBatch(dailyData, importedAt);
                if (sentTransactionCountBatchPrefix != null) {
                    currentDao.replaceSentTransactionCounts(sentTransactionCountBatchPrefix, sentTransactionCounts);
                }
                return result;
            });
        });
    }

    private String sentTransactionCountBatchPrefix(ReplayIssueExcelParser.ParsedWorkbook parsed,
                                                   ReplayDailyWorkbookData dailyData) {
        String batch = dailyData == null
                ? parsed.rows().stream().map(ReplayIssueRow::batchNo)
                        .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null)
                : dailyData.batchNo();
        if (batch == null) return null;
        String normalized = batch.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("RPT")) return "RPT";
        if (normalized.startsWith("DZ")) return "DZ";
        return null;
    }

    private Map<String, Long> sentTransactionCounts(ReplayDailyWorkbookData dailyData) {
        if (dailyData == null) return Map.of();
        Set<String> unknownCodes = new HashSet<>();
        for (ReplayInterfaceComparisonRow row : dailyData.comparisons()) {
            if (row.rowType() != ReplayDailyRowType.DETAIL || row.transactionCode() == null) continue;
            String code = row.transactionCode().trim();
            if (!code.isEmpty() && row.sentTransactionCount() == null) unknownCodes.add(code);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReplayInterfaceComparisonRow row : dailyData.comparisons()) {
            if (row.rowType() != ReplayDailyRowType.DETAIL || row.transactionCode() == null) continue;
            String code = row.transactionCode().trim();
            if (code.isEmpty() || unknownCodes.contains(code)) continue;
            try {
                counts.merge(code, row.sentTransactionCount(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("页签“接口比对明细”交易码“" + code
                        + "”发送交易量合计超出整数范围", exception);
            }
        }
        unknownCodes.forEach(code -> counts.put(code, null));
        return counts;
    }

    private void validateDailyBatch(ReplayIssueExcelParser.ParsedWorkbook issues,
                                    ReplayDailyWorkbookData dailyData) {
        if (dailyData == null) {
            return;
        }
        for (ReplayIssueRow row : issues.rows()) {
            String issueBatch = row.batchNo() == null ? "" : row.batchNo().trim();
            if (!dailyData.batchNo().equals(issueBatch)) {
                throw new IllegalArgumentException("页签“" + row.sourceSheet() + "”第 " + (row.rowOrder() + 1)
                        + " 行批次与日报批次不一致：" + (issueBatch.isBlank() ? "空" : issueBatch));
            }
        }
    }
}
