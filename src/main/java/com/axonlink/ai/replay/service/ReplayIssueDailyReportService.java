package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate readSnapshotTransaction;
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
        this.clock = clock;
        if (diiResultJdbcTemplate.getDataSource() == null) {
            throw new IllegalArgumentException("Replay daily report requires a result DataSource");
        }
        this.readSnapshotTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.readSnapshotTransaction.setReadOnly(true);
        this.readSnapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public List<ReplayDailyBatch> listBatches() {
        return dailyDataDao.findBatchesRecentFirst();
    }

    public byte[] generate(String batchNo) {
        validateBatchNo(batchNo);
        var cached = dailyDataDao.findReportSnapshot(batchNo);
        if (cached.isPresent()) {
            return cached.get().content();
        }
        ReportSnapshot snapshot = Objects.requireNonNull(readSnapshotTransaction.execute(
                status -> loadSnapshot(batchNo)));
        var calculated = calculator.calculate(
                snapshot.previousSummaries(), snapshot.previousIssues(),
                snapshot.currentSummaries(), snapshot.currentIssues());
        byte[] bytes = workbookWriter.write(calculated, snapshot.comparisons(),
                snapshot.coverageSummaries(), snapshot.coverageDetails());
        dailyDataDao.saveReportSnapshot(new ReplayDailyReportSnapshot(
                batchNo, batchNo + "日报.xlsx", XLSX_CONTENT_TYPE, bytes, bytes.length,
                LocalDateTime.now(clock)));
        return bytes;
    }

    private ReportSnapshot loadSnapshot(String batchNo) {
        ReplayDailyBatch selected = dailyDataDao.findBatchesRecentFirst().stream()
                .filter(batch -> batchNo.equals(batch.batchNo()))
                .findFirst()
                .orElseThrow(BatchNotFoundException::new);
        String previousBatchNo = selected.previousBatchNo();
        if (previousBatchNo == null) {
            throw new PreviousBatchNotFoundException();
        }

        return new ReportSnapshot(
                dailyDataDao.findSummaries(previousBatchNo),
                issueDao.findDailyReportIssueStatistics(previousBatchNo),
                dailyDataDao.findSummaries(batchNo),
                issueDao.findDailyReportIssueStatistics(batchNo),
                dailyDataDao.findComparisons(batchNo),
                dailyDataDao.findCoverageSummaries(batchNo),
                dailyDataDao.findCoverageDetails(batchNo));
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

    private record ReportSnapshot(
            List<ReplayDailySummaryRow> previousSummaries,
            List<ReplayDailyIssueStatisticRow> previousIssues,
            List<ReplayDailySummaryRow> currentSummaries,
            List<ReplayDailyIssueStatisticRow> currentIssues,
            List<ReplayInterfaceComparisonRow> comparisons,
            List<ReplayCoverageSummaryRow> coverageSummaries,
            List<ReplayCoverageDetailRow> coverageDetails) {

        private ReportSnapshot {
            previousSummaries = List.copyOf(previousSummaries);
            previousIssues = List.copyOf(previousIssues);
            currentSummaries = List.copyOf(currentSummaries);
            currentIssues = List.copyOf(currentIssues);
            comparisons = List.copyOf(comparisons);
            coverageSummaries = List.copyOf(coverageSummaries);
            coverageDetails = List.copyOf(coverageDetails);
        }
    }
}
