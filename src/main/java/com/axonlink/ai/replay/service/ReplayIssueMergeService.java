package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Merges an imported workbook into the current issue projection by issue_key. */
@Service
public class ReplayIssueMergeService {

    private static final Pattern BATCH_DATE_PATTERN = Pattern.compile("^(?:RPT|DZ)(\\d{8})-.+$");

    private final ReplayIssueDao dao;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReplayIssueMergeService(ReplayIssueDao dao) {
        this(dao, Clock.systemDefaultZone(), new ObjectMapper().findAndRegisterModules());
    }

    ReplayIssueMergeService(ReplayIssueDao dao, Clock clock, ObjectMapper objectMapper) {
        this.dao = dao;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public ReplayIssueImportResult merge(ReplayIssueExcelParser.ParsedWorkbook workbook,
                                         LocalDate importDate, ReplayIssueOperator operator) {
        return merge(workbook, importDate, operator,
                LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
    }

    public ReplayIssueImportResult merge(ReplayIssueExcelParser.ParsedWorkbook workbook,
                                         LocalDate importDate, ReplayIssueOperator operator, String coverageRound) {
        if (workbook == null || workbook.rows().isEmpty()) {
            throw new IllegalArgumentException("目标页签中没有可导入数据");
        }
        validateKeys(workbook);
        LocalDate effectiveDate = importDate == null ? LocalDate.now(clock) : importDate;
        LocalDate repairDate = batchDate(workbook.rows());
        String occurrenceBatchName = incomingBatchName(workbook.rows());
        String batchFamily = batchFamily(occurrenceBatchName);
        ReplayIssueOperator effectiveOperator = operator == null ? ReplayIssueOperator.system() : operator;
        LocalDateTime operationAt = LocalDateTime.now(clock);
        Set<String> incomingKeys = workbook.rows().stream().map(row -> row.issueKey().trim()).collect(java.util.stream.Collectors.toSet());

        int[] counts = dao.inTransaction(currentDao -> {
            long roundId = currentDao.insertImportRound(coverageRound, operationAt, effectiveOperator, workbook.rows().size());
            int created = 0;
            int updated = 0;
            int ignored = 0;
            for (ReplayIssueRow incoming : workbook.rows()) {
                String key = incoming.issueKey().trim();
                ReplayIssueRow current = currentDao.findCurrentByIssueKeyForUpdate(key);
                if (current == null) {
                    ReplayIssueRow createdRow = newRow(incoming, effectiveDate);
                    long id = currentDao.insertCurrent(createdRow);
                    currentDao.updateCoverageRound(id, coverageRound);
                    ReplayIssueRow after = withId(createdRow, id);
                    currentDao.insertIssueRound(roundId, id, key, true, null, ReplayIssueStatus.NEW,
                            "导入新增", incoming.sourceSheet(), incoming.rowOrder() + 1, operationAt,
                            snapshot(incoming), incoming.batchNo());
                    currentDao.upsertOccurrenceBatch(id, key, incoming.batchNo(), operationAt, ReplayIssueStatus.NEW);
                    currentDao.insertHistoryForRound(id, key, "导入新增", operationAt, effectiveOperator, effectiveDate,
                            coverageRound, incoming.sourceSheet(), incoming.rowOrder() + 1,
                            null, snapshot(after), snapshot(incoming), roundId);
                    currentDao.updateLatestHistoryOccurrenceBatch(id, operationAt, incoming.batchNo());
                    created++;
                    continue;
                }
                boolean batchAlreadyKnown = currentDao.occurrenceBatchExists(current.id(), incoming.batchNo());
                ReplayIssueStatus status = current.issueStatus() == null ? ReplayIssueStatus.OPEN : current.issueStatus();
                if (status == ReplayIssueStatus.NEW || status == ReplayIssueStatus.OPEN || status == ReplayIssueStatus.DEFERRED) {
                    ReplayIssueRow refreshed = refreshed(current, incoming, status, current.importDate());
                    currentDao.updateCurrent(refreshed);
                    currentDao.updateCoverageRound(current.id(), coverageRound);
                    currentDao.insertIssueRound(roundId, current.id(), key, true, status, status,
                            "数据继承", incoming.sourceSheet(), incoming.rowOrder() + 1, operationAt,
                            snapshot(incoming), incoming.batchNo());
                    currentDao.upsertOccurrenceBatch(current.id(), key, incoming.batchNo(), operationAt, status);
                    String beforeSnapshot = snapshot(current);
                    String afterSnapshot = snapshot(refreshed);
                    if (!batchAlreadyKnown && ReplayIssueTrackingProjection.hasFieldChanges(beforeSnapshot, afterSnapshot)) {
                        currentDao.insertHistoryForRound(current.id(), key, "基础数据覆盖，人工内容继承",
                                operationAt, effectiveOperator, effectiveDate, coverageRound,
                                incoming.sourceSheet(), incoming.rowOrder() + 1,
                                beforeSnapshot, afterSnapshot, snapshot(incoming), roundId);
                        currentDao.updateLatestHistoryOccurrenceBatch(current.id(), operationAt, incoming.batchNo());
                    }
                    updated++;
                    continue;
                }
                if (status == ReplayIssueStatus.ANALYZING) {
                    currentDao.updateCoverageRound(current.id(), coverageRound);
                    if (!batchAlreadyKnown) currentDao.insertIssueRound(roundId, current.id(), key, true, status, status,
                            "保持", incoming.sourceSheet(), incoming.rowOrder() + 1, operationAt,
                            snapshot(incoming), incoming.batchNo());
                    currentDao.upsertOccurrenceBatch(current.id(), key, incoming.batchNo(), operationAt, status);
                    ignored++;
                    continue;
                }
                boolean pendingVerificationNeedsReopen = status == ReplayIssueStatus.PENDING_VERIFICATION
                        && shouldReopenBefore(currentDao.findLatestManualSaveAt(current.id()), repairDate);
                ReplayIssueStatus nextStatus = pendingVerificationNeedsReopen
                        ? ReplayIssueStatus.REOPENED : status == ReplayIssueStatus.FIXED ? ReplayIssueStatus.NEW : status;
                boolean resetImportDate = status == ReplayIssueStatus.FIXED;
                ReplayIssueRow refreshed = refreshed(current, incoming, nextStatus,
                        resetImportDate ? effectiveDate : current.importDate());
                currentDao.updateCurrent(refreshed);
                currentDao.updateCoverageRound(current.id(), coverageRound);
                String actionType = pendingVerificationNeedsReopen ? "重新打开并继承" : "数据继承";
                if (!batchAlreadyKnown) currentDao.insertIssueRound(roundId, current.id(), key, true, status, nextStatus,
                        actionType, incoming.sourceSheet(), incoming.rowOrder() + 1, operationAt,
                        snapshot(incoming), incoming.batchNo());
                currentDao.upsertOccurrenceBatch(current.id(), key, incoming.batchNo(), operationAt, nextStatus);
                String beforeSnapshot = snapshot(current);
                String afterSnapshot = snapshot(refreshed);
                if (!batchAlreadyKnown && ReplayIssueTrackingProjection.hasFieldChanges(beforeSnapshot, afterSnapshot)) {
                    currentDao.insertHistoryForRound(current.id(), key,
                            pendingVerificationNeedsReopen ? "修复待验证问题重新打开"
                                    : status == ReplayIssueStatus.FIXED ? "已修复问题重新新建"
                                    : status == ReplayIssueStatus.NO_ACTION ? "基础数据覆盖，人工内容继承" : "数据继承",
                            operationAt, effectiveOperator, effectiveDate, coverageRound,
                            incoming.sourceSheet(), incoming.rowOrder() + 1,
                            beforeSnapshot, afterSnapshot, snapshot(incoming), roundId);
                    currentDao.updateLatestHistoryOccurrenceBatch(current.id(), operationAt, incoming.batchNo());
                }
                updated++;
            }
            int autoRepaired = 0;
            for (ReplayIssueRow current : currentDao.findAutoRepairCandidatesMissing(incomingKeys, batchFamily)) {
                ReplayIssueRow fixed = withStatusAndDefectDate(current, ReplayIssueStatus.FIXED, repairDate);
                currentDao.updateCurrent(fixed);
                currentDao.insertIssueRound(roundId, current.id(), current.issueKey(), false,
                        current.issueStatus(), ReplayIssueStatus.FIXED, "自动修复", null, null, operationAt);
                currentDao.insertHistoryForRound(current.id(), current.issueKey(), "问题自动修复", operationAt,
                        effectiveOperator, effectiveDate, coverageRound, null, null,
                        snapshot(current), snapshot(fixed), null, roundId);
                currentDao.updateLatestHistoryOccurrenceBatch(current.id(), operationAt, occurrenceBatchName);
                autoRepaired++;
            }
            currentDao.updateImportRoundStats(roundId, created, updated, ignored, autoRepaired);
            return new int[] {created, updated, ignored, autoRepaired};
        });
        return new ReplayIssueImportResult(workbook.rows().size(), workbook.rowsBySheet(), workbook.sandboxRows(),
                workbook.nonSandboxRows(), operationAt, counts[0], counts[1], counts[2], counts[3], 0, coverageRound);
    }

    private String incomingBatchName(List<ReplayIssueRow> rows) {
        return rows.stream().map(ReplayIssueRow::batchNo)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().findFirst().orElse(null);
    }

    private String batchFamily(String batchName) {
        if (batchName == null) return null;
        String normalized = batchName.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.startsWith("RPT")) return "RPT";
        if (normalized.startsWith("DZ")) return "DZ";
        return null;
    }

    private boolean shouldReopenBefore(LocalDateTime lastManualSaveAt, LocalDate batchRegisteredDate) {
        return lastManualSaveAt == null || lastManualSaveAt.toLocalDate().isBefore(batchRegisteredDate);
    }

    private LocalDate batchDate(List<ReplayIssueRow> rows) {
        SortedSet<LocalDate> dates = new TreeSet<>();
        for (ReplayIssueRow row : rows) {
            String batchNo = row.batchNo() == null ? "" : row.batchNo().trim();
            java.util.regex.Matcher matcher = BATCH_DATE_PATTERN.matcher(batchNo);
            if (!matcher.matches()) {
                throw invalidBatchDate(row, batchNo);
            }
            try {
                dates.add(LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE));
            } catch (DateTimeParseException exception) {
                throw invalidBatchDate(row, batchNo);
            }
        }
        if (dates.size() > 1) {
            throw new IllegalArgumentException("同一工作簿存在多个批次日期："
                    + dates.stream().map(LocalDate::toString)
                    .collect(java.util.stream.Collectors.joining("、")));
        }
        return dates.first();
    }

