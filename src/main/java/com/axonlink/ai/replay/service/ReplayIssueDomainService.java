package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueDomainPermissions;
import com.axonlink.ai.replay.dto.ReplayIssueDomainTransfers;
import com.axonlink.ai.replay.dto.ReplayIssueDomainUpdateResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReplayIssueDomainService {
    public static final List<String> ALLOWED_DOMAINS = List.of(
            "存款组", "贷款组", "公共组", "结算组", "迁移组", "平台组");
    public static final String REPAIRED_LOCK_MESSAGE = "问题已有缺陷修复日期，不可转组";
    public static final String TRANSFER_LIMIT_MESSAGE = "已经达到 3 次转组上限，无法继续转组";

    private final ReplayIssueDao issueDao;
    private final SysUserDao userDao;
    private final ReplayIssueDomainProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ReplayIssueDomainService(ReplayIssueDao issueDao, SysUserDao userDao,
                                    ReplayIssueDomainProperties properties) {
        this(issueDao, userDao, properties, Clock.systemDefaultZone());
    }

    ReplayIssueDomainService(ReplayIssueDao issueDao, SysUserDao userDao,
                             ReplayIssueDomainProperties properties, Clock clock) {
        this.issueDao = issueDao;
        this.userDao = userDao;
        this.properties = properties;
        this.clock = clock;
    }

    public ReplayIssueDomainPermissions permissions(ReplayIssueOperator operator) {
        String identity = permissionIdentity(activeUser(operator));
        if (identity == null) return new ReplayIssueDomainPermissions(List.of());
        return new ReplayIssueDomainPermissions(ALLOWED_DOMAINS.stream()
                .filter(domain -> containsIdentity(properties.getEditors().get(domain), identity))
                .toList());
    }

    public ReplayIssueDomainUpdateResult update(long issueId, String targetValue, ReplayIssueOperator operator) {
        return issueDao.inTransaction(dao -> {
            ReplayIssueDao.IssueDomainState state = dao.findIssueDomainStateForUpdate(issueId);
            if (state == null) throw new IllegalArgumentException("回放问题不存在");
            if (state.defectRepairDate() != null) throw new IllegalArgumentException(REPAIRED_LOCK_MESSAGE);
            String target = normalizeTarget(targetValue);
            long transferCount = dao.countIssueDomainTransfers(issueId);
            if (target.equals(state.issueDomain())) {
                return new ReplayIssueDomainUpdateResult(issueId, state.issueDomain(), transferCount);
            }
            if (!canEdit(state.issueDomain(), operator)) {
                throw new ReplayIssueDomainForbiddenException("没有权限修改该问题所属领域");
            }
            if (transferCount >= 3) throw new IllegalArgumentException(TRANSFER_LIMIT_MESSAGE);

            LocalDateTime transferredAt = LocalDateTime.now(clock);
            dao.updateIssueDomain(issueId, target);
            dao.insertIssueDomainTransfer(issueId, state.issueKey(), state.issueDomain(), target, operator, transferredAt);
            dao.insertHistoryForRound(issueId, state.issueKey(), "修改问题所属领域", transferredAt,
                    operator, state.importDate(), state.coverageRound(), state.sourceSheet(), state.rowOrder(),
                    snapshot(state.issueDomain()), snapshot(target), null, dao.findLatestIssueRoundId(issueId),
                    state.batchNo());
            return new ReplayIssueDomainUpdateResult(issueId, target, transferCount + 1);
        });
    }

    public ReplayIssueDomainTransfers transfers(long issueId) {
        return issueDao.inTransaction(dao -> {
            if (dao.findIssueDomainStateForUpdate(issueId) == null) {
                throw new IllegalArgumentException("回放问题不存在");
            }
            return new ReplayIssueDomainTransfers(
                    dao.countIssueDomainTransfers(issueId), dao.listIssueDomainTransfers(issueId));
        });
    }

    private String normalizeTarget(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!ALLOWED_DOMAINS.contains(normalized)) {
            throw new IllegalArgumentException("问题所属领域不合法");
        }
        return normalized;
    }

    private boolean canEdit(String issueDomain, ReplayIssueOperator operator) {
        String identity = permissionIdentity(activeUser(operator));
        return identity != null && containsIdentity(properties.getEditors().get(issueDomain), identity);
    }

    private SysUser activeUser(ReplayIssueOperator operator) {
        if (operator == null || operator.username() == null || operator.username().isBlank()) return null;
        return userDao.findActiveByUsername(operator.username());
    }

    private static String permissionIdentity(SysUser user) {
        if (user == null) return null;
        if (user.getEmpNo() != null && !user.getEmpNo().isBlank()) return user.getEmpNo().trim();
        if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername().trim();
        return null;
    }

    private static boolean containsIdentity(ReplayIssueDomainProperties.EditorGroup group, String identity) {
        return group != null && group.getEmpNos().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .anyMatch(identity::equals);
    }

    private String snapshot(String issueDomain) {
        try {
            return objectMapper.writeValueAsString(Map.of("issueDomain", issueDomain));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题所属领域跟踪快照", exception);
        }
    }
}
