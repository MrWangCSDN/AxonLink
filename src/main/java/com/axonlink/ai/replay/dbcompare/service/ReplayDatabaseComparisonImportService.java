package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseValidatedTable;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetail;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetailDraft;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditEvent;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditOperation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportError;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareState;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReplayDatabaseComparisonImportService {

    private static final Actor SYSTEM_ACTOR = new Actor("SYSTEM", "SYSTEM", "系统");
    private static final List<String> SHEET_ORDER = List.of("存款", "贷款", "公共", "结算");

    private final ReplayDatabaseComparisonExcelParser parser;
    private final ReplayBaseMetadataService metadataService;
    private final ReplayDatabaseComparisonDao dao;
    private final ReplayDatabaseComparisonReviserResolver reviserResolver;
    private final ReplayDatabaseComparisonAuditDiff auditDiff;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public ReplayDatabaseComparisonImportService(
            ReplayDatabaseComparisonExcelParser parser,
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao dao,
            ReplayDatabaseComparisonVersionDao versionDao,
            SysUserDao userDao,
            ReplayDatabaseComparisonAuditDiff auditDiff,
            JdbcTemplate diiResultJdbcTemplate) {
        this(parser, metadataService, dao, versionDao, userDao, auditDiff,
                diiResultJdbcTemplate, Clock.systemDefaultZone());
    }

    ReplayDatabaseComparisonImportService(
            ReplayDatabaseComparisonExcelParser parser,
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao dao,
            ReplayDatabaseComparisonVersionDao versionDao,
            SysUserDao userDao,
            ReplayDatabaseComparisonAuditDiff auditDiff,
            JdbcTemplate diiResultJdbcTemplate,
            Clock clock) {
        this.parser = parser;
        this.metadataService = metadataService;
        this.dao = dao;
        this.reviserResolver = new ReplayDatabaseComparisonReviserResolver(userDao);
        this.auditDiff = auditDiff;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.clock = clock;
    }

    public ReplayDbCompareImportResult importFile(
            InputStream input,
            ReplayIssueOperator operator) throws IOException {
        requireImporter(operator);
        ReplayDatabaseComparisonExcelParser.ParsedImport parsed = parser.parse(input);
        List<ReplayDbCompareImportError> errors = new ArrayList<>(parsed.errors());
        List<PreparedImport> prepared = new ArrayList<>();
        for (ReplayDatabaseComparisonExcelParser.ParsedTable table : parsed.tables()) {
            prepare(table, prepared, errors);
        }
        if (!errors.isEmpty()) {
            errors.sort(errorOrder());
            return failed(parsed.tables().size(), errors);
        }

        Counts counts = transactionTemplate.execute(status -> writeAll(prepared));
        if (counts == null) {
            throw new IllegalStateException("数据库比对字段导入事务未完成");
        }
        return new ReplayDbCompareImportResult(
                true, parsed.tables().size(), counts.created, counts.updated, counts.unchanged, List.of());
    }

    private void prepare(
            ReplayDatabaseComparisonExcelParser.ParsedTable parsed,
            List<PreparedImport> prepared,
            List<ReplayDbCompareImportError> errors) {
        Map<String, ReplayDatabaseComparisonReviserResolver.Resolution> revisers = new LinkedHashMap<>();
        boolean revisersValid = true;
        for (ReplayDatabaseComparisonExcelParser.ParsedRow row : parsed.rows()) {
            if (row.reviserInput().isBlank()) {
                continue;
            }
            ReplayDatabaseComparisonReviserResolver.Resolution resolution = revisers.computeIfAbsent(
                    row.reviserInput(), reviserResolver::resolve);
            if (!resolution.valid()) {
                errors.add(error(row, resolution.reason()));
                revisersValid = false;
            }
        }
        ReplayDatabaseComparisonReviserResolver.Resolution reviser = parsed.reviserInput().isBlank()
                ? reviserResolver.resolve("")
                : revisers.get(parsed.reviserInput());

        boolean metadataValid = true;
        for (ReplayDatabaseComparisonExcelParser.ParsedRow row : parsed.rows()) {
            try {
                metadataService.requireTableWithColumns(row.tableName(), List.of(row.fieldName()));
            } catch (RuntimeException exception) {
                errors.add(error(row, safeMessage(exception)));
                metadataValid = false;
            }
        }
        if (!metadataValid || !revisersValid) {
            return;
        }

        try {
            ReplayBaseValidatedTable incoming = metadataService.requireTableWithColumns(
                    parsed.tableName(), parsed.fieldNames());
            ReplayDbCompareRegistration existing = dao.findBySchemaAndTable(
                    incoming.schemaName(), incoming.tableName());
            if (existing != null && existing.deleted()) {
                parsed.rows().forEach(row -> errors.add(error(row, "该表已删除，请在页面中重新登记")));
                return;
            }
            List<String> combinedNames = mergeFieldNames(existing, parsed.fieldNames());
            ReplayBaseValidatedTable trusted = existing == null
                    ? incoming
                    : metadataService.requireTableWithColumns(parsed.tableName(), combinedNames);
            prepared.add(new PreparedImport(
                    parsed, trusted, reviser.user(), existing,
                    trustedFields(trusted.columns(), combinedNames)));
        } catch (RuntimeException exception) {
            parsed.rows().forEach(row -> errors.add(error(row, safeMessage(exception))));
        }
    }

    private Counts writeAll(List<PreparedImport> prepared) {
        Counts counts = new Counts();
        LocalDateTime now = LocalDateTime.now(clock);
        for (PreparedImport item : prepared) {
            if (item.existing == null) {
                writeCreate(item, now);
                counts.created++;
            } else if (writeUpdate(item, now)) {
                counts.updated++;
            } else {
                counts.unchanged++;
            }
        }
        return counts;
    }

    private void writeCreate(PreparedImport item, LocalDateTime now) {
        ReplayDbCompareRegistration registration = new ReplayDbCompareRegistration(
                null, item.metadata.schemaName(), item.metadata.tableName(), item.metadata.tableComment(),
                item.parsed.domainName(), empNo(item.reviser), username(item.reviser), name(item.reviser),
                null, null, LocalDate.now(clock), false, null, null, null, 0,
                SYSTEM_ACTOR.empNo, SYSTEM_ACTOR.name, now,
                SYSTEM_ACTOR.empNo, SYSTEM_ACTOR.name, now, item.fields);
        List<ReplayDbCompareAuditDetailDraft> details =
                auditDiff.compare(null, state(registration), ReplayDbCompareAuditOperation.IMPORT);
        long id = dao.insertRegistration(registration);
        writeAudit(id, registration.schemaName(), registration.tableName(), 0, now, details);
    }

    private boolean writeUpdate(PreparedImport item, LocalDateTime now) {
        ReplayDbCompareRegistration current = dao.findByIdIncludingDeleted(item.existing.id());
        if (current == null || current.deleted() || current.version() != item.existing.version()) {
            throw new ReplayDatabaseComparisonVersionConflictException();
        }
        ReplayDbCompareRegistration target = new ReplayDbCompareRegistration(
                current.id(), current.schemaName(), current.tableName(), item.metadata.tableComment(),
                item.parsed.domainName(), empNo(item.reviser), username(item.reviser), name(item.reviser),
                current.groupOwnerEmpNo(), current.groupOwnerName(), current.registeredDate(),
                false, null, null, null, current.version() + 1,
                current.createdBy(), current.createdName(), current.createdAt(),
                SYSTEM_ACTOR.empNo, SYSTEM_ACTOR.name, now, item.fields,
                current.whereCondition(), current.compareLimit(), null);
        List<ReplayDbCompareAuditDetailDraft> details =
                auditDiff.compare(state(current), state(target), ReplayDbCompareAuditOperation.IMPORT);
        if (details.isEmpty()) {
            return false;
        }
        if (!dao.updateRegistration(
                current.id(), current.version(), target.tableComment(), target.domainName(),
                target.groupOwnerEmpNo(), target.groupOwnerName(), target.registeredDate(),
                target.reviserEmpNo(), target.reviserUsername(), target.reviserName(),
                SYSTEM_ACTOR.empNo, SYSTEM_ACTOR.name, now)) {
            throw new ReplayDatabaseComparisonVersionConflictException();
        }
        dao.replaceFields(current.id(), target.fields(), now);
        writeAudit(current.id(), current.schemaName(), current.tableName(),
                current.version() + 1, now, details);
        return true;
    }

    private void writeAudit(
            long registrationId,
            String schemaName,
            String tableName,
            long version,
            LocalDateTime now,
            List<ReplayDbCompareAuditDetailDraft> drafts) {
        long eventId = dao.insertAuditEvent(new ReplayDbCompareAuditEvent(
                null, registrationId, schemaName, tableName, ReplayDbCompareAuditOperation.IMPORT,
                version, drafts.size(), "Excel 初始化导入",
                SYSTEM_ACTOR.empNo, SYSTEM_ACTOR.username, SYSTEM_ACTOR.name, now));
        List<ReplayDbCompareAuditDetail> details = java.util.stream.IntStream.range(0, drafts.size())
                .mapToObj(index -> {
                    ReplayDbCompareAuditDetailDraft draft = drafts.get(index);
                    return new ReplayDbCompareAuditDetail(
                            null, eventId, index + 1, draft.changeType(), draft.fieldCode(),
                            draft.fieldLabel(), draft.beforeValue(), draft.afterValue(), now);
                }).toList();
        dao.insertAuditDetails(eventId, details, now);
    }

    private List<String> mergeFieldNames(
            ReplayDbCompareRegistration existing,
            List<String> incoming) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (existing != null) {
            existing.fields().stream().map(ReplayDbCompareField::columnName)
                    .map(this::identifier).forEach(names::add);
        }
        incoming.stream().map(this::identifier).forEach(names::add);
        return List.copyOf(names);
    }

    private List<ReplayDbCompareField> trustedFields(
            List<ReplayBaseColumnOption> columns,
            List<String> orderedNames) {
        Map<String, ReplayBaseColumnOption> byName = new LinkedHashMap<>();
        columns.forEach(column -> byName.put(identifier(column.columnName()), column));
        return java.util.stream.IntStream.range(0, orderedNames.size())
                .mapToObj(index -> {
                    ReplayBaseColumnOption column = byName.get(orderedNames.get(index));
                    if (column == null) {
                        throw new IllegalArgumentException("母库字段不存在：" + orderedNames.get(index));
                    }
                    return new ReplayDbCompareField(
                            column.columnName(), column.columnComment(),
                            column.ordinalPosition(), column.primaryKey(), index + 1);
                }).toList();
    }

    private void requireImporter(ReplayIssueOperator operator) {
        if (operator == null || operator.username() == null || operator.username().isBlank()) {
            throw new IllegalStateException("用户未登录");
        }
    }

    private ReplayDbCompareState state(ReplayDbCompareRegistration registration) {
        return new ReplayDbCompareState(
                registration.tableComment(), registration.domainName(),
                registration.groupOwnerEmpNo(), registration.groupOwnerName(),
                registration.registeredDate(), registration.deleted(), registration.fields(),
                registration.whereCondition(), registration.compareLimit());
    }

    private ReplayDbCompareImportError error(
            ReplayDatabaseComparisonExcelParser.ParsedRow row,
            String reason) {
        return new ReplayDbCompareImportError(
                row.sheetName(), row.rowNumber(), row.tableName(), row.fieldName(),
                row.reviserInput(), reason);
    }

    private ReplayDbCompareImportResult failed(
            int tableCount,
            Collection<ReplayDbCompareImportError> errors) {
        return new ReplayDbCompareImportResult(
                false, tableCount, 0, 0, 0, List.copyOf(errors));
    }

    private Comparator<ReplayDbCompareImportError> errorOrder() {
        return Comparator.comparingInt((ReplayDbCompareImportError error) -> {
                    int index = SHEET_ORDER.indexOf(error.sheetName());
                    return index < 0 ? SHEET_ORDER.size() : index;
                })
                .thenComparing(error -> error.rowNumber() == null ? 0 : error.rowNumber())
                .thenComparing(ReplayDbCompareImportError::reason);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "导入校验失败" : message;
    }

    private String identifier(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String empNo(SysUser user) {
        return user == null ? null : user.getEmpNo();
    }

    private String username(SysUser user) {
        return user == null ? null : user.getUsername();
    }

    private String name(SysUser user) {
        return user == null ? null : user.getRealName();
    }

    private record Actor(String empNo, String username, String name) {
    }

    private record PreparedImport(
            ReplayDatabaseComparisonExcelParser.ParsedTable parsed,
            ReplayBaseValidatedTable metadata,
            SysUser reviser,
            ReplayDbCompareRegistration existing,
            List<ReplayDbCompareField> fields) {
    }

    private static final class Counts {
        private int created;
        private int updated;
        private int unchanged;
    }
}
