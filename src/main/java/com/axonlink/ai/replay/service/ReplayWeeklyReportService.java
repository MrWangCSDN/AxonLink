package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportOptions;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class ReplayWeeklyReportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReplayDailyDataDao dailyDataDao;
    private final ReplayIssueDao issueDao;
    private final ReplayWeeklyReportDao weeklyReportDao;
    private final ReplayDailyReportCalculator calculator;
    private final ReplayDailyReportWorkbookWriter workbookWriter;
    private final TransactionTemplate readSnapshotTransaction;
    private final Clock clock;

    @Autowired
    public ReplayWeeklyReportService(ReplayDailyDataDao dailyDataDao,
                                     ReplayIssueDao issueDao,
                                     ReplayWeeklyReportDao weeklyReportDao,
                                     ReplayDailyReportCalculator calculator,
                                     ReplayDailyReportWorkbookWriter workbookWriter,
                                     JdbcTemplate diiResultJdbcTemplate) {
        this(dailyDataDao, issueDao, weeklyReportDao, calculator, workbookWriter,
                diiResultJdbcTemplate, Clock.systemDefaultZone());
    }

    ReplayWeeklyReportService(ReplayDailyDataDao dailyDataDao,
                              ReplayIssueDao issueDao,
                              ReplayWeeklyReportDao weeklyReportDao,
                              ReplayDailyReportCalculator calculator,
                              ReplayDailyReportWorkbookWriter workbookWriter,
                              JdbcTemplate diiResultJdbcTemplate,
                              Clock clock) {
        this.dailyDataDao = dailyDataDao;
        this.issueDao = issueDao;
        this.weeklyReportDao = weeklyReportDao;
        this.calculator = calculator;
        this.workbookWriter = workbookWriter;
        this.clock = clock;
        if (diiResultJdbcTemplate.getDataSource() == null) {
            throw new IllegalArgumentException("Replay weekly report requires a result DataSource");
        }
        this.readSnapshotTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.readSnapshotTransaction.setReadOnly(true);
        this.readSnapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ReplayWeeklyReportOptions options() {
        return new ReplayWeeklyReportOptions(
                dailyDataDao.findGeneratedBatchesInFamilyOrder(), weeklyReportDao.findGeneratedReports());
    }

    public ReplayWeeklyReportSnapshot generate(String startBatchNo, String endBatchNo) {
        validateBatchNo(startBatchNo);
        validateBatchNo(endBatchNo);
        String normalizedStart = startBatchNo.trim();
        String normalizedEnd = endBatchNo.trim();
        var cached = weeklyReportDao.findSnapshot(normalizedStart, normalizedEnd);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (weeklyReportDao.findSnapshotByEndBatchNo(normalizedEnd).isPresent()) {
            throw new EndBatchAlreadyGeneratedException();
        }
        validateRange(normalizedStart, normalizedEnd, dailyDataDao.findGeneratedBatchesInFamilyOrder());
        ReportSnapshot snapshot = Objects.requireNonNull(readSnapshotTransaction.execute(
                status -> loadSnapshot(normalizedStart, normalizedEnd)));
        var calculated = calculator.calculate(
                snapshot.startSummaries(), snapshot.startIssues(),
                snapshot.endSummaries(), snapshot.endIssues());
        byte[] bytes = workbookWriter.write(calculated, snapshot.comparisons(),
                snapshot.coverageSummaries(), snapshot.coverageDetails());
        ReplayWeeklyReportSnapshot generated = new ReplayWeeklyReportSnapshot(
                normalizedStart, normalizedEnd, normalizedEnd + "周报.xlsx", XLSX_CONTENT_TYPE,
                bytes, bytes.length, LocalDateTime.now(clock));
        try {
            weeklyReportDao.saveSnapshot(generated);
        } catch (DataIntegrityViolationException exception) {
            if (weeklyReportDao.findSnapshotByEndBatchNo(normalizedEnd).isPresent()) {
                throw new EndBatchAlreadyGeneratedException();
            }
            throw exception;
        }
        return generated;
    }

    private ReportSnapshot loadSnapshot(String startBatchNo, String endBatchNo) {
        List<ReplayDailySummaryRow> startSummaries = dailyDataDao.findSummaries(startBatchNo);
        List<ReplayDailySummaryRow> endSummaries = dailyDataDao.findSummaries(endBatchNo);
        if (startSummaries.isEmpty() || endSummaries.isEmpty()) {
            throw new BatchDataNotFoundException();
        }
        return new ReportSnapshot(
                startSummaries,
                issueDao.findDailyReportIssueStatistics(startBatchNo),
                endSummaries,
                issueDao.findDailyReportIssueStatistics(endBatchNo),
                dailyDataDao.findComparisons(endBatchNo),
                dailyDataDao.findCoverageSummaries(endBatchNo),
                dailyDataDao.findCoverageDetails(endBatchNo));
    }

    private static void validateRange(String startBatchNo, String endBatchNo,
                                      List<ReplayDailyBatch> candidates) {
        ReplayDailyBatch start = candidates.stream()
                .filter(batch -> startBatchNo.equals(batch.batchNo()))
                .findFirst()
                .orElseThrow(DailyReportNotGeneratedException::new);
        ReplayDailyBatch end = candidates.stream()
                .filter(batch -> endBatchNo.equals(batch.batchNo()))
                .findFirst()
                .orElseThrow(DailyReportNotGeneratedException::new);
        if (!start.family().equals(end.family()) || startBatchNo.equals(endBatchNo)
                || candidates.indexOf(start) >= candidates.indexOf(end)) {
            throw new InvalidRangeException();
        }
    }

    private static void validateBatchNo(String batchNo) {
        if (!ReplayDailyBatch.isStandardBatchNo(batchNo)) {
            throw new MalformedBatchException();
        }
    }

    public static final class MalformedBatchException extends RuntimeException {
        public MalformedBatchException() {
            super("批次号格式错误");
        }
    }

    public static final class DailyReportNotGeneratedException extends RuntimeException {
        public DailyReportNotGeneratedException() {
            super("所选批次日报尚未生成");
        }
    }

    public static final class InvalidRangeException extends RuntimeException {
        public InvalidRangeException() {
            super("周报起止批次范围错误");
        }
    }

    public static final class EndBatchAlreadyGeneratedException extends RuntimeException {
        public EndBatchAlreadyGeneratedException() {
            super("结束批次周报已生成");
        }
    }

    public static final class BatchDataNotFoundException extends RuntimeException {
        public BatchDataNotFoundException() {
            super("所选批次数据不存在");
        }
    }

    private record ReportSnapshot(
            List<ReplayDailySummaryRow> startSummaries,
            List<ReplayDailyIssueStatisticRow> startIssues,
            List<ReplayDailySummaryRow> endSummaries,
            List<ReplayDailyIssueStatisticRow> endIssues,
            List<ReplayInterfaceComparisonRow> comparisons,
            List<ReplayCoverageSummaryRow> coverageSummaries,
            List<ReplayCoverageDetailRow> coverageDetails) {

        private ReportSnapshot {
            startSummaries = List.copyOf(startSummaries);
            startIssues = List.copyOf(startIssues);
            endSummaries = List.copyOf(endSummaries);
            endIssues = List.copyOf(endIssues);
            comparisons = List.copyOf(comparisons);
            coverageSummaries = List.copyOf(coverageSummaries);
            coverageDetails = List.copyOf(coverageDetails);
        }
    }
}