    private IllegalArgumentException invalidBatchDate(ReplayIssueRow row, String batchNo) {
        return new IllegalArgumentException("页签“" + row.sourceSheet() + "”第 " + (row.rowOrder() + 1)
                + " 行批次号日期格式不合法：" + (batchNo.isBlank() ? "空" : batchNo)
                + "，正确示例：RPT20260820-142055-9860 或 DZ20260820-142055-9860");
    }

    private void validateKeys(ReplayIssueExcelParser.ParsedWorkbook workbook) {
        Set<String> keys = new HashSet<>();
        for (ReplayIssueRow row : workbook.rows()) {
            if (row.issueKey() == null || row.issueKey().isBlank()) {
                throw new IllegalArgumentException("页签“" + row.sourceSheet() + "”第 "
                        + (row.rowOrder() + 1) + " 行 issue_key 不能为空");
            }
            if (!keys.add(row.issueKey().trim())) {
                throw new IllegalArgumentException("工作簿存在重复 issue_key：" + row.issueKey().trim());
            }
        }
    }

    private ReplayIssueRow newRow(ReplayIssueRow incoming, LocalDate importDate) {
        ReplayIssueRow row = refreshed(incoming, incoming, ReplayIssueStatus.NEW, importDate);
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), "", "", "", row.resolvedDate(), row.cooperationGroup(), row.resolver(),
                row.serialNo(), row.dataRepairDate(), "", row.affectedTransactionCount(), row.issueId(), row.issueKey(),
                row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(), row.importedAt(), row.issueStatus(),
                row.importDate(), row.defectRepairDate(), null, null, row.globalSerialNo());
    }

    private ReplayIssueRow refreshed(ReplayIssueRow current, ReplayIssueRow incoming,
                                     ReplayIssueStatus status, LocalDate importDate) {
        return new ReplayIssueRow(current.id(), incoming.sourceSheet(), incoming.groupName(), incoming.sandbox(), incoming.rowOrder(),
                incoming.domain(), incoming.sequenceNo(), incoming.batchNo(), incoming.transactionCode(), incoming.transactionName(),
                incoming.issueLevel(), incoming.registeredDate(), incoming.fieldName(), incoming.issueDescription(), incoming.transactionOwner(),
                current.issueType(), current.initialAnalysis(), current.finalSolution(), incoming.resolvedDate(), incoming.cooperationGroup(),
                incoming.resolver(), incoming.serialNo(), incoming.dataRepairDate(), current.remark(), incoming.affectedTransactionCount(),
                incoming.issueId(), incoming.issueKey().trim(), incoming.historicalOccurrenceCount(), incoming.firstOccurrenceDate(),
                incoming.lastOccurrenceDate(), LocalDateTime.now(clock), status, importDate,
                inheritedDefectRepairDate(current, status),
                current.cooperationPersonUsername(), current.cooperationPersonRealName(), incoming.globalSerialNo(),
                current.reviewStatus(), current.reviewerUsername(), current.reviewerRealName(), current.reviewedAt(),
                current.plannedCompletionDate());
    }

    private LocalDate inheritedDefectRepairDate(ReplayIssueRow current, ReplayIssueStatus status) {
        if (status != ReplayIssueStatus.NO_ACTION
                || current.reviewStatus() != ReplayIssueReviewStatus.APPROVED
                || current.reviewedAt() == null) {
            return null;
        }
        return current.reviewedAt().toLocalDate();
    }

    private ReplayIssueRow withStatusAndDefectDate(ReplayIssueRow row, ReplayIssueStatus status, LocalDate defectDate) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(),
                row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), status, row.importDate(), defectDate, row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo(),
                null, null, null, null, row.plannedCompletionDate());
    }

    private ReplayIssueRow withId(ReplayIssueRow row, long id) {
        return new ReplayIssueRow(id, row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(),
                row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), row.issueStatus(), row.importDate(), row.defectRepairDate(), row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo(),
                row.reviewStatus(), row.reviewerUsername(), row.reviewerRealName(), row.reviewedAt(),
                row.plannedCompletionDate());
    }

    private String snapshot(ReplayIssueRow row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题跟踪快照", exception);
        }
    }
}
