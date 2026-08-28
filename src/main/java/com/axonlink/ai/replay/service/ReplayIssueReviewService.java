package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueReviewPermissions;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Authorization and reviewer-contact projection for no-action issue review. */
@Service
public class ReplayIssueReviewService {
    private final ReplayIssueDao issueDao;
    private final SysUserDao userDao;
    private final ReplayTransactionPersonDao transactionPersonDao;
    private final ReplayIssueReviewProperties properties;
    private final Clock clock;

    @Autowired
    public ReplayIssueReviewService(ReplayIssueDao issueDao, SysUserDao userDao,
                                    ReplayTransactionPersonDao transactionPersonDao,
                                    ReplayIssueReviewProperties properties) {
        this(issueDao, userDao, transactionPersonDao, properties, Clock.systemDefaultZone());
    }

    /** Compatibility constructor for focused tests and older callers. */
    public ReplayIssueReviewService(ReplayIssueDao issueDao, SysUserDao userDao,
                                    ReplayIssueReviewProperties properties) {
        this(issueDao, userDao, new ReplayTransactionPersonDao(issueDao.jdbc()), properties,
                Clock.systemDefaultZone());
    }

    ReplayIssueReviewService(ReplayIssueDao issueDao, SysUserDao userDao,
                             ReplayIssueReviewProperties properties, Clock clock) {
        this(issueDao, userDao, new ReplayTransactionPersonDao(issueDao.jdbc()), properties, clock);
    }

    ReplayIssueReviewService(ReplayIssueDao issueDao, SysUserDao userDao,
                             ReplayTransactionPersonDao transactionPersonDao,
                             ReplayIssueReviewProperties properties, Clock clock) {
        this.issueDao = issueDao;
        this.userDao = userDao;
        this.transactionPersonDao = transactionPersonDao;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isReviewer(String groupName, ReplayIssueOperator operator) {
        if (groupName == null || operator == null || operator.username() == null) return false;
        SysUser user = userDao.findActiveByUsername(operator.username());
        if (user == null || user.getEmpNo() == null) return false;
        ReplayIssueReviewProperties.ReviewerGroup group = properties.getReviewers().get(groupName);
        return group != null && group.getEmpNos().stream()
                .filter(empNo -> empNo != null && !empNo.isBlank())
                .map(String::trim)
                .anyMatch(user.getEmpNo().trim()::equals);
    }

    public boolean isReviewer(ReplayIssueRow issue, ReplayIssueOperator operator) {
        if (issue == null) return false;
        if (isReviewer(issue.groupName(), operator)) return true;
        SysUser user = activeUser(operator);
        return user != null && user.getEmpNo() != null
                && transactionPersonDao.findBankOwnerEmpNosByTransactionCode(issue.transactionCode()).stream()
                .anyMatch(user.getEmpNo().trim()::equals);
    }

    public ReplayIssueReviewPermissions permissions(ReplayIssueOperator operator) {
        List<String> reviewableGroups = properties.getReviewers().keySet().stream()
                .filter(groupName -> isReviewer(groupName, operator)).toList();
        Map<String, List<String>> contacts = new LinkedHashMap<>();
        properties.getReviewers().forEach((groupName, group) -> {
            List<String> names = new ArrayList<>();
            for (String empNo : group.getEmpNos()) {
                SysUser user = userDao.findActiveByEmpNo(empNo);
                if (user != null && user.getRealName() != null && !user.getRealName().isBlank()) {
                    names.add(user.getRealName());
                }
            }
            contacts.put(groupName, List.copyOf(names));
        });
        SysUser currentUser = activeUser(operator);
        List<String> reviewableTransactionCodes = currentUser == null || currentUser.getEmpNo() == null
                ? List.of()
                : transactionPersonDao.findTransactionCodesByBankOwnerEmpNo(currentUser.getEmpNo());
        return new ReplayIssueReviewPermissions(List.copyOf(reviewableGroups), contacts,
                reviewableTransactionCodes);
    }

    public List<String> reviewerNames(ReplayIssueRow issue) {
        if (issue == null) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String empNo : transactionPersonDao.findBankOwnerEmpNosByTransactionCode(issue.transactionCode())) {
            addRealName(names, userDao.findActiveByEmpNo(empNo));
        }
        ReplayIssueReviewProperties.ReviewerGroup group = properties.getReviewers().get(issue.groupName());
        if (group != null) {
            for (String empNo : group.getEmpNos()) addRealName(names, userDao.findActiveByEmpNo(empNo));
        }
        return List.copyOf(names);
    }

    public ReplayIssueRow approve(long issueId, ReplayIssueOperator operator) {
        return issueDao.inTransaction(dao -> {
            ReplayIssueRow before = dao.findCurrentByIdForUpdate(issueId);
            if (before == null) throw new IllegalArgumentException("回放问题不存在");
            if (before.issueStatus() == ReplayIssueStatus.NO_ACTION
                    && before.reviewStatus() == ReplayIssueReviewStatus.APPROVED) {
                return before;
            }
            if (before.issueStatus() != ReplayIssueStatus.NO_ACTION
                    || before.reviewStatus() != ReplayIssueReviewStatus.PENDING) {
                throw new IllegalArgumentException("当前问题不是待审核状态");
            }
            if (!isReviewer(before, operator)) {
                List<String> contacts = reviewerNames(before);
                throw new ReplayIssueReviewForbiddenException(
                        contacts.isEmpty() ? "没有审核权限" :
                                "没有权限，请联系" + String.join("、", contacts) + "进行审核");
            }
            java.time.LocalDateTime reviewedAt = java.time.LocalDateTime.now(clock);
            ReplayIssueRow after = withApprovedReview(before, operator, reviewedAt);
            dao.updateCurrent(after);
            dao.insertHistoryForRound(after.id(), after.issueKey(), "审核通过", reviewedAt, operator,
                    after.importDate(), null, null, null, snapshot(before), snapshot(after), null,
                    dao.findLatestIssueRoundId(after.id()));
            dao.updateLatestHistoryOccurrenceBatch(after.id(), reviewedAt, after.batchNo());
            return after;
        });
    }

    private SysUser activeUser(ReplayIssueOperator operator) {
        if (operator == null || operator.username() == null || operator.username().isBlank()) return null;
        return userDao.findActiveByUsername(operator.username());
    }

    private static void addRealName(LinkedHashSet<String> names, SysUser user) {
        if (user != null && user.getRealName() != null && !user.getRealName().isBlank()) {
            names.add(user.getRealName().trim());
        }
    }

    private static ReplayIssueRow withApprovedReview(ReplayIssueRow row, ReplayIssueOperator operator,
                                                     java.time.LocalDateTime reviewedAt) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
                row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
                row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), "合理差异", row.initialAnalysis(), row.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(),
                row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(),
                row.firstOccurrenceDate(), row.lastOccurrenceDate(), row.importedAt(), ReplayIssueStatus.NO_ACTION,
                row.importDate(), reviewedAt.toLocalDate(), row.cooperationPersonUsername(),
                row.cooperationPersonRealName(), row.globalSerialNo(), ReplayIssueReviewStatus.APPROVED,
                operator.username(), operator.realName(), reviewedAt, row.plannedCompletionDate());
    }

    private static String snapshot(ReplayIssueRow row) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题跟踪快照", exception);
        }
    }
}
