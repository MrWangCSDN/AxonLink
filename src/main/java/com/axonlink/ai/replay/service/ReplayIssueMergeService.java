package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Merges an imported workbook into the current issue projection by issue_key. */
@Service
public class ReplayIssueMergeService {

    private final ReplayIssueDao dao;
    private final Clock clock;
    private final ObjectMapper objectMapper;

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
        if (workbook == null || workbook.rows().isEmpty()) {
            throw new IllegalArgumentException("目标页签中没有可导入数据");
        }
        validateKeys(workbook);
        LocalDate effectiveDate = importDate == null ? LocalDate.now(clock) : importDate;
        ReplayIssueOperator effectiveOperator = operator == null ? ReplayIssueOperator.system() : operator;
        LocalDateTime operationAt = LocalDateTime.now(clock);
        Set<String> incomingKeys = workbook.rows().stream().map(row -> row.issueKey().trim()).collect(java.util.stream.Collectors.toSet());

        int[] counts = dao.inTransaction(currentDao -> {
            int created = 0;
            int updated = 0;
            int ignored = 0;
            for (ReplayIssueRow incoming : workbook.rows()) {
                String key = incoming.issueKey().trim();
                ReplayIssueRow current = currentDao.findCurrentByIssueKeyForUpdate(key);
                if (current == null) {
                    ReplayIssueRow createdRow = newRow(incoming, effectiveDate);
                    long id = currentDao.insertCurrent(createdRow);
                    ReplayIssueRow after = withId(createdRow, id);
                    currentDao.insertHistory(id, key, "导入新增", operationAt, effectiveOperator, effectiveDate,
                            incoming.sourceSheet(), incoming.rowOrder() + 1, null, snapshot(after), snapshot(incoming));
                    created++;
                    continue;
                }
                ReplayIssueStatus status = current.issueStatus() == null ? ReplayIssueStatus.OPEN : current.issueStatus();
                if (status == ReplayIssueStatus.OPEN || status == ReplayIssueStatus.ANALYZING
                        || status == ReplayIssueStatus.DEFERRED) {
                    currentDao.insertHistory(current.id(), key, "重复导入忽略", operationAt, effectiveOperator, effectiveDate,
                            incoming.sourceSheet(), incoming.rowOrder() + 1, snapshot(current), snapshot(current), snapshot(incoming));
                    ignored++;
                    continue;
                }
                ReplayIssueStatus nextStatus = status == ReplayIssueStatus.PENDING_VERIFICATION
                        ? ReplayIssueStatus.REOPENED
                        : status == ReplayIssueStatus.FIXED ? ReplayIssueStatus.OPEN : status;
                boolean resetImportDate = status == ReplayIssueStatus.FIXED;
                ReplayIssueRow refreshed = refreshed(current, incoming, nextStatus,
                        resetImportDate ? effectiveDate : current.importDate());
                currentDao.updateCurrent(refreshed);
                currentDao.insertHistory(current.id(), key,
                        status == ReplayIssueStatus.FIXED ? "已修复问题重新打开" : "问题重新出现",
                        operationAt, effectiveOperator, effectiveDate, incoming.sourceSheet(), incoming.rowOrder() + 1,
                        snapshot(current), snapshot(refreshed), snapshot(incoming));
                updated++;
            }
            int autoRepaired = 0;
            for (ReplayIssueRow current : currentDao.findPendingVerificationMissing(incomingKeys)) {
                ReplayIssueRow fixed = withStatusAndDefectDate(current, ReplayIssueStatus.FIXED, effectiveDate);
                currentDao.updateCurrent(fixed);
                currentDao.insertHistory(current.id(), current.issueKey(), "问题自动修复", operationAt, effectiveOperator,
                        effectiveDate, null, null, snapshot(current), snapshot(fixed), null);
                autoRepaired++;
            }
            return new int[] {created, updated, ignored, autoRepaired};
        });
        return new ReplayIssueImportResult(workbook.rows().size(), workbook.rowsBySheet(), workbook.sandboxRows(),
                workbook.nonSandboxRows(), operationAt, counts[0], counts[1], counts[2], counts[3], 0);
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
        ReplayIssueRow row = refreshed(incoming, incoming, ReplayIssueStatus.OPEN, importDate);
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), "", "", "", row.resolvedDate(), row.cooperationGroup(), row.resolver(),
                row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(),
                row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(), row.importedAt(), row.issueStatus(),
                row.importDate(), row.defectRepairDate(), null, null);
    }

    private ReplayIssueRow refreshed(ReplayIssueRow current, ReplayIssueRow incoming,
                                     ReplayIssueStatus status, LocalDate importDate) {
        return new ReplayIssueRow(current.id(), incoming.sourceSheet(), incoming.groupName(), incoming.sandbox(), incoming.rowOrder(),
                incoming.domain(), incoming.sequenceNo(), incoming.batchNo(), incoming.transactionCode(), incoming.transactionName(),
                incoming.issueLevel(), incoming.registeredDate(), incoming.fieldName(), incoming.issueDescription(), incoming.transactionOwner(),
                current.issueType(), current.initialAnalysis(), current.finalSolution(), incoming.resolvedDate(), incoming.cooperationGroup(),
                incoming.resolver(), incoming.serialNo(), incoming.dataRepairDate(), incoming.remark(), incoming.affectedTransactionCount(),
                incoming.issueId(), incoming.issueKey().trim(), incoming.historicalOccurrenceCount(), incoming.firstOccurrenceDate(),
                incoming.lastOccurrenceDate(), LocalDateTime.now(clock), status, importDate, null,
                current.cooperationPersonUsername(), current.cooperationPersonRealName());
    }

    private ReplayIssueRow withStatusAndDefectDate(ReplayIssueRow row, ReplayIssueStatus status, LocalDate defectDate) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(),
                row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), status, row.importDate(), defectDate, row.cooperationPersonUsername(), row.cooperationPersonRealName());
    }

    private ReplayIssueRow withId(ReplayIssueRow row, long id) {
        return new ReplayIssueRow(id, row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(),
                row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), row.issueStatus(), row.importDate(), row.defectRepairDate(), row.cooperationPersonUsername(), row.cooperationPersonRealName());
    }

    private String snapshot(ReplayIssueRow row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题跟踪快照", exception);
        }
    }
}
