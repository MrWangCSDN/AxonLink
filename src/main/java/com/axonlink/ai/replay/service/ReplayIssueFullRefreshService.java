package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueFullRefreshResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** Upserts edited replay issue baseline data from the temporary workbook. */
@Service
public class ReplayIssueFullRefreshService {

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ReplayIssueFullRefreshExcelParser parser;
    private final ReplayIssueDao dao;
    private final SysUserDao userDao;
    private final ReplayIssueImportGate importGate;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReplayIssueFullRefreshService(ReplayIssueFullRefreshExcelParser parser, ReplayIssueDao dao,
                                         SysUserDao userDao, ReplayIssueImportGate importGate) {
        this(parser, dao, userDao, importGate, Clock.systemDefaultZone());
    }

    ReplayIssueFullRefreshService(ReplayIssueFullRefreshExcelParser parser, ReplayIssueDao dao,
                                  SysUserDao userDao, ReplayIssueImportGate importGate, Clock clock) {
        this.parser = parser;
        this.dao = dao;
        this.userDao = userDao;
        this.importGate = importGate;
        this.clock = clock;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public ReplayIssueFullRefreshResult fullRefresh(MultipartFile file, ReplayIssueOperator operator) throws IOException {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 50MB");
        }
        return importGate.execute(() -> rebuild(parser.parse(file), operator));
    }

    private ReplayIssueFullRefreshResult rebuild(ReplayIssueFullRefreshExcelParser.ParsedWorkbook workbook,
                                                 ReplayIssueOperator operator) {
        LocalDateTime operationAt = LocalDateTime.now(clock);
        String coverageRound = operationAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        ReplayIssueOperator effectiveOperator = operator == null ? ReplayIssueOperator.system() : operator;
        Map<String, UserMatch> userMatches = new HashMap<>();
        var normalizedRows = workbook.rows().stream()
                .map(row -> normalize(row, operationAt, userMatches))
                .toList();

        dao.inTransaction(currentDao -> {
            ReplayIssueDao.GeneratedIdentitySequences sequences = currentDao.findGeneratedIdentitySequences();
            long issueIdSequence = sequences.issueIdSequence();
            long issueKeySequence = sequences.issueKeySequence();
            for (ReplayIssueRow row : normalizedRows) {
                String issueId = row.issueId();
                if (issueId == null || issueId.isBlank()) {
                    issueId = "AUTO-%06d".formatted(++issueIdSequence);
                }
                String issueKey = row.issueKey();
                if (issueKey == null || issueKey.isBlank()) {
                    issueKey = "AUTO-KEY-%06d".formatted(++issueKeySequence);
                }
                ReplayIssueRow identified = withIdentities(row, issueId, issueKey);
                ReplayIssueRow existing = currentDao.findCurrentByIssueKeyForUpdate(issueKey);
                ReplayIssueRow persisted;
                String operationType;
                String beforeSnapshot;
                if (existing == null) {
                    long id = currentDao.insertCurrent(identified);
                    currentDao.updateCoverageRound(id, coverageRound);
                    persisted = withId(identified, id);
                    operationType = "全量基础数据导入";
                    beforeSnapshot = null;
                } else {
                    persisted = withPlannedCompletionDate(withId(identified, existing.id()), existing.plannedCompletionDate());
                    beforeSnapshot = snapshot(existing);
                    currentDao.updateCurrent(persisted);
                    currentDao.updateCoverageRound(existing.id(), coverageRound);
                    operationType = "全量基础数据覆盖";
                }
                String snapshot = snapshot(persisted);
                currentDao.insertHistory(persisted.id(), persisted.issueKey(), operationType, operationAt,
                        effectiveOperator, operationAt.toLocalDate(), persisted.sourceSheet(),
                        persisted.rowOrder() + 1, beforeSnapshot, snapshot, snapshot);
                currentDao.upsertOccurrenceBatch(persisted.id(), persisted.issueKey(), persisted.batchNo(), operationAt, persisted.issueStatus());
                currentDao.updateLatestHistoryOccurrenceBatch(persisted.id(), operationAt, persisted.batchNo());
                currentDao.updateLatestHistoryCoverageRound(persisted.issueKey(), operationAt, coverageRound);
            }
            return null;
        });

        return new ReplayIssueFullRefreshResult(normalizedRows.size(), workbook.rowsBySheet(),
                workbook.generatedIdentityRows(), workbook.sandboxRows(), workbook.nonSandboxRows(), operationAt, coverageRound);
    }

    private ReplayIssueRow normalize(ReplayIssueRow row, LocalDateTime operationAt,
                                     Map<String, UserMatch> userMatches) {
        String rawName = row.cooperationPersonRealName() == null ? "" : row.cooperationPersonRealName().trim();
        UserMatch match = rawName.isEmpty()
                ? UserMatch.EMPTY
                : userMatches.computeIfAbsent(rawName, this::resolveUser);
        String username = match.username();
        String realName = match.realName() == null ? emptyToNull(rawName) : match.realName();
        return new ReplayIssueRow(null, row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
                row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
                row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(),
                row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(), null, row.remark(),
                row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(),
                row.firstOccurrenceDate(), row.lastOccurrenceDate(), operationAt, row.issueStatus(),
                operationAt.toLocalDate(), null, username, realName, row.globalSerialNo());
    }

    private UserMatch resolveUser(String realName) {
        var users = userDao.findActiveByExactRealName(realName);
        if (users.size() != 1) {
            return new UserMatch(null, realName);
        }
        SysUser user = users.get(0);
        return new UserMatch(user.getUsername(), user.getRealName());
    }

    private ReplayIssueRow withId(ReplayIssueRow row, long id) {
        return new ReplayIssueRow(id, row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
                row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
                row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(),
                row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(),
                row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(),
                row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), row.issueStatus(), row.importDate(), row.defectRepairDate(),
                row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo());
    }

    private ReplayIssueRow withIdentities(ReplayIssueRow row, String issueId, String issueKey) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
                row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
                row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(),
                row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(),
                row.remark(), row.affectedTransactionCount(), issueId, issueKey,
                row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), row.issueStatus(), row.importDate(), row.defectRepairDate(),
                row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo());
    }

    private ReplayIssueRow withPlannedCompletionDate(ReplayIssueRow row, java.time.LocalDate plannedCompletionDate) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
                row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
                row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(),
                row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(),
                row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(),
                row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), row.issueStatus(), row.importDate(), row.defectRepairDate(),
                row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo(),
                row.reviewStatus(), row.reviewerUsername(), row.reviewerRealName(), row.reviewedAt(),
                plannedCompletionDate);
    }

    private String snapshot(ReplayIssueRow row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题跟踪快照", exception);
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record UserMatch(String username, String realName) {
        private static final UserMatch EMPTY = new UserMatch(null, null);
    }
}
