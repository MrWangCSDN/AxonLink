package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseValidatedTable;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetail;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetailDraft;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditEvent;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditGroupPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditOperation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareDeleteRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListItem;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareMetadataStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareMetadataValidation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbComparePrimaryKeySyncResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareReregisterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSaveRequest;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReplayDatabaseComparisonService {

    private static final String MISSING_FIELDS_LABEL = "比对字段母库中不存在";
    private static final String TABLE_MISSING_LABEL = "母库表已删除";
    private static final int METADATA_BATCH_SIZE = 200;
    private static final Set<String> DOMAINS =
            Set.of("存款组", "贷款组", "公共组", "结算组", "平台组");

    private final ReplayBaseMetadataService metadataService;
    private final ReplayDatabaseComparisonDao dao;
    private final SysUserDao userDao;
    private final ReplayDatabaseComparisonAuditDiff auditDiff;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public ReplayDatabaseComparisonService(
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao dao,
            ReplayDatabaseComparisonVersionDao versionDao,
            SysUserDao userDao,
            ReplayDatabaseComparisonAuditDiff auditDiff,
            JdbcTemplate diiResultJdbcTemplate) {
        this(metadataService, dao, versionDao, userDao, auditDiff,
                diiResultJdbcTemplate, Clock.systemDefaultZone());
    }

    ReplayDatabaseComparisonService(
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao dao,
            ReplayDatabaseComparisonVersionDao versionDao,
            SysUserDao userDao,
            ReplayDatabaseComparisonAuditDiff auditDiff,
            JdbcTemplate diiResultJdbcTemplate,
            Clock clock) {
        this.metadataService = metadataService;
        this.dao = dao;
        this.userDao = userDao;
        this.auditDiff = auditDiff;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.clock = clock;
    }

    public ReplayDbCompareRegistration create(
            ReplayDbCompareSaveRequest request,
            ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        PreparedSave prepared = prepare(request.tableName(), request.domainName(),
                request.groupOwnerEmpNo(), request.fieldNames());
        LocalDateTime now = LocalDateTime.now(clock);
        ReplayDbCompareRegistration registration = new ReplayDbCompareRegistration(
                null, prepared.table().schemaName(), prepared.table().tableName(),
                prepared.table().tableComment(), prepared.domainName(), actor.empNo(), actor.username(), actor.name(),
                prepared.groupOwner().identifier(), prepared.groupOwner().name(), LocalDate.now(clock),
                false, null, null, null, 0, actor.empNo(), actor.name(), now,
                actor.empNo(), actor.name(), now, prepared.fields());
        ReplayDbCompareState after = state(registration);
        List<ReplayDbCompareAuditDetailDraft> details =
                auditDiff.compare(null, after, ReplayDbCompareAuditOperation.CREATE);

        ReplayDbCompareRegistration result = transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration existing = dao.findBySchemaAndTable(
                    registration.schemaName(), registration.tableName());
            if (existing != null) {
                throw new IllegalStateException(existing.deleted()
                        ? "该母库表已删除，请使用重新登记"
                        : "该母库表已登记");
            }
            long id = dao.insertRegistration(registration);
            writeAudit(id, registration.schemaName(), registration.tableName(),
                    ReplayDbCompareAuditOperation.CREATE, 0, null, actor, now, details);
            return dao.findByIdIncludingDeleted(id);
        });
        return requiredResult(result);
    }

    public ReplayDbCompareRegistration update(
            long id,
            ReplayDbCompareSaveRequest request,
            ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        if (request.fieldNames().isEmpty()) {
            if (!request.deleteWhenNoFields()) {
                throw new IllegalArgumentException("比对字段不能为空");
            }
            requirePrimaryKey(metadataService.requireTableWithColumns(request.tableName(), List.of()));
            LocalDateTime now = LocalDateTime.now(clock);
            ReplayDbCompareRegistration deleted = transactionTemplate.execute(status -> {
                ReplayDbCompareRegistration current = requireRegistration(id);
                requireActive(current);
                requireVersion(current, request.version());
                return deleteInside(current, "全部比对字段已移除", actor, now);
            });
            return requiredResult(deleted);
        }
        PreparedSave prepared = prepare(request.tableName(), request.domainName(),
                request.groupOwnerEmpNo(), request.fieldNames());
        LocalDateTime now = LocalDateTime.now(clock);

        ReplayDbCompareRegistration result = transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration current = requireRegistration(id);
            requireActive(current);
            requireVersion(current, request.version());
            requireSameTable(current, prepared.table());
            ReplayDbCompareRegistration target = updatedRegistration(
                    current, prepared, actor, LocalDate.now(clock), now, false);
            List<ReplayDbCompareAuditDetailDraft> details =
                    auditDiff.compare(state(current), state(target), ReplayDbCompareAuditOperation.UPDATE);
            if (details.isEmpty()) {
                return current;
            }
            if (!dao.updateRegistration(
                    id, current.version(), target.tableComment(), target.domainName(),
                    target.groupOwnerEmpNo(), target.groupOwnerName(), target.registeredDate(),
                    actor.empNo(), actor.username(), actor.name(), now)) {
                throw new ReplayDatabaseComparisonVersionConflictException();
            }
            dao.replaceFields(id, target.fields(), now);
            long version = current.version() + 1;
            writeAudit(id, current.schemaName(), current.tableName(),
                    ReplayDbCompareAuditOperation.UPDATE, version, null, actor, now, details);
            return dao.findByIdIncludingDeleted(id);
        });
        return requiredResult(result);
    }

    public ReplayDbCompareRegistration delete(
            long id,
            ReplayDbCompareDeleteRequest request,
            ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        LocalDateTime now = LocalDateTime.now(clock);
        ReplayDbCompareRegistration result = transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration current = requireRegistration(id);
            requireActive(current);
            requireVersion(current, request.version());
            return deleteInside(current, normalizeReason(request.reason(), "删除登记"), actor, now);
        });
        return requiredResult(result);
    }

    public ReplayDbCompareRegistration reregister(
            long id,
            ReplayDbCompareReregisterRequest request,
            ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        ReplayDbCompareRegistration snapshot = requireRegistration(id);
        if (!snapshot.deleted()) {
            throw new IllegalStateException("当前登记未删除，不能重新登记");
        }
        PreparedSave prepared = prepare(snapshot.tableName(), request.domainName(),
                request.groupOwnerEmpNo(), request.fieldNames());
        LocalDateTime now = LocalDateTime.now(clock);

        ReplayDbCompareRegistration result = transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration current = requireRegistration(id);
            if (!current.deleted()) {
                throw new ReplayDatabaseComparisonVersionConflictException();
            }
            requireVersion(current, request.version());
            requireSameTable(current, prepared.table());
            ReplayDbCompareRegistration target = updatedRegistration(
                    current, prepared, actor, LocalDate.now(clock), now, false);
            List<ReplayDbCompareAuditDetailDraft> details =
                    auditDiff.compare(null, state(target), ReplayDbCompareAuditOperation.REREGISTER);
            if (!dao.reregisterRegistration(
                    id, current.version(), target.tableComment(), target.domainName(),
                    target.groupOwnerEmpNo(), target.groupOwnerName(), target.registeredDate(),
                    actor.empNo(), actor.username(), actor.name(), now)) {
                throw new ReplayDatabaseComparisonVersionConflictException();
            }
            dao.replaceFields(id, target.fields(), now);
            long version = current.version() + 1;
            writeAudit(id, current.schemaName(), current.tableName(),
                    ReplayDbCompareAuditOperation.REREGISTER, version,
                    normalizeReason(request.reason(), "重新登记"), actor, now, details);
            return dao.findByIdIncludingDeleted(id);
        });
        return requiredResult(result);
    }

    public ReplayDbCompareListPage search(ReplayDbCompareQuery query) {
        ReplayDbCompareQuery effective = query == null ? ReplayDbCompareQuery.empty(0, 50) : query;
        if (!effective.metadataStatuses().isEmpty()) {
            return searchByMetadataStatus(effective);
        }
        ReplayDbCompareListPage page = dao.search(query);
        if (page.items().isEmpty()) {
            return page;
        }
        try {
            List<ReplayDbCompareListItem> items = enrichMetadata(page.items());
            return new ReplayDbCompareListPage(items, page.page(), page.size(), page.total());
        } catch (ReplayBaseDatabaseUnavailableException exception) {
            List<ReplayDbCompareListItem> items = page.items().stream()
                    .map(item -> withValidation(item, ReplayDbCompareMetadataValidation.unavailable()))
                    .toList();
            return new ReplayDbCompareListPage(items, page.page(), page.size(), page.total());
        }
    }

    private ReplayDbCompareListPage searchByMetadataStatus(ReplayDbCompareQuery query) {
        if (query.metadataStatuses().stream()
                .anyMatch(status -> status != ReplayDbCompareMetadataStatus.MISSING_FIELDS
                        && status != ReplayDbCompareMetadataStatus.TABLE_MISSING)) {
            throw new IllegalArgumentException("仅支持筛选字段缺失或母库表已删除状态");
        }
        Set<ReplayDbCompareMetadataStatus> requestedStatuses = Set.copyOf(query.metadataStatuses());
        List<ReplayDbCompareListItem> matched = enrichMetadata(dao.findMetadataCandidates(query)).stream()
                .filter(item -> requestedStatuses.stream()
                        .anyMatch(status -> matchesMetadataFilter(item.metadataValidation(), status)))
                .toList();
        int page = Math.max(0, query.page());
        int size = Math.min(Math.max(1, query.size()), 200);
        int fromIndex = Math.min(page * size, matched.size());
        int toIndex = Math.min(fromIndex + size, matched.size());
        return new ReplayDbCompareListPage(matched.subList(fromIndex, toIndex), page, size, matched.size());
    }

    private List<ReplayDbCompareListItem> enrichMetadata(List<ReplayDbCompareListItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, List<ReplayDbCompareField>> fieldsByRegistration =
                dao.findFieldsByRegistrationIds(items.stream().map(ReplayDbCompareListItem::id).toList());
        List<String> tableNames = items.stream()
                .map(ReplayDbCompareListItem::tableName)
                .map(this::identifier)
                .distinct()
                .toList();
        Map<String, ReplayBaseMetadataSnapshot> metadataByTable = new LinkedHashMap<>();
        for (int start = 0; start < tableNames.size(); start += METADATA_BATCH_SIZE) {
            int end = Math.min(start + METADATA_BATCH_SIZE, tableNames.size());
            metadataByTable.putAll(metadataService.inspectTables(tableNames.subList(start, end)));
        }
        return items.stream()
                .map(item -> withValidation(item, validationFor(
                        metadataByTable.get(identifier(item.tableName())),
                        fieldsByRegistration.getOrDefault(item.id(), List.of()))))
                .toList();
    }

    private ReplayDbCompareMetadataValidation validationFor(
            ReplayBaseMetadataSnapshot metadata,
            List<ReplayDbCompareField> registeredFields) {
        if (metadata == null || !metadata.tableExists()) {
            return ReplayDbCompareMetadataValidation.tableMissing();
        }
        Set<String> currentNames = metadata.columns().stream()
                .map(ReplayBaseColumnOption::columnName)
                .map(this::identifier)
                .collect(java.util.stream.Collectors.toSet());
        List<String> missingFields = registeredFields.stream()
                .map(ReplayDbCompareField::columnName)
                .filter(name -> !currentNames.contains(identifier(name)))
                .toList();
        Set<String> registeredNames = registeredFields.stream()
                .map(ReplayDbCompareField::columnName)
                .map(this::identifier)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> currentPrimaryKeys = metadata.columns().stream()
                .filter(ReplayBaseColumnOption::primaryKey)
                .sorted(java.util.Comparator.comparing(ReplayBaseColumnOption::primaryKeyOrder))
                .map(ReplayBaseColumnOption::columnName)
                .map(this::identifier)
                .toList();
        Set<String> currentPrimaryKeySet = Set.copyOf(currentPrimaryKeys);
        List<String> missingPrimaryKeys = currentPrimaryKeys.stream()
                .filter(name -> !registeredNames.contains(name))
                .toList();
        List<String> formerPrimaryKeys = registeredFields.stream()
                .filter(ReplayDbCompareField::primaryKey)
                .map(ReplayDbCompareField::columnName)
                .map(this::identifier)
                .filter(name -> !currentPrimaryKeySet.contains(name) && !currentNames.contains(name))
                .toList();
        ReplayDbCompareMetadataStatus status = missingFields.isEmpty()
                ? ReplayDbCompareMetadataStatus.VALID
                : ReplayDbCompareMetadataStatus.MISSING_FIELDS;
        return ReplayDbCompareMetadataValidation.of(
                status, missingFields, missingPrimaryKeys, formerPrimaryKeys);
    }

    public ReplayDbCompareRegistration detail(long id) {
        ReplayDbCompareRegistration snapshot = requireRegistration(id);
        try {
            ReplayBaseMetadataSnapshot metadata = metadataService.inspectTable(snapshot.tableName());
            if (!metadata.tableExists()) {
                return withValidation(snapshot,
                        snapshot.fields().stream()
                                .map(field -> withExistsInBase(field, false))
                                .toList(),
                        ReplayDbCompareMetadataValidation.tableMissing());
            }
            Map<String, ReplayBaseColumnOption> currentColumns = metadata.columns().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            column -> identifier(column.columnName()),
                            column -> column,
                            (left, right) -> left,
                            LinkedHashMap::new));
            List<ReplayDbCompareField> fields = snapshot.fields().stream()
                    .map(field -> withCurrentMetadata(
                            field, currentColumns.get(identifier(field.columnName()))))
                    .toList();
            ReplayDbCompareMetadataValidation validation = validationFor(metadata, snapshot.fields());
            return withValidation(snapshot, fields, validation);
        } catch (ReplayBaseDatabaseUnavailableException exception) {
            return withValidation(snapshot,
                    snapshot.fields().stream()
                            .map(field -> withExistsInBase(field, null))
                            .toList(),
                    ReplayDbCompareMetadataValidation.unavailable());
        }
    }

    public ReplayDbComparePrimaryKeySyncResult synchronizePrimaryKeys() {
        List<ReplayDbCompareListItem> registrations =
                dao.findMetadataCandidates(ReplayDbCompareQuery.empty(0, 200));
        if (registrations.isEmpty()) {
            return new ReplayDbComparePrimaryKeySyncResult(0, 0, 0, 0);
        }
        List<String> tableNames = registrations.stream()
                .map(ReplayDbCompareListItem::tableName)
                .map(this::identifier)
                .distinct()
                .toList();
        Map<String, ReplayBaseMetadataSnapshot> metadataByTable = new LinkedHashMap<>();
        for (int start = 0; start < tableNames.size(); start += METADATA_BATCH_SIZE) {
            int end = Math.min(start + METADATA_BATCH_SIZE, tableNames.size());
            metadataByTable.putAll(metadataService.inspectTables(tableNames.subList(start, end)));
        }
        int updatedCount = 0;
        int addedFieldCount = 0;
        int conflictCount = 0;
        for (ReplayDbCompareListItem registration : registrations) {
            ReplayBaseMetadataSnapshot metadata = metadataByTable.get(identifier(registration.tableName()));
            if (metadata == null || !metadata.tableExists()) {
                continue;
            }
            try {
                SyncOutcome outcome = transactionTemplate.execute(
                        status -> synchronizePrimaryKeys(registration.id(), metadata));
                if (outcome != null && outcome.addedFieldCount() > 0) {
                    updatedCount++;
                    addedFieldCount += outcome.addedFieldCount();
                }
            } catch (ReplayDatabaseComparisonVersionConflictException exception) {
                conflictCount++;
            }
        }
        return new ReplayDbComparePrimaryKeySyncResult(
                registrations.size(), updatedCount, addedFieldCount, conflictCount);
    }

    private SyncOutcome synchronizePrimaryKeys(long registrationId, ReplayBaseMetadataSnapshot metadata) {
        ReplayDbCompareRegistration current = requireRegistration(registrationId);
        requireActive(current);
        List<ReplayDbCompareField> synchronizedFields =
                addMissingPrimaryKeys(current.fields(), metadata.columns());
        int addedFieldCount = synchronizedFields.size() - current.fields().size();
        if (addedFieldCount == 0) {
            return new SyncOutcome(0);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ReplayDbCompareRegistration target = withSystemFields(current, synchronizedFields, now);
        List<ReplayDbCompareAuditDetailDraft> details =
                auditDiff.compare(state(current), state(target), ReplayDbCompareAuditOperation.UPDATE);
        if (!dao.touchSystemUpdate(current.id(), current.version(), now)) {
            throw new ReplayDatabaseComparisonVersionConflictException();
        }
        dao.replaceFields(current.id(), synchronizedFields, now);
        writeAudit(current.id(), current.schemaName(), current.tableName(),
                ReplayDbCompareAuditOperation.UPDATE, current.version() + 1,
                "自动同步母库主键", new Actor("SYSTEM", "SYSTEM", "系统"), now, details);
        return new SyncOutcome(addedFieldCount);
    }

    private List<ReplayDbCompareField> addMissingPrimaryKeys(
            List<ReplayDbCompareField> registeredFields,
            List<ReplayBaseColumnOption> currentColumns) {
        List<ReplayDbCompareField> result = new ArrayList<>(registeredFields.stream()
                .sorted(Comparator.comparingInt(ReplayDbCompareField::comparisonOrder))
                .toList());
        List<ReplayBaseColumnOption> currentPrimaryKeys = currentColumns.stream()
                .filter(ReplayBaseColumnOption::primaryKey)
                .sorted(Comparator.comparing(ReplayBaseColumnOption::primaryKeyOrder))
                .toList();
        Set<String> registeredNames = result.stream()
                .map(ReplayDbCompareField::columnName)
                .map(this::identifier)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (int keyIndex = 0; keyIndex < currentPrimaryKeys.size(); keyIndex++) {
            ReplayBaseColumnOption key = currentPrimaryKeys.get(keyIndex);
            String keyName = identifier(key.columnName());
            if (registeredNames.contains(keyName)) {
                continue;
            }
            int insertionIndex = 0;
            for (int previousIndex = keyIndex - 1; previousIndex >= 0; previousIndex--) {
                String previousName = identifier(currentPrimaryKeys.get(previousIndex).columnName());
                int existingIndex = indexOfField(result, previousName);
                if (existingIndex >= 0) {
                    insertionIndex = existingIndex + 1;
                    break;
                }
            }
            result.add(insertionIndex, new ReplayDbCompareField(
                    key.columnName(), key.columnComment(), key.ordinalPosition(), true, 0));
            registeredNames.add(keyName);
        }
        return java.util.stream.IntStream.range(0, result.size())
                .mapToObj(index -> {
                    ReplayDbCompareField field = result.get(index);
                    return new ReplayDbCompareField(
                            field.columnName(), field.columnComment(), field.ordinalPosition(),
                            field.primaryKey(), index + 1);
                })
                .toList();
    }

    private int indexOfField(List<ReplayDbCompareField> fields, String normalizedName) {
        for (int index = 0; index < fields.size(); index++) {
            if (identifier(fields.get(index).columnName()).equals(normalizedName)) {
                return index;
            }
        }
        return -1;
    }

    private ReplayDbCompareRegistration withSystemFields(
            ReplayDbCompareRegistration current,
            List<ReplayDbCompareField> fields,
            LocalDateTime updatedAt) {
        return new ReplayDbCompareRegistration(
                current.id(), current.schemaName(), current.tableName(), current.tableComment(),
                current.domainName(), current.reviserEmpNo(), current.reviserUsername(), current.reviserName(),
                current.groupOwnerEmpNo(), current.groupOwnerName(), current.registeredDate(),
                current.deleted(), current.deletedReason(), current.deletedBy(), current.deletedAt(),
                current.version() + 1, current.createdBy(), current.createdName(), current.createdAt(),
                "SYSTEM", "系统", updatedAt, fields);
    }

    public ReplayDbCompareAuditPage searchAudits(ReplayDbCompareAuditQuery query) {
        return dao.searchAuditEvents(query);
    }

    public ReplayDbCompareAuditGroupPage searchGroupedAudits(ReplayDbCompareAuditQuery query) {
        ReplayDbCompareAuditQuery effective = normalizeGroupedAuditQuery(query);
        long total = dao.countAuditGroups(effective);
        if (total == 0) {
            return new ReplayDbCompareAuditGroupPage(List.of(), effective.page(), effective.size(), 0, 0);
        }
        List<ReplayDbCompareAuditGroup> summaries = dao.findAuditGroupPage(effective);
        List<ReplayDbCompareAuditEvent> events = dao.findAuditEventsForGroups(effective, summaries);
        Map<String, List<ReplayDbCompareAuditEvent>> eventsByGroup = new LinkedHashMap<>();
        for (ReplayDbCompareAuditEvent event : events) {
            eventsByGroup.computeIfAbsent(auditGroupKey(event.schemaName(), event.tableName()), ignored -> new ArrayList<>())
                    .add(event);
        }
        List<ReplayDbCompareAuditGroup> groups = summaries.stream().map(summary -> {
            List<ReplayDbCompareAuditEvent> groupEvents = eventsByGroup.getOrDefault(
                    auditGroupKey(summary.schemaName(), summary.tableName()), List.of());
            ReplayDbCompareAuditEvent latest = groupEvents.isEmpty() ? null : groupEvents.get(0);
            return new ReplayDbCompareAuditGroup(
                    summary.schemaName(), summary.tableName(), summary.tableComment(), summary.matchedEventCount(),
                    latest == null ? null : latest.operatorEmpNo(),
                    latest == null ? null : latest.operatorUsername(),
                    latest == null ? null : latest.operatorName(),
                    latest == null ? summary.latestOperatedAt() : latest.operatedAt(), groupEvents);
        }).toList();
        int totalPages = (int) ((total + effective.size() - 1) / effective.size());
        return new ReplayDbCompareAuditGroupPage(
                groups, effective.page(), effective.size(), total, totalPages);
    }

    private ReplayDbCompareAuditQuery normalizeGroupedAuditQuery(ReplayDbCompareAuditQuery query) {
        ReplayDbCompareAuditQuery source = query == null ? ReplayDbCompareAuditQuery.empty(0, 20) : query;
        int size = source.size() == 0 ? 20 : source.size();
        if (source.page() < 0 || !Set.of(10, 20, 50).contains(size)) {
            throw new IllegalArgumentException("审计日志分页参数无效");
        }
        if (source.operatedFrom() != null && source.operatedTo() != null
                && source.operatedFrom().isAfter(source.operatedTo())) {
            throw new IllegalArgumentException("审计日志开始时间不能晚于结束时间");
        }
        return new ReplayDbCompareAuditQuery(
                source.registrationId(), source.tableKeyword(), source.operatorKeyword(), source.operatorEmpNos(),
                source.operations(), source.operatedFrom(), source.operatedTo(), source.page(), size);
    }

    private String auditGroupKey(String schemaName, String tableName) {
        return schemaName + '\u0000' + tableName;
    }

    public ReplayDbCompareAuditPage auditEvents(long registrationId, int page, int size) {
        return dao.searchAuditEvents(new ReplayDbCompareAuditQuery(
                registrationId, null, null, List.of(), List.of(), null, null, page, size));
    }

    public ReplayDbCompareHeaderFilterResult headerFilterOptions(ReplayDbCompareHeaderFilterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("筛选请求不能为空");
        }
        ReplayDbCompareHeaderFilterResult ordinary = dao.headerFilterOptions(request);
        if (!"tableName".equals(request.targetColumn())) {
            return ordinary;
        }
        List<StatusFilterLabel> matchedLabels = statusFilterLabels().stream()
                .filter(option -> matchesLabel(option.label(), request.keyword()))
                .toList();
        if (matchedLabels.isEmpty()) {
            return ordinary;
        }
        List<ReplayDbCompareListItem> enriched = enrichMetadata(
                dao.findMetadataCandidates(headerMetadataQuery(request)));
        List<ReplayDbCompareHeaderFilterOption> combined = new java.util.ArrayList<>(ordinary.options());
        for (StatusFilterLabel option : matchedLabels) {
            long count = enriched.stream()
                    .filter(item -> matchesMetadataFilter(item.metadataValidation(), option.status()))
                    .count();
            if (count > 0) {
                combined.add(new ReplayDbCompareHeaderFilterOption(
                        option.label(), option.label(), count, option.status()));
            }
        }
        List<ReplayDbCompareHeaderFilterOption> options = combined.stream()
                .limit(request.effectiveLimit())
                .toList();
        return new ReplayDbCompareHeaderFilterResult(
                options, combined.size(), ordinary.matchedRegistrationCount(), combined.size() > options.size());
    }

    private List<StatusFilterLabel> statusFilterLabels() {
        return List.of(
                new StatusFilterLabel(MISSING_FIELDS_LABEL, ReplayDbCompareMetadataStatus.MISSING_FIELDS),
                new StatusFilterLabel(TABLE_MISSING_LABEL, ReplayDbCompareMetadataStatus.TABLE_MISSING));
    }

    private boolean matchesMetadataFilter(
            ReplayDbCompareMetadataValidation validation,
            ReplayDbCompareMetadataStatus requested) {
        return validation.status() == requested;
    }

    private boolean matchesLabel(String candidate, String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String label = candidate.toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || label.contains(normalized) || normalized.contains(label);
    }

    private ReplayDbCompareQuery headerMetadataQuery(ReplayDbCompareHeaderFilterRequest request) {
        ReplayDbCompareQuery query = request.query();
        return new ReplayDbCompareQuery(
                null, query.fieldKeyword(), query.domains(), query.reviserEmpNos(),
                query.groupOwnerEmpNos(), query.registeredDateFrom(), query.registeredDateTo(),
                0, 1, List.of());
    }

    public RegistrationState registrationState(String tableName) {
        ReplayDbCompareRegistration registration = dao.findBySchemaAndTable(
                metadataService.schemaName(), tableName);
        if (registration == null) {
            return new RegistrationState("UNREGISTERED", null, null);
        }
        return new RegistrationState(registration.deleted() ? "DELETED" : "ACTIVE",
                registration.id(), registration.version());
    }

    public List<ReplayDbCompareAuditDetail> auditDetails(long auditEventId) {
        return dao.findAuditDetails(auditEventId);
    }

    private ReplayDbCompareRegistration deleteInside(
            ReplayDbCompareRegistration current,
            String reason,
            Actor actor,
            LocalDateTime now) {
        List<ReplayDbCompareAuditDetailDraft> details =
                auditDiff.compare(state(current), null, ReplayDbCompareAuditOperation.DELETE);
        if (!dao.markDeleted(current.id(), current.version(), reason,
                actor.empNo(), actor.username(), actor.name(), now)) {
            throw new ReplayDatabaseComparisonVersionConflictException();
        }
        long version = current.version() + 1;
        writeAudit(current.id(), current.schemaName(), current.tableName(),
                ReplayDbCompareAuditOperation.DELETE, version, reason, actor, now, details);
        dao.clearFields(current.id());
        return dao.findByIdIncludingDeleted(current.id());
    }

    private PreparedSave prepare(
            String tableName,
            String domainName,
            String groupOwnerEmpNo,
            List<String> fieldNames) {
        String domain = requireDomain(domainName);
        String groupOwnerIdentity = requireText(groupOwnerEmpNo, "小组负责人不能为空");
        SysUser groupOwner = findActiveUser(groupOwnerIdentity);
        if (groupOwner == null) {
            throw new IllegalArgumentException("小组负责人不存在或已停用");
        }
        List<String> normalizedFields = normalizeFields(fieldNames);
        ReplayBaseValidatedTable table = requirePrimaryKey(
                metadataService.requireTableWithColumns(tableName, normalizedFields));
        Set<String> selectedFieldNames = Set.copyOf(normalizedFields);
        List<String> missingPrimaryKeys = table.currentPrimaryKeys().stream()
                .map(ReplayBaseColumnOption::columnName)
                .map(this::identifier)
                .filter(name -> !selectedFieldNames.contains(name))
                .toList();
        if (!missingPrimaryKeys.isEmpty()) {
            throw new ReplayBasePrimaryKeysRequiredException(table.tableName(), missingPrimaryKeys);
        }
        Map<String, ReplayBaseColumnOption> metadataByName = new LinkedHashMap<>();
        table.columns().forEach(column -> metadataByName.put(identifier(column.columnName()), column));
        List<ReplayDbCompareField> fields = java.util.stream.IntStream.range(0, normalizedFields.size())
                .mapToObj(index -> {
                    ReplayBaseColumnOption column = metadataByName.get(normalizedFields.get(index));
                    if (column == null) {
                        throw new IllegalArgumentException("母库字段不存在：" + normalizedFields.get(index));
                    }
                    return new ReplayDbCompareField(
                            column.columnName(), column.columnComment(), column.ordinalPosition(),
                            column.primaryKey(), index + 1);
                })
                .toList();
        return new PreparedSave(table, domain,
                new Owner(userIdentifier(groupOwner),
                        requireText(groupOwner.getRealName(), "小组负责人姓名为空")), fields);
    }

    private ReplayBaseValidatedTable requirePrimaryKey(ReplayBaseValidatedTable table) {
        if (table.currentPrimaryKeys().isEmpty()) {
            throw new ReplayBasePrimaryKeyMissingException(table.tableName());
        }
        return table;
    }

    private List<String> normalizeFields(List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return List.of();
        }
        List<String> normalized = fieldNames.stream()
                .map(name -> identifier(requireText(name, "字段英文名不能为空")))
                .toList();
        if (new LinkedHashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException("比对字段不能重复");
        }
        return normalized;
    }

    private Actor requireActor(ReplayIssueOperator operator) {
        if (operator == null || operator.username() == null || operator.username().isBlank()) {
            throw new IllegalStateException("用户未登录");
        }
        String identity = operator.username().trim();
        SysUser user = findActiveUser(identity);
        if (user == null) {
            throw new IllegalStateException("当前登录用户不存在或已停用");
        }
        String username = user.getUsername() == null || user.getUsername().isBlank()
                ? identity : user.getUsername().trim();
        String name = user.getRealName() == null || user.getRealName().isBlank()
                ? operator.realName() : user.getRealName();
        return new Actor(userIdentifier(user), username,
                requireText(name, "当前用户姓名为空"));
    }

    private SysUser findActiveUser(String identity) {
        SysUser user = userDao.findActiveByUsername(identity);
        return user == null ? userDao.findActiveByEmpNo(identity) : user;
    }

    private String userIdentifier(SysUser user) {
        if (user.getEmpNo() != null && !user.getEmpNo().isBlank()) {
            return user.getEmpNo().trim();
        }
        return requireText(user.getUsername(), "用户账号为空");
    }

    private ReplayDbCompareRegistration requireRegistration(long id) {
        ReplayDbCompareRegistration registration = dao.findByIdIncludingDeleted(id);
        if (registration == null) {
            throw new IllegalArgumentException("登记不存在");
        }
        return registration;
    }

    private void requireActive(ReplayDbCompareRegistration registration) {
        if (registration.deleted()) {
            throw new IllegalStateException("登记已删除");
        }
    }

    private void requireVersion(ReplayDbCompareRegistration registration, Long version) {
        if (version == null || registration.version() != version) {
            throw new ReplayDatabaseComparisonVersionConflictException();
        }
    }

    private void requireSameTable(ReplayDbCompareRegistration registration, ReplayBaseValidatedTable table) {
        if (!identifier(registration.schemaName()).equals(identifier(table.schemaName()))
                || !identifier(registration.tableName()).equals(identifier(table.tableName()))) {
            throw new IllegalArgumentException("登记表与母库表不一致");
        }
    }

    private ReplayDbCompareRegistration updatedRegistration(
            ReplayDbCompareRegistration current,
            PreparedSave prepared,
            Actor actor,
            LocalDate registeredDate,
            LocalDateTime updatedAt,
            boolean deleted) {
        return new ReplayDbCompareRegistration(
                current.id(), current.schemaName(), current.tableName(), prepared.table().tableComment(),
                prepared.domainName(), actor.empNo(), actor.username(), actor.name(),
                prepared.groupOwner().identifier(), prepared.groupOwner().name(), registeredDate,
                deleted, null, null, null, current.version() + 1,
                current.createdBy(), current.createdName(), current.createdAt(),
                actor.empNo(), actor.name(), updatedAt, prepared.fields());
    }

    private ReplayDbCompareListItem withValidation(
            ReplayDbCompareListItem item,
            ReplayDbCompareMetadataValidation validation) {
        return new ReplayDbCompareListItem(
                item.id(), item.schemaName(), item.tableName(), item.tableComment(), item.domainName(),
                item.reviserEmpNo(), item.reviserUsername(), item.reviserName(),
                item.groupOwnerEmpNo(), item.groupOwnerName(), item.registeredDate(), item.version(),
                item.fieldCount(), item.fieldPreview(), validation);
    }

    private ReplayDbCompareRegistration withValidation(
            ReplayDbCompareRegistration registration,
            List<ReplayDbCompareField> fields,
            ReplayDbCompareMetadataValidation validation) {
        return new ReplayDbCompareRegistration(
                registration.id(), registration.schemaName(), registration.tableName(), registration.tableComment(),
                registration.domainName(), registration.reviserEmpNo(), registration.reviserUsername(),
                registration.reviserName(), registration.groupOwnerEmpNo(), registration.groupOwnerName(),
                registration.registeredDate(), registration.deleted(), registration.deletedReason(),
                registration.deletedBy(), registration.deletedAt(), registration.version(),
                registration.createdBy(), registration.createdName(), registration.createdAt(),
                registration.updatedBy(), registration.updatedName(), registration.updatedAt(), fields, validation);
    }

    private ReplayDbCompareField withExistsInBase(ReplayDbCompareField field, Boolean existsInBase) {
        return new ReplayDbCompareField(
                field.columnName(), field.columnComment(), field.ordinalPosition(), field.primaryKey(),
                field.comparisonOrder(), existsInBase);
    }

    private ReplayDbCompareField withCurrentMetadata(
            ReplayDbCompareField field,
            ReplayBaseColumnOption currentColumn) {
        if (currentColumn == null) {
            return withExistsInBase(field, false);
        }
        return new ReplayDbCompareField(
                field.columnName(), field.columnComment(), field.ordinalPosition(), currentColumn.primaryKey(),
                field.comparisonOrder(), true);
    }

    private ReplayDbCompareState state(ReplayDbCompareRegistration registration) {
        return new ReplayDbCompareState(
                registration.tableComment(), registration.domainName(),
                registration.groupOwnerEmpNo(), registration.groupOwnerName(),
                registration.registeredDate(), registration.deleted(), registration.fields());
    }

    private void writeAudit(
            long registrationId,
            String schemaName,
            String tableName,
            ReplayDbCompareAuditOperation operation,
            long version,
            String reason,
            Actor actor,
            LocalDateTime now,
            List<ReplayDbCompareAuditDetailDraft> drafts) {
        long eventId = dao.insertAuditEvent(new ReplayDbCompareAuditEvent(
                null, registrationId, schemaName, tableName, operation, version, drafts.size(),
                reason, actor.empNo(), actor.username(), actor.name(), now));
        List<ReplayDbCompareAuditDetail> details = java.util.stream.IntStream.range(0, drafts.size())
                .mapToObj(index -> {
                    ReplayDbCompareAuditDetailDraft draft = drafts.get(index);
                    return new ReplayDbCompareAuditDetail(
                            null, eventId, index + 1, draft.changeType(), draft.fieldCode(),
                            draft.fieldLabel(), draft.beforeValue(), draft.afterValue(), now);
                })
                .toList();
        dao.insertAuditDetails(eventId, details, now);
    }

    private String requireDomain(String value) {
        String domain = requireText(value, "领域不能为空");
        if (!DOMAINS.contains(domain)) {
            throw new IllegalArgumentException("领域不合法");
        }
        return domain;
    }

    private String normalizeReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String identifier(String value) {
        return requireText(value, "标识符不能为空").toLowerCase(Locale.ROOT);
    }

    private ReplayDbCompareRegistration requiredResult(ReplayDbCompareRegistration result) {
        if (result == null) {
            throw new IllegalStateException("数据库比对字段登记事务未完成");
        }
        return result;
    }

    private record Actor(String empNo, String username, String name) {
    }

    private record Owner(String identifier, String name) {
    }

    private record PreparedSave(
            ReplayBaseValidatedTable table,
            String domainName,
            Owner groupOwner,
            List<ReplayDbCompareField> fields) {
    }

    public record RegistrationState(String status, Long registrationId, Long registrationVersion) {
    }

    private record StatusFilterLabel(
            String label,
            ReplayDbCompareMetadataStatus status) {
    }

    private record SyncOutcome(int addedFieldCount) {
    }
}
