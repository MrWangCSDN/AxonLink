package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/** Atomically updates the six page-managed fields and appends one audit snapshot. */
@Service
public class ReplayIssueEditService {
    private static final Set<String> ISSUE_TYPES = Set.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "参数问题", "平台问题", "规则差异问题", "合理差异", "外围问题", "其他问题");

    private final ReplayIssueDao dao;
    private final SysUserDao userDao;
    private final ReplayIssueMailService mailService;
    private final ReplayIssueReviewService reviewService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ReplayIssueEditService(ReplayIssueDao dao, SysUserDao userDao) {
        this(dao, userDao, null, defaultReviewService(dao, userDao), Clock.systemDefaultZone(),
                new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public ReplayIssueEditService(ReplayIssueDao dao, SysUserDao userDao, ReplayIssueMailService mailService,
                                  ReplayIssueReviewService reviewService) {
        this(dao, userDao, mailService, reviewService, Clock.systemDefaultZone(),
                new ObjectMapper().findAndRegisterModules());
    }

    ReplayIssueEditService(ReplayIssueDao dao, SysUserDao userDao, ReplayIssueMailService mailService,
                           ReplayIssueReviewService reviewService, Clock clock, ObjectMapper objectMapper) {
        this.dao = dao;
        this.userDao = userDao;
        this.mailService = mailService;
        this.reviewService = reviewService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    ReplayIssueEditService(ReplayIssueDao dao, SysUserDao userDao, ReplayIssueMailService mailService,
                           Clock clock, ObjectMapper objectMapper) {
        this(dao, userDao, mailService, defaultReviewService(dao, userDao), clock, objectMapper);
    }

    private static ReplayIssueReviewService defaultReviewService(ReplayIssueDao dao, SysUserDao userDao) {
        return new ReplayIssueReviewService(dao, userDao, new ReplayIssueReviewProperties());
    }

    public ReplayIssueRow update(long id, ReplayIssueUpdateRequest request, ReplayIssueOperator operator) {
        validate(request, operator);
        ReplayIssueRow updated = dao.inTransaction(currentDao -> {
            ReplayIssueRow before = currentDao.findCurrentByIdForUpdate(id);
            if (before == null) {
                throw new IllegalArgumentException("回放问题不存在");
            }
            if (before.issueStatus() == ReplayIssueStatus.FIXED) {
                throw new IllegalArgumentException("已修复的问题不可编辑");
            }
            if (before.issueStatus() == ReplayIssueStatus.NO_ACTION
                    && before.reviewStatus() == ReplayIssueReviewStatus.APPROVED
                    && !reviewService.isReviewer(before, operator)) {
                throw new IllegalArgumentException("该问题仅限审核人员编辑");
            }
            ReplayIssueStatus issueStatus = request.issueStatus();
            if (issueStatus == null) {
                issueStatus = before.issueStatus() == null ? ReplayIssueStatus.OPEN : before.issueStatus();
            } else if (!issueStatus.isManuallySelectable()) {
                throw new IllegalArgumentException("该问题状态不能手工选择");
            }
            SysUser collaborator = resolveCollaborator(request.cooperationPersonUsername());
            LocalDateTime operationAt = LocalDateTime.now(clock);
            ReplayIssueRow after = edited(before, request, collaborator, issueStatus, operator, operationAt);
            currentDao.updateCurrent(after);
            currentDao.insertHistoryForRound(after.id(), after.issueKey(), "人工保存", operationAt, operator,
                    after.importDate(), null, null, null, snapshot(before), snapshot(after), null,
                    currentDao.findLatestIssueRoundId(after.id()));
            currentDao.updateLatestHistoryOccurrenceBatch(after.id(), operationAt, after.batchNo());
            return after;
        });
        return updated;
    }

    private static void validate(ReplayIssueUpdateRequest request, ReplayIssueOperator operator) {
        if (request == null) throw new IllegalArgumentException("保存内容不能为空");
        request.validateTextLengths();
        if (request.issueType() == null || request.issueType().isBlank()) {
            throw new IllegalArgumentException("问题类型为必填项");
        }
        if (!ISSUE_TYPES.contains(request.issueType().trim())) {
            throw new IllegalArgumentException("未知问题类型：" + request.issueType());
        }
        if (operator == null || operator.username() == null || operator.username().isBlank()) {
            throw new IllegalArgumentException("无法获取当前操作人");
        }
    }

    private SysUser resolveCollaborator(String username) {
        if (username == null || username.isBlank()) return null;
        SysUser user = userDao.findByUsername(username.trim());
        if (user == null) throw new IllegalArgumentException("需协同人不存在");
        return user;
    }

    private ReplayIssueRow edited(ReplayIssueRow row, ReplayIssueUpdateRequest request, SysUser collaborator,
                                  ReplayIssueStatus issueStatus, ReplayIssueOperator operator,
                                  LocalDateTime operationAt) {
        String issueType = request.issueType() == null ? "" : request.issueType().trim();
        ReplayIssueReviewStatus reviewStatus = null;
        String reviewerUsername = null;
        String reviewerRealName = null;
        LocalDateTime reviewedAt = null;
        LocalDate defectRepairDate = row.defectRepairDate();
        if (issueStatus == ReplayIssueStatus.NO_ACTION) {
            issueType = "合理差异";
            if (row.issueStatus() == ReplayIssueStatus.NO_ACTION
                    && row.reviewStatus() == ReplayIssueReviewStatus.APPROVED) {
                reviewStatus = row.reviewStatus();
                reviewerUsername = row.reviewerUsername();
                reviewerRealName = row.reviewerRealName();
                reviewedAt = row.reviewedAt();
            } else {
                boolean reviewer = reviewService.isReviewer(row, operator);
                reviewStatus = reviewer ? ReplayIssueReviewStatus.APPROVED : ReplayIssueReviewStatus.PENDING;
                reviewerUsername = reviewer ? operator.username() : null;
                reviewerRealName = reviewer ? operator.realName() : null;
                reviewedAt = reviewer ? operationAt : null;
                defectRepairDate = reviewer ? operationAt.toLocalDate() : null;
            }
        } else if (issueStatus == ReplayIssueStatus.DEFERRED) {
            issueType = "迁移问题";
            if (row.issueStatus() == ReplayIssueStatus.NO_ACTION
                    && row.reviewStatus() == ReplayIssueReviewStatus.APPROVED) {
                defectRepairDate = null;
            }
        } else if (row.issueStatus() == ReplayIssueStatus.NO_ACTION
                && row.reviewStatus() == ReplayIssueReviewStatus.APPROVED) {
            defectRepairDate = null;
        }
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), issueType, request.initialAnalysis(), request.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), request.remark(), row.affectedTransactionCount(),
                row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), issueStatus, row.importDate(), defectRepairDate,
                collaborator == null ? null : collaborator.getUsername(), collaborator == null ? null : collaborator.getRealName(), row.globalSerialNo(),
                reviewStatus, reviewerUsername, reviewerRealName, reviewedAt, row.plannedCompletionDate());
    }

    private String snapshot(ReplayIssueRow row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题跟踪快照", exception);
        }
    }
}
