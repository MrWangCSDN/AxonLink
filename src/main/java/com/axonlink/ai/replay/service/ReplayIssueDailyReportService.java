package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOption;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOptionPage;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayDailyReportMailDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class ReplayIssueDailyReportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReplayDailyDataDao dailyDataDao;
    private final ReplayIssueDao issueDao;
    private final ReplayDailyReportCalculator calculator;
    private final ReplayDailyReportWorkbookWriter workbookWriter;
    private final ReplayReportSummaryViewFactory summaryViewFactory;
    private final ReplayReportSummaryCodec summaryCodec;
    private final ReplayDailyReportMailDao dailyReportMailDao;
    private final TransactionTemplate readSnapshotTransaction;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    @Autowired
    public ReplayIssueDailyReportService(ReplayDailyDataDao dailyDataDao,
                                         ReplayIssueDao issueDao,
                                         ReplayDailyReportCalculator calculator,
                                         ReplayDailyReportWorkbookWriter workbookWriter,
                                         JdbcTemplate diiResultJdbcTemplate) {
        this(dailyDataDao, issueDao, calculator, workbookWriter, diiResultJdbcTemplate,
                Clock.systemDefaultZone());
    }

    ReplayIssueDailyReportService(ReplayDailyDataDao dailyDataDao,
                                  ReplayIssueDao issueDao,
                                  ReplayDailyReportCalculator calculator,
                                  ReplayDailyReportWorkbookWriter workbookWriter,
                                  JdbcTemplate diiResultJdbcTemplate,
                                  Clock clock) {
        this.dailyDataDao = dailyDataDao;
        this.issueDao = issueDao;
        this.calculator = calculator;
        this.workbookWriter = workbookWriter;
        this.summaryViewFactory = new ReplayReportSummaryViewFactory();
        this.summaryCodec = new ReplayReportSummaryCodec();
        this.dailyReportMailDao = new ReplayDailyReportMailDao(diiResultJdbcTemplate);
        this.clock = clock;
        if (diiResultJdbcTemplate.getDataSource() == null) {
            throw new IllegalArgumentException("Replay daily report requires a result DataSource");
        }
        this.readSnapshotTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.readSnapshotTransaction.setReadOnly(true);
        this.readSnapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writeTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public List<ReplayDailyBatch> listBatches() {
        return dailyDataDao.findBatchesRecentFirst();
    }

    public ReplayReportAttachmentOptionPage searchAttachmentOptions(
            String keyword, String family, int page, int size) {
        ReplayReportAttachmentOptionPage result =
                dailyDataDao.searchReportAttachmentOptions(keyword, family, page, size);
        List<ReplayReportAttachmentOption> items = result.items().stream()
                .map(option -> new ReplayReportAttachmentOption(
                        option.batchNo(), option.family(), ReplayReportFileNames.daily(option.batchNo()),
                        option.fileSize(), option.generatedAt()))
                .toList();
        return new ReplayReportAttachmentOptionPage(items, result.page(), result.size(), result.total());
    }

    public byte[] generate(String batchNo) {
        validateBatchNo(batchNo);
        String normalizedBatchNo = batchNo.trim();
        var cached = dailyDataDao.findReportSnapshot(normalizedBatchNo);
        if (cached.isPresent()) {
            return cached.get().content();
        }
        GeneratedReport report = buildReport(normalizedBatchNo);
        dailyDataDao.saveReportSnapshot(snapshot(normalizedBatchNo, report));
        return report.workbook();
    }

    public byte[] regenerate(String batchNo) {
        validateBatchNo(batchNo);
        String normalizedBatchNo = batchNo.trim();
        if (dailyDataDao.findReportSnapshot(normalizedBatchNo).isEmpty()) {
            throw new SnapshotNotFoundException();
        }
        GeneratedReport report = buildReport(normalizedBatchNo);
        writeTransaction.executeWithoutResult(status -> {
            dailyDataDao.saveReportSnapshot(snapshot(normalizedBatchNo, report));
            dailyReportMailDao.delete(normalizedBatchNo);
        });
        return report.workbook();
    }

    private GeneratedReport buildReport(String batchNo) {
        ReportSnapshot snapshot = Objects.requireNonNull(readSnapshotTransaction.execute(
                status -> loadSnapshot(batchNo)));
        var calculated = calculator.calculate(
                snapshot.previousSummaries(), snapshot.previousIssues(), snapshot.previousComparisons(),
                snapshot.currentSummaries(), snapshot.currentIssues(), snapshot.comparisons(),
                snapshot.previousMetricIssues(), snapshot.currentMetricIssues(), snapshot.context());
        ReplayReportSummaryView summaryView = summaryViewFactory.create(
                ReplayReportPeriod.DAILY, null, batchNo, calculated, snapshot.context());
        byte[] workbook = workbookWriter.write(calculated, summaryView, snapshot.comparisons(),
                snapshot.coverageSummaries(), snapshot.coverageDetails());
        return new GeneratedReport(workbook, summaryView);
    }

    private ReplayDailyReportSnapshot snapshot(String batchNo, GeneratedReport report) {
        byte[] bytes = report.workbook();
        return new ReplayDailyReportSnapshot(batchNo, ReplayReportFileNames.daily(batchNo), XLSX_CONTENT_TYPE,
                bytes, bytes.length, summaryCodec.encode(report.summaryView()), LocalDateTime.now(clock));
    }

    private ReportSnapshot loadSnapshot(String batchNo) {
        ReplayDailyBatch selected = dailyDataDao.findBatchesRecentFirst().stream()
                .filter(batch -> batchNo.equals(batch.batchNo()))
                .findFirst()
                .orElseThrow(BatchNotFoundException::new);
        String previousBatchNo = selected.previousBatchNo();
        var loader = new ReplayReportCalculationDataLoader(dailyDataDao, issueDao);
        var current = loader.loadHistorical(batchNo);
        var previous = previousBatchNo == null ? null : loader.loadHistorical(previousBatchNo);
        List<String> notices = new ArrayList<>();
        if (previous != null) notices.addAll(previous.notices());
        notices.addAll(current.notices());
        var context = new ReplayReportCalculationContext(
                previous == null ? ReplayReportCalculationContext.Side.complete() : previous.availability(),
                current.availability(), notices);

        return new ReportSnapshot(
                previous == null ? List.of() : previous.summaries(),
                previous == null ? List.of() : previous.historicalIssues(),
                previous == null ? List.of() : previous.comparisons(),
                current.summaries(),
                current.historicalIssues(),
                current.comparisons(),
                dailyDataDao.findCoverageSummaries(batchNo),
                dailyDataDao.findCoverageDetails(batchNo),
                previous == null ? List.of() : previous.metricIssues(),
                current.metricIssues(), context);
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

    public static final class BatchNotFoundException extends RuntimeException {
        public BatchNotFoundException() {
            super("批次数据不存在");
        }
    }

    public static final class PreviousBatchNotFoundException extends RuntimeException {
        public PreviousBatchNotFoundException() {
            super("没有上批次数据");
        }
    }

    public static final class SnapshotNotFoundException extends RuntimeException {
        public SnapshotNotFoundException() {
            super("日报尚未生成");
        }
    }

    private record ReportSnapshot(
            List<ReplayDailySummaryRow> previousSummaries,
            List<ReplayDailyIssueStatisticRow> previousIssues,
            List<ReplayInterfaceComparisonRow> previousComparisons,
            List<ReplayDailySummaryRow> currentSummaries,
            List<ReplayDailyIssueStatisticRow> currentIssues,
            List<ReplayInterfaceComparisonRow> comparisons,
            List<ReplayCoverageSummaryRow> coverageSummaries,
            List<ReplayCoverageDetailRow> coverageDetails,
            List<ReplayDailyIssueStatisticRow> previousMetricIssues,
            List<ReplayDailyIssueStatisticRow> currentMetricIssues,
            ReplayReportCalculationContext context) {

        private ReportSnapshot {
            previousSummaries = List.copyOf(previousSummaries);
            previousIssues = List.copyOf(previousIssues);
            previousComparisons = List.copyOf(previousComparisons);
            currentSummaries = List.copyOf(currentSummaries);
            currentIssues = List.copyOf(currentIssues);
            comparisons = List.copyOf(comparisons);
            coverageSummaries = List.copyOf(coverageSummaries);
            coverageDetails = List.copyOf(coverageDetails);
            previousMetricIssues = List.copyOf(previousMetricIssues);
            currentMetricIssues = List.copyOf(currentMetricIssues);
            context = Objects.requireNonNull(context);
        }
    }

    private record GeneratedReport(byte[] workbook, ReplayReportSummaryView summaryView) {
        private GeneratedReport {
            workbook = workbook.clone();
        }

        @Override
        public byte[] workbook() {
            return workbook.clone();
        }
    }
}
