package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareChangeType;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSql;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCustomSqlSaveRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlAnalysis;
import org.springframework.http.HttpStatus;

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
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCompiledScope;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareDeleteRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareGlobalCounts;
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
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSampleLimit;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSaveRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScopeFilterValue;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareState;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareTechnologyOwnerOption;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayDatabaseTablePersonRow;
import com.axonlink.ai.replay.persistence.ReplayDatabaseTablePersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
    private static final String MISSING_CONDITION_FIELDS_LABEL = "条件字段母库中不存在";
    private static final String TABLE_MISSING_LABEL = "母库表已删除";
    private static final String ORDERING_PRIMARY_KEY_CHANGED_LABEL = "排序主键已变更";
    private static final String FULL_TABLE_FILTER_VALUE = "__FULL_TABLE__";
    private static final int METADATA_BATCH_SIZE = 200;
    private static final Set<String> DOMAINS =
            Set.of("存款组", "贷款组", "公共组", "结算组", "平台组");

    private final ReplayBaseMetadataService metadataService;
    private final ReplayDatabaseComparisonDao dao;
    private final SysUserDao userDao;
    private ReplayDatabaseTablePersonDao tablePersonDao;
    private ReplayDatabaseComparisonCustomSqlAnalyzer customSqlAnalyzer;

    @Autowired
    public void setCustomSqlAnalyzer(ReplayDatabaseComparisonCustomSqlAnalyzer analyzer) {
        this.customSqlAnalyzer = analyzer;
    }

    @Autowired(required = false)
    public void setTablePersonDao(ReplayDatabaseTablePersonDao tablePersonDao) {
        this.tablePersonDao = tablePersonDao;
    }
    private final ReplayDatabaseComparisonAuditDiff auditDiff;
    private final ReplayDatabaseComparisonScopeCompiler scopeCompiler;
    private final ReplayDatabaseComparisonConditionCodec conditionCodec =
            new ReplayDatabaseComparisonConditionCodec();
    private final ReplayDatabaseComparisonConditionLabeler conditionLabeler =
            new ReplayDatabaseComparisonConditionLabeler();
    private final ReplayDatabaseComparisonLegacyAuditDetailAdapter legacyAuditDetailAdapter =
            new ReplayDatabaseComparisonLegacyAuditDetailAdapter();
    private final TransactionTemplate transactionTemplate;
    private final ReplayDatabaseComparisonCodeAllocator codeAllocator;
    private final Clock clock;
    private ReplayDatabaseComparisonProperties partitionProperties =
            new ReplayDatabaseComparisonProperties();

    @Autowired
    public void setPartitionProperties(
            ReplayDatabaseComparisonProperties properties) {
        this.partitionProperties = properties;
    }

    public ReplayDbCompareRegistration updatePartitioning(
            long id, Long version, Integer partitionNum, ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        SysUser authenticatedUser = findActiveUser(operator.username());
        if (authenticatedUser == null || !partitionProperties.canConfigurePartitions(authenticatedUser.getUsername(), authenticatedUser.getEmpNo())) {
            throw new ReplayDatabaseComparisonPartitionForbiddenException();
        }
        if (partitionNum == null || partitionNum < 1 || partitionNum > 256) {
            throw new IllegalArgumentException("读取分区数必须为 1 到 256 的整数");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return requiredResult(transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration current = dao.findByIdIncludingDeleted(id);
            if (current == null) {
                throw new ReplayDatabaseComparisonGenerationException(
                        HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND", "登记不存在", null);
            }
            requireActive(current);
            requireVersion(current, version);
            if (current.partitionNum() == partitionNum) {
                return current;
            }
            if (!dao.updatePartitioning(id, version, partitionNum, actor.empNo(), actor.name(), now)) {
                throw new ReplayDatabaseComparisonVersionConflictException();
            }
            writeAudit(id, current.schemaName(), current.tableName(), ReplayDbCompareAuditOperation.UPDATE,
                    current.version() + 1, null, actor, now, List.of(new ReplayDbCompareAuditDetailDraft(
                            ReplayDbCompareChangeType.MODIFY,
                            "partitionNum", "读取分区数", String.valueOf(current.partitionNum()),
                            String.valueOf(partitionNum))));
            return dao.findByIdIncludingDeleted(id);
        }));
    }

    private int partitionForSave(Integer requested, int current, ReplayIssueOperator operator) {
        if (requested == null) return current;
        if (requested < 1 || requested > 256) {
            throw new IllegalArgumentException("分区数必须为 1 到 256 的整数");
        }
        if (requested != current) {
            SysUser user = findActiveUser(operator.username());
            if (user == null || com.axonlink.security.DiiTokenBypassFilter.DII_PRINCIPAL.equals(operator.username())
                    || !partitionProperties.canConfigurePartitions(user.getUsername(), user.getEmpNo())) {
                throw new ReplayDatabaseComparisonPartitionForbiddenException();
            }
        }
        return requested;
    }

    private int sampleForSave(Integer requested, int current, ReplayIssueOperator operator) {
        if (requested == null) return current;
        if (!ReplayDbCompareSampleLimit.isValid(requested)) {
            throw new IllegalArgumentException(ReplayDbCompareSampleLimit.RANGE_ERROR);
        }
        if (requested != current) {
            SysUser user = findActiveUser(operator.username());
            if (user == null || com.axonlink.security.DiiTokenBypassFilter.DII_PRINCIPAL.equals(operator.username())
                    || !partitionProperties.canConfigurePartitions(user.getUsername(), user.getEmpNo()))
                throw new ReplayDatabaseComparisonPartitionForbiddenException();
        }
        return requested;
    }

    private int maximumForSave(Integer requested, int current) {
        if (requested == null) return current;
        if (requested < 1 || requested > 256) {
            throw new IllegalArgumentException("最大HASH分区数必须为 1 到 256 的整数");
        }
        return requested;
    }

    private List<ReplayDbCompareAuditDetailDraft> withMaximumAudit(
            List<ReplayDbCompareAuditDetailDraft> details, int before, int after) {
        if (before == after) return details;
        var result = new ArrayList<>(details);
        result.add(new ReplayDbCompareAuditDetailDraft(ReplayDbCompareChangeType.MODIFY,
                "maxPartitionNum", "最大HASH分区数", String.valueOf(before), String.valueOf(after)));
        return List.copyOf(result);
    }

    private List<ReplayDbCompareAuditDetailDraft> withSampleAudit(
            List<ReplayDbCompareAuditDetailDraft> details, int before, int after) {
        if (before == after) return details;
        var result = new java.util.ArrayList<>(details);
        result.add(new ReplayDbCompareAuditDetailDraft(ReplayDbCompareChangeType.MODIFY,
                "sampleLimit", "样本数", String.valueOf(before), String.valueOf(after)));
        return List.copyOf(result);
    }

    private List<ReplayDbCompareAuditDetailDraft> withPartitionAudit(
            List<ReplayDbCompareAuditDetailDraft> details, int before, int after) {
        if (before == after) return details;
        var result = new java.util.ArrayList<>(details);
        result.add(new ReplayDbCompareAuditDetailDraft(ReplayDbCompareChangeType.MODIFY,
                "partitionNum", "分区数", String.valueOf(before), String.valueOf(after)));
        return List.copyOf(result);
    }

    @Autowired
    public ReplayDatabaseComparisonService(
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao dao,
            ReplayDatabaseComparisonVersionDao versionDao,
            SysUserDao userDao,
            ReplayDatabaseComparisonAuditDiff auditDiff,
            ReplayDatabaseComparisonScopeCompiler scopeCompiler,
            JdbcTemplate diiResultJdbcTemplate) {
        this(metadataService, dao, versionDao, userDao, auditDiff, scopeCompiler,
                diiResultJdbcTemplate, Clock.systemDefaultZone());
    }

    ReplayDatabaseComparisonService(
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao dao,
            ReplayDatabaseComparisonVersionDao versionDao,
            SysUserDao userDao,
            ReplayDatabaseComparisonAuditDiff auditDiff,
            ReplayDatabaseComparisonScopeCompiler scopeCompiler,
            JdbcTemplate diiResultJdbcTemplate,
            Clock clock) {
        this.metadataService = metadataService;
        this.codeAllocator = new ReplayDatabaseComparisonCodeAllocator(diiResultJdbcTemplate, metadataService);
        this.dao = dao;
        this.userDao = userDao;
        this.auditDiff = auditDiff;
        this.scopeCompiler = scopeCompiler;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.clock = clock;
    }

    ReplayDatabaseComparisonService(
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao dao,
            ReplayDatabaseComparisonVersionDao versionDao,
            SysUserDao userDao,
            ReplayDatabaseComparisonAuditDiff auditDiff,
            JdbcTemplate diiResultJdbcTemplate,
            Clock clock) {
        this(metadataService, dao, versionDao, userDao, auditDiff,
                new ReplayDatabaseComparisonScopeCompiler(
                        new ReplayDatabaseComparisonConditionCodec()),
                diiResultJdbcTemplate, clock);
    }

    public ReplayDbCompareRegistration create(
            ReplayDbCompareSaveRequest request,
            ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        int partitionNum = partitionForSave(request.partitionNum(), 1, operator);
        int sampleLimit = sampleForSave(request.sampleLimit(), 100, operator);
        int maxPartitionNum = maximumForSave(request.maxPartitionNum(), 64);
        PreparedSave prepared = prepare(request.tableName(), request.domainName(),
                request.groupOwnerEmpNo(), request.fieldNames(),
                request.whereCondition(), request.compareLimit(), null, request.comparisonKeyName());
        LocalDateTime now = LocalDateTime.now(clock);
        ReplayDbCompareRegistration registration = new ReplayDbCompareRegistration(
                null, prepared.table().schemaName(), prepared.table().tableName(),
                prepared.table().tableComment(), prepared.domainName(),
                prepared.person() == null ? actor.empNo() : developerEmpNo(prepared),
                prepared.person() == null ? actor.username() : developerUsername(prepared),
                prepared.person() == null ? actor.name() : developerName(prepared),
                prepared.groupOwner().identifier(), prepared.groupOwner().name(), LocalDate.now(clock),
                false, null, null, null, 0, actor.empNo(), actor.name(), now,
                actor.empNo(), actor.name(), now, prepared.fields(),
                prepared.scope().conditionTree(), prepared.scope().compareLimit(),
                orderingPrimaryKeySnapshot(prepared), null).withPartitionNum(partitionNum).withSampleLimit(sampleLimit).withMaxPartitionNum(maxPartitionNum).withComparisonKey(prepared.comparisonKey()).withConfigurationIdentity(configuredIdentity(prepared.table().tableName(), request.configurationName(), null));
        ReplayDbCompareState after = state(registration);
        List<ReplayDbCompareAuditDetailDraft> details =
                withMaximumAudit(withSampleAudit(withPartitionAudit(auditDiff.compare(null, after, ReplayDbCompareAuditOperation.CREATE),
                        1, partitionNum), 100, sampleLimit), 64, maxPartitionNum);

        ReplayDbCompareRegistration result = transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration existing = dao.findBySchemaAndTable(
                    registration.schemaName(), registration.sourceTableName());
            if (!Boolean.TRUE.equals(request.createAdditionalConfiguration()) && existing != null && identifier(existing.sourceTableName()).equals(identifier(registration.sourceTableName()))) {
                throw new IllegalStateException(existing.deleted()
                        ? "该母库表已删除，请使用重新登记"
                        : "该母库表已登记");
            }
            if (!Boolean.TRUE.equals(request.createAdditionalConfiguration()) && codeAllocator.hasSource(registration.schemaName(), registration.sourceTableName())) throw new IllegalStateException("该母库表已登记");
            var target = registration.withComparisonCode(codeAllocator.allocate(registration.schemaName(), registration.sourceTableName(), Boolean.TRUE.equals(request.createAdditionalConfiguration())));
            long id = dao.insertRegistration(target);
            writeAudit(id, target.schemaName(), target.tableName(),
                    ReplayDbCompareAuditOperation.CREATE, 0, null, actor, now, details);
            return dao.findByIdIncludingDeleted(id);
        });
        return requiredResult(result);
    }

    public Map<String, Object> analyzeCustomSql(String sql, ReplayIssueOperator operator) {
        requireActor(operator);
        ReplayDbCompareSqlAnalysis analysis;
        try {
            analysis = customSqlAnalyzer.analyze(sql);
        } catch (IllegalArgumentException exception) {
            throw invalidCustomSql(exception.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", analysis.sql());
        result.put("schemaName", analysis.schemaName());
        result.put("sourceTableName", analysis.sourceTableName());
        result.put("sourceTableComment", analysis.sourceTableComment());
        result.put("resultColumns", analysis.resultColumns());
        result.put("candidateKeyColumns", analysis.candidateKeyColumns());
        var person = tablePersonDao == null ? null : tablePersonDao.findByTableName(analysis.sourceTableName());
        result.put("domainName", person == null ? "" : person.domain());
        result.put("reviserName", person == null ? "" : person.developer());
        result.put("groupOwnerName", person == null ? "" : person.technologyOwner());
        return result;
    }

    public ReplayDbCompareRegistration createCustomSql(
            ReplayDbCompareCustomSqlSaveRequest request, ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        int partitions = partitionForSave(request.partitionNum(), 1, operator);
        int samples = sampleForSave(request.sampleLimit(), 100, operator);
        var prepared = prepareCustomSql(request);
        var person = customSqlPerson(prepared.analysis().sourceTableName());
        LocalDateTime now = LocalDateTime.now(clock);
        var registration = customSqlRegistration(null, request, prepared, person, actor, now, partitions, samples);
        var details = withMaximumAudit(withSampleAudit(withPartitionAudit(
                auditDiff.compare(null, state(registration), ReplayDbCompareAuditOperation.CREATE),
                1, partitions), 100, samples), 64, registration.maxPartitionNum());
        try {
            return requiredResult(transactionTemplate.execute(status -> {
                if (!Boolean.TRUE.equals(request.createAdditionalConfiguration())) {
                    requireAvailableCustomSqlTable(registration, null);
                    if (codeAllocator.hasSource(registration.schemaName(), registration.sourceTableName())) {
                        requireAvailableCustomSqlTable(registration, null);
                        throw new ReplayDatabaseComparisonGenerationException(HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION",
                                "该母库表已有配置，请明确新增同表配置", Map.of("schema", registration.schemaName(), "tableName", registration.sourceTableName()));
                    }
                }
                var target = registration.withComparisonCode(codeAllocator.allocate(registration.schemaName(), registration.sourceTableName(), Boolean.TRUE.equals(request.createAdditionalConfiguration())));
                long id = dao.insertRegistration(target);
                writeAudit(id, target.schemaName(), target.tableName(), ReplayDbCompareAuditOperation.CREATE,
                        0, null, actor, now, details);
                return dao.findByIdIncludingDeleted(id);
            }));
        } catch (DuplicateKeyException exception) {
            if (!Boolean.TRUE.equals(request.createAdditionalConfiguration())) requireAvailableCustomSqlTable(registration, null);
            throw exception;
        }
    }

    public ReplayDbCompareRegistration updateCustomSql(
            long id, ReplayDbCompareCustomSqlSaveRequest request, ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        var snapshot = requireRegistration(id);
        requireVersion(snapshot, request.version());
        if (!identifier(snapshot.sourceTableName()).equals(identifier(request.sourceTableName()))) {
            throw new IllegalArgumentException("自定义 SQL 的关联基础表创建后不能更换");
        }
        int partitions = partitionForSave(request.partitionNum(), snapshot.partitionNum(), operator);
        int samples = sampleForSave(request.sampleLimit(), snapshot.sampleLimit(), operator);
        var prepared = prepareCustomSql(request);
        if (!identifier(snapshot.schemaName()).equals(identifier(prepared.analysis().schemaName()))) {
            throw new IllegalArgumentException("自定义 SQL 的母库 Schema 已变化，请重新登记");
        }
        var person = customSqlPerson(snapshot.sourceTableName());
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            return requiredResult(transactionTemplate.execute(status -> {
                var current = requireRegistration(id);
                requireVersion(current, request.version());
                if (current.deleted() != snapshot.deleted()) {
                    throw new ReplayDatabaseComparisonVersionConflictException();
                }
                var target = customSqlRegistration(current, request, prepared, person, actor, now, partitions, samples);
                requireAvailableCustomSqlTable(target, id);
                var operation = current.deleted() ? ReplayDbCompareAuditOperation.REREGISTER : ReplayDbCompareAuditOperation.UPDATE;
                var details = withSampleAudit(withPartitionAudit(
                        auditDiff.compare(current.deleted() ? null : state(current), state(target), operation),
                        current.partitionNum(), partitions), current.sampleLimit(), samples);
                details = withMaximumAudit(details, current.maxPartitionNum(), target.maxPartitionNum());
                details = withConfigurationNameAudit(details, current.configurationName(), target.configurationName());
                if (!current.tableName().equals(target.tableName())) {
                    var identityDetails = new ArrayList<>(details);
                    identityDetails.add(new ReplayDbCompareAuditDetailDraft(ReplayDbCompareChangeType.MODIFY,
                            "tableName", "表英文名", current.tableName(), target.tableName()));
                    details = List.copyOf(identityDetails);
                }
                if (details.isEmpty()) return current;
                if (!dao.updateCustomSqlRegistration(id, current.version(), target, current.deleted())) {
                    throw new ReplayDatabaseComparisonVersionConflictException();
                }
                dao.replaceFields(id, target.fields(), now);
                writeAudit(id, target.schemaName(), target.tableName(), operation,
                        current.version() + 1, current.deleted() ? "重新登记自定义 SQL" : null, actor, now, details);
                return dao.findByIdIncludingDeleted(id);
            }));
        } catch (DuplicateKeyException exception) {
            requireAvailableCustomSqlTable(customSqlRegistration(snapshot, request, prepared, person,
                    actor, now, partitions, samples), id);
            throw exception;
        }
    }

    private void requireAvailableCustomSqlTable(ReplayDbCompareRegistration target, Long currentId) {
        var existing = currentId == null
                ? dao.findFirstBySchemaAndSource(target.schemaName(), target.sourceTableName())
                : dao.findBySchemaAndTable(target.schemaName(), target.tableName());
        if (existing != null && !existing.id().equals(currentId)) {
            throw new ReplayDatabaseComparisonGenerationException(HttpStatus.CONFLICT,
                    "DUPLICATE_REGISTRATION", existing.deleted()
                    ? "该表已登记且已删除，请进入原登记重新登记后编辑自定义 SQL"
                    : "该表已登记，请进入原登记编辑自定义 SQL",
                    Map.of("existingId", existing.id(), "schema", target.schemaName(), "tableName", target.tableName()));
        }
    }

    private ReplayDatabaseComparisonCustomSqlAnalyzer.PreparedSql prepareCustomSql(ReplayDbCompareCustomSqlSaveRequest request) {
        var prepared = prepareCustomSql(request.sql(), request.fieldNames(), request.resultKeyColumns(), request.compareLimit());
        if (!identifier(prepared.analysis().sourceTableName()).equals(identifier(request.sourceTableName()))) {
            throw new ReplayDatabaseComparisonGenerationException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CUSTOM_SQL_SOURCE_MISMATCH", "SQL 来源表与关联基础表不一致", null);
        }
        return prepared;
    }

    private ReplayDatabaseComparisonCustomSqlAnalyzer.PreparedSql prepareCustomSql(
            String sql, List<String> fields, List<String> keys, Long limit) {
        try {
            return customSqlAnalyzer.prepare(sql, fields, keys, limit);
        } catch (IllegalArgumentException exception) {
            throw invalidCustomSql(exception.getMessage());
        }
    }

    private ReplayDatabaseComparisonGenerationException invalidCustomSql(String reason) {
        return new ReplayDatabaseComparisonGenerationException(HttpStatus.UNPROCESSABLE_ENTITY,
                "CUSTOM_SQL_INVALID", reason, null);
    }

    private ReplayDatabaseTablePersonRow customSqlPerson(String source) {
        var person = tablePersonDao == null ? null : tablePersonDao.findByTableName(source);
        if (person == null) throw new IllegalArgumentException("该表未配置数据库表人员清单，请先导入人员清单：" + source);
        requireDomain(person.domain());
        return person;
    }

    private ReplayDbCompareRegistration customSqlRegistration(
            ReplayDbCompareRegistration current, ReplayDbCompareCustomSqlSaveRequest request,
            ReplayDatabaseComparisonCustomSqlAnalyzer.PreparedSql prepared, ReplayDatabaseTablePersonRow person,
            Actor actor, LocalDateTime now, int partitions, int samples) {
        var analysis = prepared.analysis();
        String developer = person.developer() == null ? "" : person.developer().split("\\(", 2)[0];
        return new ReplayDbCompareRegistration(current == null ? null : current.id(), analysis.schemaName(),
                current == null ? analysis.sourceTableName() : current.tableName(), analysis.sourceTableComment(), person.domain(), person.developerEmpNos(), person.developerUsernames(), developer,
                person.technologyOwnerEmpNos(), person.technologyOwner(), LocalDate.now(clock), false, null, null, null,
                current == null ? 0 : current.version() + 1,
                current == null ? actor.empNo() : current.createdBy(), current == null ? actor.name() : current.createdName(),
                current == null ? now : current.createdAt(), actor.empNo(), actor.name(), now, prepared.fields(), null,
                request.compareLimit(), prepared.comparisonKey().columnNames(), null)
                .withPartitionNum(partitions).withSampleLimit(samples)
                .withMaxPartitionNum(maximumForSave(request.maxPartitionNum(), current == null ? 64 : current.maxPartitionNum())).withComparisonKey(prepared.comparisonKey())
                .withCustomSql(new ReplayDbCompareCustomSql(analysis.sql(), analysis.sourceTableName(), analysis.resultColumns()))
                .withConfigurationIdentity(configuredIdentity(analysis.sourceTableName(), request.displayName(), current));
    }

    public ReplayDbCompareRegistration update(
            long id,
            ReplayDbCompareSaveRequest request,
            ReplayIssueOperator operator) {
        Actor actor = requireActor(operator);
        ReplayDbCompareRegistration snapshot = requireRegistration(id);
        requireRequestedSource(snapshot, request.tableName());
        if (snapshot.customSql() != null) throw new IllegalArgumentException("请使用自定义 SQL 编辑入口");
        if (request.fieldNames().isEmpty()) {
            if (!request.deleteWhenNoFields()) {
                throw new IllegalArgumentException("比对字段不能为空");
            }
            ReplayBaseValidatedTable emptyTable = metadataService.requireTableWithColumns(snapshot.sourceTableName(), List.of());
            ReplayDatabaseComparisonKeys.choose(emptyTable.tableName(), emptyTable.keyOptions(), request.comparisonKeyName(), null);
            LocalDateTime now = LocalDateTime.now(clock);
            ReplayDbCompareRegistration deleted = transactionTemplate.execute(status -> {
                ReplayDbCompareRegistration current = requireRegistration(id);
                requireActive(current);
                requireVersion(current, request.version());
                partitionForSave(request.partitionNum(), current.partitionNum(), operator);
                sampleForSave(request.sampleLimit(), current.sampleLimit(), operator);
                maximumForSave(request.maxPartitionNum(), current.maxPartitionNum());
                return deleteInside(current, "全部比对字段已移除", actor, now);
            });
            return requiredResult(deleted);
        }
        PreparedSave prepared = prepare(snapshot.sourceTableName(), request.domainName(),
                request.groupOwnerEmpNo(), request.fieldNames(),
                request.whereCondition(), request.compareLimit(), snapshot, request.comparisonKeyName());
        LocalDateTime now = LocalDateTime.now(clock);

        ReplayDbCompareRegistration result = transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration current = requireRegistration(id);
            requireActive(current);
            requireVersion(current, request.version());
            requireSameTable(current, prepared.table());
            ReplayDbCompareRegistration target = updatedRegistration(
                    current, prepared, actor, LocalDate.now(clock), now, false)
                    .withPartitionNum(partitionForSave(request.partitionNum(), current.partitionNum(), operator))
                    .withSampleLimit(sampleForSave(request.sampleLimit(), current.sampleLimit(), operator))
                    .withMaxPartitionNum(maximumForSave(request.maxPartitionNum(), current.maxPartitionNum()))
                    .withConfigurationIdentity(configuredIdentity(current.sourceTableName(), request.configurationName(), current));
            List<ReplayDbCompareAuditDetailDraft> details =
                    withPartitionAudit(auditDiff.compare(state(current), state(target), ReplayDbCompareAuditOperation.UPDATE),
                            current.partitionNum(), target.partitionNum());
            details = withSampleAudit(details, current.sampleLimit(), target.sampleLimit());
            details = withMaximumAudit(details, current.maxPartitionNum(), target.maxPartitionNum());
            details = withConfigurationNameAudit(details, current.configurationName(), target.configurationName());
            if (details.isEmpty()) {
                return current;
            }
            if (!dao.updateRegistration(
                    id, current.version(), target.tableComment(), target.domainName(),
                    target.groupOwnerEmpNo(), target.groupOwnerName(), target.registeredDate(),
                    prepared.person() == null ? actor.empNo() : developerEmpNo(prepared),
                    prepared.person() == null ? actor.username() : developerUsername(prepared),
                    prepared.person() == null ? actor.name() : developerName(prepared), actor.empNo(), actor.name(), now,
                    target.whereCondition(), target.compareLimit(), target.orderingPrimaryKeyNames(), target.partitionNum(), target.sampleLimit(), target.comparisonKey(), target.maxPartitionNum())) {
                throw new ReplayDatabaseComparisonVersionConflictException();
            }
            dao.updateConfigurationIdentity(id, target.configurationIdentity());
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
        if (snapshot.customSql() != null) throw new IllegalArgumentException("自定义 SQL 请通过自定义 SQL 入口新建登记");
        if (!snapshot.deleted()) {
            throw new IllegalStateException("当前登记未删除，不能重新登记");
        }
        PreparedSave prepared = prepare(snapshot.sourceTableName(), request.domainName(),
                request.groupOwnerEmpNo(), request.fieldNames(),
                request.whereCondition(), request.compareLimit(), snapshot, request.comparisonKeyName());
        LocalDateTime now = LocalDateTime.now(clock);

        ReplayDbCompareRegistration result = transactionTemplate.execute(status -> {
            ReplayDbCompareRegistration current = requireRegistration(id);
            if (!current.deleted()) {
                throw new ReplayDatabaseComparisonVersionConflictException();
            }
            requireVersion(current, request.version());
            requireSameTable(current, prepared.table());
            ReplayDbCompareRegistration target = updatedRegistration(
                    current, prepared, actor, LocalDate.now(clock), now, false)
                    .withPartitionNum(partitionForSave(request.partitionNum(), current.partitionNum(), operator))
                    .withSampleLimit(sampleForSave(request.sampleLimit(), current.sampleLimit(), operator))
                    .withMaxPartitionNum(maximumForSave(request.maxPartitionNum(), current.maxPartitionNum()))
                    .withConfigurationIdentity(configuredIdentity(current.sourceTableName(), request.configurationName(), current));
            List<ReplayDbCompareAuditDetailDraft> details =
                    withPartitionAudit(auditDiff.compare(null, state(target), ReplayDbCompareAuditOperation.REREGISTER),
                            current.partitionNum(), target.partitionNum());
            details = withSampleAudit(details, current.sampleLimit(), target.sampleLimit());
            details = withMaximumAudit(details, current.maxPartitionNum(), target.maxPartitionNum());
            if (!dao.reregisterRegistration(
                    id, current.version(), target.tableComment(), target.domainName(),
                    target.groupOwnerEmpNo(), target.groupOwnerName(), target.registeredDate(),
                    prepared.person() == null ? actor.empNo() : developerEmpNo(prepared),
                    prepared.person() == null ? actor.username() : developerUsername(prepared),
                    prepared.person() == null ? actor.name() : developerName(prepared), now,
                    target.whereCondition(), target.compareLimit(), target.orderingPrimaryKeyNames(), target.partitionNum(), target.sampleLimit(), target.comparisonKey(), target.maxPartitionNum())) {
                throw new ReplayDatabaseComparisonVersionConflictException();
            }
            dao.updateConfigurationIdentity(id, target.configurationIdentity());
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
            return new ReplayDbCompareListPage(
                    items, page.page(), page.size(), page.total(),
                    page.globalTableCount(), page.globalFieldCount(), page.globalCustomSqlCount());
        } catch (ReplayBaseDatabaseUnavailableException exception) {
            List<ReplayDbCompareListItem> items = page.items().stream()
                    .map(item -> withValidation(item, ReplayDbCompareMetadataValidation.unavailable()))
                    .toList();
            return new ReplayDbCompareListPage(
                    items, page.page(), page.size(), page.total(),
                    page.globalTableCount(), page.globalFieldCount(), page.globalCustomSqlCount());
        }
    }

    private ReplayDbCompareListPage searchByMetadataStatus(ReplayDbCompareQuery query) {
        if (query.metadataStatuses().stream()
                .anyMatch(status -> status != ReplayDbCompareMetadataStatus.MISSING_FIELDS
                        && status != ReplayDbCompareMetadataStatus.MISSING_CONDITION_FIELDS
                        && status != ReplayDbCompareMetadataStatus.ORDERING_PRIMARY_KEY_CHANGED
                        && status != ReplayDbCompareMetadataStatus.CUSTOM_SQL_INVALID
                        && status != ReplayDbCompareMetadataStatus.TABLE_MISSING)) {
            throw new IllegalArgumentException("仅支持筛选字段缺失、排序主键变更或母库表已删除状态");
        }
        Set<ReplayDbCompareMetadataStatus> requestedStatuses = Set.copyOf(query.metadataStatuses());
        Set<String> requestedTables = query.tableNames().stream()
                .map(this::identifier)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        ReplayDbCompareQuery candidateQuery = requestedTables.isEmpty()
                ? query
                : new ReplayDbCompareQuery(
                        query.tableKeyword(), query.fieldKeyword(), query.domains(),
                        query.reviserEmpNos(), query.groupOwnerEmpNos(),
                        query.registeredDateFrom(), query.registeredDateTo(),
                        query.page(), query.size(), query.metadataStatuses(),
                        List.of(), query.fieldNames(), query.registeredDates(), query.whereConditionValues(), query.maxPartitionNums());
        List<ReplayDbCompareListItem> matched = enrichMetadata(dao.findMetadataCandidates(candidateQuery)).stream()
                .filter(item -> requestedTables.contains(identifier(item.tableName()))
                        || requestedStatuses.stream().anyMatch(status ->
                                matchesMetadataFilter(item.metadataValidation(), status)))
                .toList();
        int page = Math.max(0, query.page());
        int size = Math.min(Math.max(1, query.size()), 200);
        int fromIndex = Math.min(page * size, matched.size());
        int toIndex = Math.min(fromIndex + size, matched.size());
        ReplayDbCompareGlobalCounts globalCounts = dao.findGlobalCounts();
        return new ReplayDbCompareListPage(
                matched.subList(fromIndex, toIndex), page, size, matched.size(),
                globalCounts.tableCount(), globalCounts.fieldCount(), globalCounts.customSqlCount());
    }

    private List<ReplayDbCompareListItem> enrichMetadata(List<ReplayDbCompareListItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, List<ReplayDbCompareField>> fieldsByRegistration =
                dao.findFieldsByRegistrationIds(items.stream().map(ReplayDbCompareListItem::id).toList());
        List<String> tableNames = items.stream()
                .filter(item -> item.customSql() == null)
                .map(ReplayDbCompareListItem::sourceTableName)
                .map(this::identifier)
                .distinct()
                .toList();
        Map<String, ReplayBaseMetadataSnapshot> metadataByTable = new LinkedHashMap<>();
        for (int start = 0; start < tableNames.size(); start += METADATA_BATCH_SIZE) {
            int end = Math.min(start + METADATA_BATCH_SIZE, tableNames.size());
            metadataByTable.putAll(metadataService.inspectTables(tableNames.subList(start, end)));
        }
        return items.stream()
                .map(item -> item.customSql() != null
                        ? withValidation(item, customSqlValidation(item.schemaName(), item.customSql(),
                                fieldsByRegistration.getOrDefault(item.id(), List.of()), item.comparisonKey(), item.compareLimit()))
                        : withValidation(item, validationFor(
                        metadataByTable.get(identifier(item.sourceTableName())),
                        fieldsByRegistration.getOrDefault(item.id(), List.of()),
                        item.whereCondition(), item.compareLimit(), item.orderingPrimaryKeyNames(), item.comparisonKey()), currentPrimaryKeyNames(
                        metadataByTable.get(identifier(item.sourceTableName())), item.primaryKeyNames())))
                .toList();
    }

    private List<String> currentPrimaryKeyNames(
            ReplayBaseMetadataSnapshot metadata,
            List<String> fallback) {
        if (metadata == null || !metadata.tableExists()) {
            return fallback;
        }
        return metadata.columns().stream()
                .filter(ReplayBaseColumnOption::primaryKey)
                .sorted(java.util.Comparator.comparing(ReplayBaseColumnOption::primaryKeyOrder))
                .map(ReplayBaseColumnOption::columnName)
                .map(this::identifier)
                .toList();
    }

    private List<String> orderingPrimaryKeySnapshot(PreparedSave prepared) {
        if (prepared.scope().compareLimit() == null) {
            return List.of();
        }
        return prepared.comparisonKey().columnNames().stream().map(this::identifier).toList();
    }

    private ReplayDbCompareMetadataValidation validationFor(
            ReplayBaseMetadataSnapshot metadata,
            List<ReplayDbCompareField> registeredFields,
            com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree whereCondition,
            Long compareLimit,
            List<String> savedOrderingPrimaryKeyNames, ReplayDbCompareKeySelection comparisonKey) {
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
        List<String> missingConditionFields = conditionFieldNames(whereCondition).stream()
                .filter(name -> !currentNames.contains(name))
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
        if (comparisonKey == null && currentPrimaryKeys.isEmpty() && !metadata.keyOptions().isEmpty()) {
            currentPrimaryKeys = ReplayDatabaseComparisonKeys.choose(metadata.tableName(), metadata.keyOptions(), null, null).columnNames();
        }
        if (comparisonKey != null) {
            currentPrimaryKeys = metadata.keyOptions().stream()
                    .filter(key -> key.indexName().equals(comparisonKey.indexName()) && key.indexType().equals(comparisonKey.indexType()))
                    .findFirst().map(key -> key.columnNames().stream().map(this::identifier).toList()).orElse(List.of());
        }
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
        ReplayDbCompareMetadataStatus status = missingFields.isEmpty() && missingConditionFields.isEmpty()
                ? ReplayDbCompareMetadataStatus.VALID
                : ReplayDbCompareMetadataStatus.MISSING_FIELDS;
        ReplayDbCompareMetadataValidation validation = ReplayDbCompareMetadataValidation.of(
                status, missingFields, missingConditionFields, missingPrimaryKeys, formerPrimaryKeys);
        if (comparisonKey != null) {
            return validation.withOrderingPrimaryKeyDrift(comparisonKey.columnNames(), currentPrimaryKeys);
        }
        return compareLimit == null ? validation
                : validation.withOrderingPrimaryKeyDrift(savedOrderingPrimaryKeyNames, currentPrimaryKeys);
    }

    private ReplayDbCompareMetadataValidation customSqlValidation(
            String schema, ReplayDbCompareCustomSql definition, List<ReplayDbCompareField> fields,
            ReplayDbCompareKeySelection key, Long limit) {
        try {
            if (key == null || !"RESULT_KEY".equals(key.indexType())) {
                throw new IllegalArgumentException("结果比对键未配置");
            }
            var prepared = customSqlAnalyzer.prepare(definition.sql(), fields.stream()
                    .sorted(Comparator.comparingInt(ReplayDbCompareField::comparisonOrder))
                    .map(ReplayDbCompareField::columnName).toList(), key.columnNames(), limit);
            if (!identifier(schema).equals(identifier(prepared.analysis().schemaName()))
                    || !identifier(definition.sourceTableName()).equals(identifier(prepared.analysis().sourceTableName()))) {
                throw new IllegalArgumentException("SQL 来源表与登记不一致");
            }
            return ReplayDbCompareMetadataValidation.valid();
        } catch (IllegalArgumentException exception) {
            return ReplayDbCompareMetadataValidation.of(ReplayDbCompareMetadataStatus.CUSTOM_SQL_INVALID,
                    List.of(), List.of(), List.of());
        }
    }

    public ReplayDbCompareRegistration detail(long id) {
        ReplayDbCompareRegistration snapshot = mappedPerson(requireRegistration(id));
        try {
            if (snapshot.customSql() != null) {
                return withValidation(snapshot, snapshot.fields(), customSqlValidation(snapshot.schemaName(),
                        snapshot.customSql(), snapshot.fields(), snapshot.comparisonKey(), snapshot.compareLimit()));
            }
            ReplayBaseMetadataSnapshot metadata = metadataService.inspectTable(snapshot.sourceTableName());
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
            ReplayDbCompareMetadataValidation validation = validationFor(
                    metadata, snapshot.fields(), snapshot.whereCondition(),
                    snapshot.compareLimit(), snapshot.orderingPrimaryKeyNames(), snapshot.comparisonKey());
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
                dao.findMetadataCandidates(ReplayDbCompareQuery.empty(0, 200)).stream()
                        .filter(item -> item.customSql() == null).toList();
        if (registrations.isEmpty()) {
            return new ReplayDbComparePrimaryKeySyncResult(0, 0, 0, 0);
        }
        List<String> tableNames = registrations.stream()
                .map(ReplayDbCompareListItem::sourceTableName)
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
            ReplayBaseMetadataSnapshot metadata = metadataByTable.get(identifier(registration.sourceTableName()));
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
        List<String> currentPrimaryKeys;
        if (current.comparisonKey() == null) {
            currentPrimaryKeys = currentPrimaryKeyNames(metadata, List.of());
            if (currentPrimaryKeys.isEmpty() && !metadata.keyOptions().isEmpty()) {
                currentPrimaryKeys = ReplayDatabaseComparisonKeys.choose(current.tableName(), metadata.keyOptions(), null, null).columnNames();
            }
        } else {
            try {
                currentPrimaryKeys = ReplayDatabaseComparisonKeys.choose(current.tableName(), metadata.keyOptions(), null,
                        current.comparisonKey()).columnNames();
            } catch (IllegalArgumentException invalidKey) {
                return new SyncOutcome(0); // Generation gate reports drift; never silently choose another index.
            }
        }
        if (current.compareLimit() != null && current.orderingPrimaryKeyNames().isEmpty()) {
            dao.initializeOrderingPrimaryKeys(current.id(), currentPrimaryKeys);
        }
        List<ReplayDbCompareField> synchronizedFields =
                addMissingPrimaryKeys(current.fields(), metadata.columns(), currentPrimaryKeys);
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
                current.comparisonKey() == null ? "自动同步母库主键" : "自动同步母库比对键", new Actor("SYSTEM", "SYSTEM", "系统"), now, details);
        return new SyncOutcome(addedFieldCount);
    }

    private List<ReplayDbCompareField> addMissingPrimaryKeys(
            List<ReplayDbCompareField> registeredFields,
            List<ReplayBaseColumnOption> currentColumns, List<String> keyNames) {
        List<ReplayDbCompareField> result = new ArrayList<>(registeredFields.stream()
                .sorted(Comparator.comparingInt(ReplayDbCompareField::comparisonOrder))
                .toList());
        Map<String, ReplayBaseColumnOption> columnsByName = new LinkedHashMap<>();
        currentColumns.forEach(column -> columnsByName.put(identifier(column.columnName()), column));
        List<ReplayBaseColumnOption> currentPrimaryKeys = keyNames.stream()
                .map(name -> columnsByName.get(identifier(name))).filter(java.util.Objects::nonNull).toList();
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
                    key.columnName(), key.columnComment(), key.ordinalPosition(), key.primaryKey(), 0));
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
                "SYSTEM", "系统", updatedAt, fields,
                current.whereCondition(), current.compareLimit(),
                current.orderingPrimaryKeyNames(), null).withPartitionNum(current.partitionNum()).withSampleLimit(current.sampleLimit()).withMaxPartitionNum(current.maxPartitionNum()).withComparisonKey(current.comparisonKey()).withConfigurationIdentity(current.configurationIdentity());
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
        if ("whereCondition".equals(request.targetColumn())) {
            List<ReplayDbCompareHeaderFilterOption> matched = ordinary.options().stream()
                    .map(this::queryConditionOption)
                    .filter(option -> matchesConditionLabel(option.label(), request.keyword()))
                    .toList();
            List<ReplayDbCompareHeaderFilterOption> options = matched.stream()
                    .limit(request.effectiveLimit())
                    .toList();
            return new ReplayDbCompareHeaderFilterResult(
                    options, matched.size(), ordinary.matchedRegistrationCount(), matched.size() > options.size());
        }
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

    private ReplayDbCompareHeaderFilterOption queryConditionOption(
            ReplayDbCompareHeaderFilterOption option) {
        if ("__CUSTOM_SQL__".equals(option.value())) {
            return new ReplayDbCompareHeaderFilterOption(option.value(), "自定义 SQL", option.count());
        }
        ReplayDbCompareScopeFilterValue scope = ReplayDbCompareScopeFilterValue.decode(option.value());
        String label;
        if (scope != null) {
            List<String> lines = new ArrayList<>();
            if (scope.conditionJson() != null) {
                lines.add("where " + conditionFilterLabel(conditionCodec.decode(scope.conditionJson())));
            }
            if (!scope.primaryKeyNames().isEmpty()) {
                lines.add("order by " + String.join(",", scope.primaryKeyNames()));
            }
            lines.add("limit " + scope.compareLimit());
            label = String.join("\n", lines);
        } else {
            label = FULL_TABLE_FILTER_VALUE.equals(option.value())
                    ? "全表"
                    : "where " + conditionFilterLabel(conditionCodec.decode(option.value()));
        }
        return new ReplayDbCompareHeaderFilterOption(option.value(), label, option.count());
    }

    private String conditionFilterLabel(
            com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree condition) {
        String label = conditionLabeler.label(condition);
        boolean singleCondition = condition != null
                && condition.groups().size() == 1
                && condition.groups().get(0).conditions().size() == 1;
        return singleCondition && label.startsWith("(") && label.endsWith(")")
                ? label.substring(1, label.length() - 1)
                : label;
    }

    private boolean matchesConditionLabel(String label, String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || label.toLowerCase(Locale.ROOT).contains(normalized);
    }

    private List<StatusFilterLabel> statusFilterLabels() {
        return List.of(
                new StatusFilterLabel(MISSING_FIELDS_LABEL, ReplayDbCompareMetadataStatus.MISSING_FIELDS),
                new StatusFilterLabel(MISSING_CONDITION_FIELDS_LABEL,
                        ReplayDbCompareMetadataStatus.MISSING_CONDITION_FIELDS),
                new StatusFilterLabel(ORDERING_PRIMARY_KEY_CHANGED_LABEL,
                        ReplayDbCompareMetadataStatus.ORDERING_PRIMARY_KEY_CHANGED),
                new StatusFilterLabel("自定义 SQL 无效", ReplayDbCompareMetadataStatus.CUSTOM_SQL_INVALID),
                new StatusFilterLabel(TABLE_MISSING_LABEL, ReplayDbCompareMetadataStatus.TABLE_MISSING));
    }

    private boolean matchesMetadataFilter(
            ReplayDbCompareMetadataValidation validation,
            ReplayDbCompareMetadataStatus requested) {
        return switch (requested) {
            case MISSING_FIELDS -> !validation.missingFieldNames().isEmpty();
            case MISSING_CONDITION_FIELDS -> !validation.missingConditionFieldNames().isEmpty();
            case ORDERING_PRIMARY_KEY_CHANGED -> validation.orderingPrimaryKeyChanged();
            default -> validation.status() == requested;
        };
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
                0, 1, List.of(), query.tableNames(), query.fieldNames(),
                query.registeredDates(), query.whereConditionValues(), query.maxPartitionNums());
    }

    public RegistrationState registrationState(String tableName) {
        ReplayDbCompareRegistration registration = dao.findFirstBySchemaAndSource(
                metadataService.schemaName(), tableName);
        if (registration == null) {
            return new RegistrationState("UNREGISTERED", null, null);
        }
        return new RegistrationState(registration.deleted() ? "DELETED" : "ACTIVE",
                registration.id(), registration.version());
    }

    public List<ReplayDbCompareAuditDetail> auditDetails(long auditEventId) {
        return legacyAuditDetailAdapter.adapt(dao.findAuditDetails(auditEventId));
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

    public List<ReplayDbCompareTechnologyOwnerOption> searchTechnologyOwners(String keyword, int limit) {
        return userDao.searchActiveEmployees(keyword, limit).stream()
                .map(user -> new ReplayDbCompareTechnologyOwnerOption(
                        user.getUsername(), user.getRealName(), user.getEmpNo().trim()))
                .toList();
    }

    private PreparedSave prepare(
            String tableName, String domainName, String groupOwnerEmpNo, List<String> fieldNames,
            com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree whereCondition,
            Long compareLimit, ReplayDbCompareRegistration current, String comparisonKeyName) {
        ReplayDatabaseTablePersonRow person = tablePersonDao == null ? null : tablePersonDao.findByTableName(tableName);
        if (tablePersonDao != null && person == null) {
            throw new IllegalArgumentException("该表未配置数据库表人员清单，请先导入人员清单：" + tableName);
        }
        String domain = person == null ? requireDomain(domainName) : requireText(person.domain(), "人员清单领域不能为空");
        Owner groupOwner = person == null
                ? technologyOwner(requireText(groupOwnerEmpNo, "科技负责人不能为空"), current)
                : new Owner(person.technologyOwnerEmpNos() == null ? "" : person.technologyOwnerEmpNos(),
                        person.technologyOwner() == null ? "" : person.technologyOwner());
        List<String> normalizedFields = normalizeFields(fieldNames);
        ReplayBaseValidatedTable table = metadataService.requireTableWithColumns(tableName, normalizedFields);
        ReplayDbCompareKeySelection comparisonKey = ReplayDatabaseComparisonKeys.choose(table.tableName(),
                table.keyOptions(), comparisonKeyName, current == null ? null : current.comparisonKey());
        ReplayDatabaseComparisonKeys.requireFields(table.tableName(), comparisonKey, normalizedFields);
        ReplayDbCompareCompiledScope scope = scopeCompiler.compile(
                whereCondition, compareLimit, table.allColumns());
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
        return new PreparedSave(table, domain, groupOwner, fields, scope, comparisonKey, person);
    }

    private String developerUsername(PreparedSave prepared) {
        return prepared.person() == null ? null : prepared.person().developerUsernames();
    }
    private String developerEmpNo(PreparedSave prepared) {
        return prepared.person() == null || prepared.person().developerEmpNos() == null
                ? "" : prepared.person().developerEmpNos();
    }
    private String developerName(PreparedSave prepared) {
        if (prepared.person() == null) return "";
        String display = prepared.person().developer();
        if (display == null) return "";
        int bracket = display.indexOf('(');
        return bracket < 0 ? display : display.substring(0, bracket);
    }

    private Owner technologyOwner(String identity, ReplayDbCompareRegistration current) {
        if (current != null && identity.equals(current.groupOwnerEmpNo())) {
            return new Owner(current.groupOwnerEmpNo(), current.groupOwnerName());
        }
        List<SysUser> matches = userDao.findByUsernamesOrEmpNos(List.of(identity));
        if (matches.size() > 1) {
            throw new IllegalArgumentException("科技负责人账号或工号存在歧义，请核对人员信息");
        }
        SysUser user = matches.isEmpty() ? null : matches.get(0);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new IllegalArgumentException("科技负责人不存在或已停用");
        }
        if (user.getEmpNo() == null || user.getEmpNo().isBlank()) {
            throw new IllegalArgumentException("科技负责人必须选择带工号的行员");
        }
        String employeeNumber = user.getEmpNo().trim();
        List<SysUser> storedIdentityMatches = userDao.findByUsernamesOrEmpNos(List.of(employeeNumber));
        if (storedIdentityMatches.size() != 1
                || !java.util.Objects.equals(storedIdentityMatches.get(0).getId(), user.getId())) {
            throw new IllegalArgumentException("科技负责人工号存在歧义，请核对人员信息");
        }
        return new Owner(employeeNumber, requireText(user.getRealName(), "科技负责人姓名为空"));
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
                || !identifier(registration.sourceTableName()).equals(identifier(table.tableName()))) {
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
                prepared.domainName(), prepared.person() == null ? actor.empNo() : developerEmpNo(prepared),
                prepared.person() == null ? actor.username() : developerUsername(prepared),
                prepared.person() == null ? actor.name() : developerName(prepared),
                prepared.groupOwner().identifier(), prepared.groupOwner().name(), registeredDate,
                deleted, null, null, null, current.version() + 1,
                current.createdBy(), current.createdName(), current.createdAt(),
                actor.empNo(), actor.name(), updatedAt, prepared.fields(),
                prepared.scope().conditionTree(), prepared.scope().compareLimit(),
                orderingPrimaryKeySnapshot(prepared), null).withPartitionNum(current.partitionNum()).withSampleLimit(current.sampleLimit()).withMaxPartitionNum(current.maxPartitionNum()).withComparisonKey(prepared.comparisonKey()).withConfigurationIdentity(current.configurationIdentity());
    }

    private ReplayDbCompareListItem withValidation(
            ReplayDbCompareListItem item,
            ReplayDbCompareMetadataValidation validation) {
        return withValidation(item, validation, item.primaryKeyNames());
    }

    private ReplayDbCompareListItem withValidation(
            ReplayDbCompareListItem item,
            ReplayDbCompareMetadataValidation validation,
            List<String> primaryKeyNames) {
        return new ReplayDbCompareListItem(
                item.id(), item.schemaName(), item.tableName(), item.tableComment(), item.domainName(),
                item.reviserEmpNo(), item.reviserUsername(), item.reviserName(),
                item.groupOwnerEmpNo(), item.groupOwnerName(), item.registeredDate(), item.version(),
                item.fieldCount(), item.fieldPreview(), item.whereCondition(),
                item.whereConditionConfigured(), item.compareLimit(), validation, primaryKeyNames,
                item.orderingPrimaryKeyNames()).withPartitionNum(item.partitionNum()).withSampleLimit(item.sampleLimit()).withMaxPartitionNum(item.maxPartitionNum()).withComparisonKey(item.comparisonKey()).withCustomSql(item.customSql()).withConfigurationIdentity(item.configurationIdentity());
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
                registration.updatedBy(), registration.updatedName(), registration.updatedAt(), fields,
                registration.whereCondition(), registration.compareLimit(),
                registration.orderingPrimaryKeyNames(), validation).withPartitionNum(registration.partitionNum()).withSampleLimit(registration.sampleLimit()).withMaxPartitionNum(registration.maxPartitionNum()).withComparisonKey(registration.comparisonKey()).withCustomSql(registration.customSql()).withConfigurationIdentity(registration.configurationIdentity());
    }

    private ReplayDbCompareRegistration mappedPerson(ReplayDbCompareRegistration registration) {
        if (tablePersonDao == null) return registration;
        ReplayDatabaseTablePersonRow person = tablePersonDao.findByTableName(registration.sourceTableName());
        String domain = person == null ? "" : person.domain();
        String developerName = person == null || person.developer() == null ? "" : person.developer().split("\\(", 2)[0];
        String developerUsername = person == null ? "" : person.developerUsernames();
        String developerEmpNo = person == null ? "" : person.developerEmpNos();
        String ownerName = person == null || person.technologyOwner() == null ? "" : person.technologyOwner();
        String ownerEmpNo = person == null ? "" : person.technologyOwnerEmpNos();
        return new ReplayDbCompareRegistration(
                registration.id(), registration.schemaName(), registration.tableName(), registration.tableComment(),
                domain, developerEmpNo, developerUsername, developerName, ownerEmpNo, ownerName,
                registration.registeredDate(), registration.deleted(), registration.deletedReason(),
                registration.deletedBy(), registration.deletedAt(), registration.version(),
                registration.createdBy(), registration.createdName(), registration.createdAt(),
                registration.updatedBy(), registration.updatedName(), registration.updatedAt(), registration.fields(),
                registration.whereCondition(), registration.compareLimit(), registration.orderingPrimaryKeyNames(),
                registration.metadataValidation()).withPartitionNum(registration.partitionNum())
                .withSampleLimit(registration.sampleLimit()).withMaxPartitionNum(registration.maxPartitionNum()).withComparisonKey(registration.comparisonKey()).withCustomSql(registration.customSql()).withConfigurationIdentity(registration.configurationIdentity());
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
                registration.registeredDate(), registration.deleted(), registration.fields(),
                registration.whereCondition(), registration.compareLimit(),
                registration.orderingPrimaryKeyNames(), registration.comparisonKey(), registration.customSql());
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

    private List<String> conditionFieldNames(
            com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree whereCondition) {
        if (whereCondition == null) {
            return List.of();
        }
        return whereCondition.groups().stream()
                .flatMap(group -> group.conditions().stream())
                .map(condition -> identifier(condition.columnName()))
                .distinct()
                .toList();
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
            List<ReplayDbCompareField> fields,
            ReplayDbCompareCompiledScope scope,
            ReplayDbCompareKeySelection comparisonKey,
            ReplayDatabaseTablePersonRow person) {
    }

    public record RegistrationState(String status, Long registrationId, Long registrationVersion) {
    }

    private record StatusFilterLabel(
            String label,
            ReplayDbCompareMetadataStatus status) {
    }

    private record SyncOutcome(int addedFieldCount) {
    }

    private com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigurationIdentity configuredIdentity(String source, String requestedName, ReplayDbCompareRegistration current) {
        String name = requestedName == null ? current == null ? "默认配置" : current.configurationIdentity() == null ? "" : current.configurationIdentity().configurationName() : requestedName.strip();
        if (requestedName != null && name.isEmpty()) name = "默认配置";
        return new com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigurationIdentity(source, name);
    }

    private void requireRequestedSource(ReplayDbCompareRegistration current, String requested) {
        if (!identifier(current.tableName()).equals(identifier(requested)) && !identifier(current.sourceTableName()).equals(identifier(requested))) throw new IllegalArgumentException("配置的真实来源表创建后不能更换");
    }

    private List<ReplayDbCompareAuditDetailDraft> withConfigurationNameAudit(List<ReplayDbCompareAuditDetailDraft> details, String before, String after) {
        if (java.util.Objects.equals(before, after)) return details;
        var result = new ArrayList<>(details);
        result.add(new ReplayDbCompareAuditDetailDraft(ReplayDbCompareChangeType.MODIFY, "configurationName", "配置名称", before, after));
        return List.copyOf(result);
    }
}
