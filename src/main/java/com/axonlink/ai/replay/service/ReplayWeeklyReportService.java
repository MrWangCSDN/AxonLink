package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportOptions;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportMailDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ReplayWeeklyReportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReplayDailyDataDao dailyDataDao;
    private final ReplayIssueDao issueDao;
    private final ReplayWeeklyReportDao weeklyReportDao;
    private final ReplayWeeklyReportMailDao weeklyReportMailDao;
    private final ReplayDailyReportCalculator calculator;
    private final ReplayDailyReportWorkbookWriter workbookWriter;
    private final ReplayReportSummaryViewFactory summaryViewFactory;
    private final ReplayReportSummaryCodec summaryCodec;
    private final TransactionTemplate readSnapshotTransaction;
    private final TransactionTemplate writeTransaction;
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
        this.weeklyReportMailDao = new ReplayWeeklyReportMailDao(diiResultJdbcTemplate);
        this.calculator = calculator;
        this.workbookWriter = workbookWriter;
        this.summaryViewFactory = new ReplayReportSummaryViewFactory();
        this.summaryCodec = new ReplayReportSummaryCodec();
        this.clock = clock;
        if (diiResultJdbcTemplate.getDataSource() == null) {
            throw new IllegalArgumentException("Replay weekly report requires a result DataSource");
        }
        this.readSnapshotTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.readSnapshotTransaction.setReadOnly(true);
        this.readSnapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writeTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public ReplayWeeklyReportOptions options() {
        return new ReplayWeeklyReportOptions(
                dailyDataDao.findBatchesWithDataInFamilyOrder(), weeklyReportDao.findGeneratedReports());
    }

    public ReplayWeeklyReportSnapshot generate(String startBatchNo, String endBatchNo) {
        validateBatchNo(startBatchNo);
        validateBatchNo(endBatchNo);
        String normalizedStart = startBatchNo.trim();
        String normalizedEnd = endBatchNo.trim();
        var cached = weeklyReportDao.findSnapshot(normalizedStart, normalizedEnd);
        if (cached.isPresent()) {
            return canonicalSnapshot(cached.get());
        }
        if (weeklyReportDao.findSnapshotByEndBatchNo(normalizedEnd).isPresent()) {
            throw new EndBatchAlreadyGeneratedException();
        }
        validateRange(normalizedStart, normalizedEnd, dailyDataDao.findBatchesWithDataInFamilyOrder());
        ReplayWeeklyReportSnapshot generated = buildReport(normalizedStart, normalizedEnd);
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

    public ReplayWeeklyReportSnapshot regenerate(String startBatchNo, String endBatchNo) {
        validateBatchNo(startBatchNo);
        validateBatchNo(endBatchNo);
        String normalizedStart = startBatchNo.trim();
        String normalizedEnd = endBatchNo.trim();
        if (weeklyReportDao.findSnapshot(normalizedStart, normalizedEnd).isEmpty()) {
            throw new SnapshotNotFoundException();
        }
        validateRange(normalizedStart, normalizedEnd, dailyDataDao.findBatchesWithDataInFamilyOrder());
        ReplayWeeklyReportSnapshot regenerated = buildReport(normalizedStart, normalizedEnd);
        writeTransaction.executeWithoutResult(status -> {
            if (weeklyReportDao.replaceSnapshot(regenerated) == 0) {
                throw new SnapshotNotFoundException();
            }
            weeklyReportMailDao.delete(normalizedStart, normalizedEnd);
        });
        return regenerated;
    }

    private ReplayWeeklyReportSnapshot buildReport(String startBatchNo, String endBatchNo) {
        ReportSnapshot snapshot = Objects.requireNonNull(readSnapshotTransaction.execute(
                status -> loadSnapshot(startBatchNo, endBatchNo)));
        var calculated = calculator.calculate(
                snapshot.startSummaries(), snapshot.startIssues(), snapshot.startComparisons(),
                snapshot.endSummaries(), snapshot.endIssues(), snapshot.comparisons(),
                snapshot.startMetrics().metricIssues(), snapshot.endMetrics().metricIssues(), snapshot.context());
        ReplayReportSummaryView summaryView = summaryViewFactory.create(
                ReplayReportPeriod.WEEKLY, startBatchNo, endBatchNo, calculated, snapshot.context());
        byte[] bytes = workbookWriter.write(calculated, summaryView, snapshot.comparisons(),
                snapshot.coverageSummaries(), snapshot.coverageDetails());
        return new ReplayWeeklyReportSnapshot(
                startBatchNo, endBatchNo, ReplayReportFileNames.weekly(startBatchNo, endBatchNo), XLSX_CONTENT_TYPE,
                bytes, bytes.length, summaryCodec.encode(summaryView), LocalDateTime.now(clock));
    }

    private ReplayWeeklyReportSnapshot canonicalSnapshot(ReplayWeeklyReportSnapshot snapshot) {
        return new ReplayWeeklyReportSnapshot(
                snapshot.startBatchNo(), snapshot.endBatchNo(),
                ReplayReportFileNames.weekly(snapshot.startBatchNo(), snapshot.endBatchNo()),
                snapshot.contentType(), snapshot.content(), snapshot.fileSize(), snapshot.summaryViewJson(),
                snapshot.generatedAt());
    }

    private ReportSnapshot loadSnapshot(String startBatchNo, String endBatchNo) {
        var latestIssues = issueDao.findCurrentReportIssues(startBatchNo, endBatchNo);
        var loader = new ReplayReportCalculationDataLoader(dailyDataDao, issueDao);
        var start = loader.loadLatest(startBatchNo, latestIssues);
        var end = loader.loadLatest(endBatchNo, latestIssues);
        if (start.summaries().isEmpty() || end.summaries().isEmpty()) {
            throw new BatchDataNotFoundException();
        }
        List<String> notices = new ArrayList<>();
        notices.add("周报按各批次数据选择新旧算法；完整新数据按同类全部批次最新审核／修复结论回算，历史数据沿用原算法。数量分别取所选两个批次，不累加历史数量。");
        notices.addAll(start.notices());
        notices.addAll(end.notices());
        var context = new ReplayReportCalculationContext(start.availability(), end.availability(), notices);
        return new ReportSnapshot(start.summaries(), start.historicalIssues(), start.comparisons(),
                end.summaries(), end.historicalIssues(), end.comparisons(),
                dailyDataDao.findCoverageSummaries(endBatchNo), dailyDataDao.findCoverageDetails(endBatchNo),
                start, end, context);
    }

    private static void validateRange(String startBatchNo, String endBatchNo,
                                      List<ReplayDailyBatch> candidates) {
        ReplayDailyBatch start = candidates.stream()
                .filter(batch -> startBatchNo.equals(batch.batchNo()))
                .findFirst()
                .orElseThrow(BatchDataNotFoundException::new);
        ReplayDailyBatch end = candidates.stream()
                .filter(batch -> endBatchNo.equals(batch.batchNo()))
                .findFirst()
                .orElseThrow(BatchDataNotFoundException::new);
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

    public static final class SnapshotNotFoundException extends RuntimeException {
        public SnapshotNotFoundException() {
            super("周报尚未生成");
        }
    }

    public static final class BatchDataNotFoundException extends RuntimeException {
        public BatchDataNotFoundException() {
            super("所选批次没有日报数据");
        }
    }

    private record ReportSnapshot(
            List<ReplayDailySummaryRow> startSummaries,
            List<ReplayDailyIssueStatisticRow> startIssues,
            List<ReplayInterfaceComparisonRow> startComparisons,
            List<ReplayDailySummaryRow> endSummaries,
            List<ReplayDailyIssueStatisticRow> endIssues,
            List<ReplayInterfaceComparisonRow> comparisons,
            List<ReplayCoverageSummaryRow> coverageSummaries,
            List<ReplayCoverageDetailRow> coverageDetails,
            ReplayReportCalculationDataLoader.SideData startMetrics,
            ReplayReportCalculationDataLoader.SideData endMetrics,
            ReplayReportCalculationContext context) {

        private ReportSnapshot {
            startSummaries = List.copyOf(startSummaries);
            startIssues = List.copyOf(startIssues);
            startComparisons = List.copyOf(startComparisons);
            endSummaries = List.copyOf(endSummaries);
            endIssues = List.copyOf(endIssues);
            comparisons = List.copyOf(comparisons);
            coverageSummaries = List.copyOf(coverageSummaries);
            coverageDetails = List.copyOf(coverageDetails);
        }
    }

}
