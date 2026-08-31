package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDatePermissions;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
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
    private final ReplayIssuePlanDateProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReplayIssuePlanDateService(ReplayIssueDao issueDao, SysUserDao userDao,
                                      ReplayIssuePlanDateProperties properties) {
        this(issueDao, userDao, properties, Clock.systemDefaultZone());
    }

    ReplayIssuePlanDateService(ReplayIssueDao issueDao, SysUserDao userDao,
                               ReplayIssuePlanDateProperties properties, Clock clock) {
        this.issueDao = issueDao;
        this.userDao = userDao;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public ReplayIssuePlanDatePermissions permissions(ReplayIssueOperator operator) {
        SysUser user = activeUser(operator);
        String identity = permissionIdentity(user);
        if (identity == null) {
            return new ReplayIssuePlanDatePermissions(List.of());
        }
        List<String> groups = properties.getEditors().entrySet().stream()
                .filter(entry -> containsIdentity(entry.getValue(), identity))
                .map(java.util.Map.Entry::getKey)
                .toList();
        return new ReplayIssuePlanDatePermissions(groups);
    }

    public ReplayIssueRow update(long issueId, String value, ReplayIssueOperator operator) {
        return issueDao.inTransaction(dao -> {
            ReplayIssueRow before = dao.findCurrentByIdForUpdate(issueId);
            if (before == null) throw new IllegalArgumentException("回放问题不存在");
            if (before.defectRepairDate() != null) {
                throw new IllegalArgumentException(REPAIRED_DATE_LOCK_MESSAGE);
            }
            if (!canEdit(before.groupName(), operator)) {
                throw new ReplayIssuePlanDateForbiddenException("没有权限编辑该领域的计划验证日期");
            }
            LocalDate plannedDate = parse(value);
            if (Objects.equals(before.plannedCompletionDate(), plannedDate)) return before;
            validateOccurrenceBoundary(before.firstOccurrenceDate(), plannedDate);

            dao.updatePlannedCompletionDate(issueId, plannedDate);
            ReplayIssueRow after = dao.findCurrentByIdForUpdate(issueId);
            LocalDateTime operationAt = LocalDateTime.now(clock);
            dao.insertHistoryForRound(after.id(), after.issueKey(), "修改计划验证日期", operationAt,
                    operator, after.importDate(), null, null, null, snapshot(before), snapshot(after), null,
                    dao.findLatestIssueRoundId(after.id()));
            dao.updateLatestHistoryOccurrenceBatch(after.id(), operationAt, after.batchNo());
            return after;
        });
    }

    private boolean canEdit(String groupName, ReplayIssueOperator operator) {
        if (groupName == null) return false;
        SysUser user = activeUser(operator);
        String identity = permissionIdentity(user);
        return identity != null && containsIdentity(properties.getEditors().get(groupName), identity);
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
