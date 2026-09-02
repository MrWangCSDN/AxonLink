package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDatePermissions;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDateChanges;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDateUpdateResult;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;

/** Permission-checked, audited maintenance of replay issue planned completion dates. */
@Service
public class ReplayIssuePlanDateService {
    public static final String INVALID_DATE_MESSAGE = "填写日期格式不合法，请按 2026-08-26 格式填写";
    public static final String REPAIRED_DATE_LOCK_MESSAGE = "问题已有缺陷修复日期，计划验证日期不可修改";
    public static final String INVALID_FIRST_OCCURRENCE_MESSAGE = "首次出现日期无效，无法填写计划验证日期";
    public static final String DATE_LIMIT_MESSAGE = "计划验证日期不能超过首次出现日期后 7 个自然日";

    private final ReplayIssueDao issueDao;
    private final SysUserDao userDao;
    private final ReplayTransactionPersonDao personDao;
    private final ReplayIssuePlanDateProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReplayIssuePlanDateService(ReplayIssueDao issueDao, SysUserDao userDao,
                                      ReplayTransactionPersonDao personDao,
                                      ReplayIssuePlanDateProperties properties) {
        this(issueDao, userDao, personDao, properties, Clock.systemDefaultZone());
    }

    ReplayIssuePlanDateService(ReplayIssueDao issueDao, SysUserDao userDao,
                               ReplayTransactionPersonDao personDao,
                               ReplayIssuePlanDateProperties properties, Clock clock) {
        this.issueDao = issueDao;
        this.userDao = userDao;
        this.personDao = personDao;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public ReplayIssuePlanDatePermissions permissions(ReplayIssueOperator operator) {
        SysUser user = activeUser(operator);
        String identity = permissionIdentity(user);
        if (identity == null) {
            return new ReplayIssuePlanDatePermissions(List.of(), List.of(), List.of());
        }
        List<String> regularGroups = properties.getEditors().entrySet().stream()
                .filter(entry -> containsIdentity(entry.getValue(), identity))
                .map(java.util.Map.Entry::getKey)
                .toList();
        List<String> advancedGroups = properties.getAdvancedEditors().entrySet().stream()
                .filter(entry -> containsIdentity(entry.getValue(), identity))
                .map(java.util.Map.Entry::getKey)
                .toList();
        LinkedHashSet<String> editableGroups = new LinkedHashSet<>(regularGroups);
        editableGroups.addAll(advancedGroups);
        return new ReplayIssuePlanDatePermissions(
                List.copyOf(editableGroups), advancedGroups, ownedTransactionCodes(user));
    }

    public ReplayIssuePlanDateUpdateResult update(long issueId, String value, ReplayIssueOperator operator) {
        return issueDao.inTransaction(dao -> {
            ReplayIssueRow before = dao.findCurrentByIdForUpdate(issueId);
            if (before == null) throw new IllegalArgumentException("回放问题不存在");
            if (before.defectRepairDate() != null) {
                throw new IllegalArgumentException(REPAIRED_DATE_LOCK_MESSAGE);
            }
            if (!canEdit(before, operator)) {
                throw new ReplayIssuePlanDateForbiddenException("没有计划验证日期编辑权限");
            }
            boolean dateLimitBypassed = canBypassDateLimit(before, operator);
            LocalDate plannedDate = parse(value);
            if (Objects.equals(before.plannedCompletionDate(), plannedDate)) {
                return result(before, dao.countPlanDateChanges(issueId));
            }
            if (!dateLimitBypassed) {
                validateOccurrenceBoundary(before.firstOccurrenceDate(), plannedDate);
            }

            dao.updatePlannedCompletionDate(issueId, plannedDate);
            ReplayIssueRow after = dao.findCurrentByIdForUpdate(issueId);
            LocalDateTime operationAt = LocalDateTime.now(clock);
            dao.insertPlanDateChange(after.id(), after.issueKey(), plannedDate, operator, operationAt);
            dao.insertHistoryForRound(after.id(), after.issueKey(), "修改计划验证日期", operationAt,
                    operator, after.importDate(), null, null, null, snapshot(before), snapshot(after), null,
                    dao.findLatestIssueRoundId(after.id()));
            dao.updateLatestHistoryOccurrenceBatch(after.id(), operationAt, after.batchNo());
            return result(after, dao.countPlanDateChanges(issueId));
        });
    }

    public ReplayIssuePlanDateChanges changes(long issueId) {
        return issueDao.inTransaction(dao -> {
            if (dao.findCurrentByIdForUpdate(issueId) == null) {
                throw new IllegalArgumentException("回放问题不存在");
            }
            return new ReplayIssuePlanDateChanges(
                    dao.countPlanDateChanges(issueId), dao.listPlanDateChanges(issueId));
        });
    }

    private static ReplayIssuePlanDateUpdateResult result(ReplayIssueRow row, long changeCount) {
        return new ReplayIssuePlanDateUpdateResult(row.id(), row.plannedCompletionDate(), changeCount);
    }

    private boolean canEdit(ReplayIssueRow row, ReplayIssueOperator operator) {
        if (row == null) return false;
        SysUser user = activeUser(operator);
        String identity = permissionIdentity(user);
        boolean groupAllowed = identity != null
                && containsIdentity(properties.getEditors().get(row.groupName()), identity);
        boolean advancedGroupAllowed = identity != null
                && containsIdentity(properties.getAdvancedEditors().get(row.groupName()), identity);
        return groupAllowed || advancedGroupAllowed || ownedTransactionCodes(user).contains(row.transactionCode());
    }

    private boolean canBypassDateLimit(ReplayIssueRow row, ReplayIssueOperator operator) {
        if (row == null) return false;
        String identity = permissionIdentity(activeUser(operator));
        return identity != null
                && containsIdentity(properties.getAdvancedEditors().get(row.groupName()), identity);
    }

    private List<String> ownedTransactionCodes(SysUser user) {
        if (user == null || user.getEmpNo() == null || user.getEmpNo().isBlank()
                || user.getUsername() == null || user.getUsername().isBlank()) {
            return List.of();
        }
        return personDao.findTransactionCodesByDeveloperUsername(user.getUsername().trim());
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

    private static boolean containsIdentity(ReplayIssuePlanDateProperties.EditorGroup group, String identity) {
        return group != null && group.getEmpNos().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .anyMatch(identity::equals);
    }

    private static LocalDate parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!normalized.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(INVALID_DATE_MESSAGE);
        }
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(INVALID_DATE_MESSAGE);
        }
    }

    private static void validateOccurrenceBoundary(String firstOccurrenceValue, LocalDate plannedDate) {
        if (plannedDate == null) return;
        LocalDate firstOccurrenceDate = parseFirstOccurrenceDate(firstOccurrenceValue);
        if (firstOccurrenceDate == null) {
            throw new IllegalArgumentException(INVALID_FIRST_OCCURRENCE_MESSAGE);
        }
        if (plannedDate.isAfter(firstOccurrenceDate.plusDays(7))) {
            throw new IllegalArgumentException(DATE_LIMIT_MESSAGE);
        }
    }

    private static LocalDate parseFirstOccurrenceDate(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() < 10 || !normalized.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        if (normalized.length() > 10 && normalized.charAt(10) != ' ' && normalized.charAt(10) != 'T') {
            return null;
        }
        try {
            return LocalDate.parse(normalized.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String snapshot(ReplayIssueRow row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问题跟踪快照", exception);
        }
    }
}
