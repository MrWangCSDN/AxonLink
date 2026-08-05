package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

/** Atomically updates the five page-managed fields and appends one audit snapshot. */
@Service
public class ReplayIssueEditService {
    private static final Set<String> ISSUE_TYPES = Set.of("迁移问题", "防腐问题", "代码问题", "新核心下线", "其他问题");

    private final ReplayIssueDao dao;
    private final SysUserDao userDao;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReplayIssueEditService(ReplayIssueDao dao, SysUserDao userDao) {
        this(dao, userDao, Clock.systemDefaultZone(), new ObjectMapper().findAndRegisterModules());
    }

    ReplayIssueEditService(ReplayIssueDao dao, SysUserDao userDao, Clock clock, ObjectMapper objectMapper) {
        this.dao = dao;
        this.userDao = userDao;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public ReplayIssueRow update(long id, ReplayIssueUpdateRequest request, ReplayIssueOperator operator) {
        validate(request, operator);
        SysUser collaborator = resolveCollaborator(request.cooperationPersonUsername());
        return dao.inTransaction(currentDao -> {
            ReplayIssueRow before = currentDao.findCurrentByIdForUpdate(id);
            if (before == null) {
                throw new IllegalArgumentException("回放问题不存在");
            }
            ReplayIssueRow after = edited(before, request, collaborator);
            currentDao.updateCurrent(after);
            currentDao.insertHistory(after.id(), after.issueKey(), "人工保存", LocalDateTime.now(clock), operator,
                    after.importDate(), null, null, snapshot(before), snapshot(after), null);
            return after;
        });
    }

    private static void validate(ReplayIssueUpdateRequest request, ReplayIssueOperator operator) {
        if (request == null) throw new IllegalArgumentException("保存内容不能为空");
        request.validateTextLengths();
        if (request.issueStatus() == null || !request.issueStatus().isManuallySelectable()) {
            throw new IllegalArgumentException("该问题状态不能手工选择");
        }
        if (request.issueType() != null && !request.issueType().isBlank() && !ISSUE_TYPES.contains(request.issueType().trim())) {
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

    private ReplayIssueRow edited(ReplayIssueRow row, ReplayIssueUpdateRequest request, SysUser collaborator) {
        String issueType = request.issueType() == null ? "" : request.issueType().trim();
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(),
                row.issueDescription(), row.transactionOwner(), issueType, request.initialAnalysis(), request.finalSolution(), row.resolvedDate(),
                row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(),
                row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), request.issueStatus(), row.importDate(), row.defectRepairDate(),
                collaborator == null ? null : collaborator.getUsername(), collaborator == null ? null : collaborator.getRealName());
    }

    private String snapshot(ReplayIssueRow row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题跟踪快照", exception);
        }
    }
}
