package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCompiledScope;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareMetadataStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionGateError;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionGateResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionSummary;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTablePage;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@Service
public class ReplayDatabaseComparisonVersionService {

    private static final int METADATA_BATCH_SIZE = 200;
    private static final String FULL_TABLE_FILTER_VALUE = "__FULL_TABLE__";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter VERSION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReplayDatabaseComparisonService registrationService;
    private final ReplayBaseMetadataService metadataService;
    private final ReplayDatabaseComparisonDao registrationDao;
    private final ReplayDatabaseComparisonVersionDao versionDao;
    private final ReplayDatabaseComparisonConfigurationHasher hasher;
    private final SysUserDao userDao;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ReplayDatabaseComparisonScopeCompiler scopeCompiler =
            new ReplayDatabaseComparisonScopeCompiler(
                    new ReplayDatabaseComparisonConditionCodec());
    private final ReplayDatabaseComparisonConditionCodec conditionCodec =
            new ReplayDatabaseComparisonConditionCodec();
    private final ReplayDatabaseComparisonConditionLabeler conditionLabeler =
            new ReplayDatabaseComparisonConditionLabeler();

    @Autowired
    public ReplayDatabaseComparisonVersionService(
            ReplayDatabaseComparisonService registrationService,
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao registrationDao,
            ReplayDatabaseComparisonVersionDao versionDao,
            ReplayDatabaseComparisonConfigurationHasher hasher,
            SysUserDao userDao,
            JdbcTemplate diiResultJdbcTemplate) {
        this(registrationService, metadataService, registrationDao, versionDao, hasher, userDao,
                diiResultJdbcTemplate, Clock.system(SHANGHAI));
    }

    ReplayDatabaseComparisonVersionService(
            ReplayDatabaseComparisonService registrationService,
            ReplayBaseMetadataService metadataService,
            ReplayDatabaseComparisonDao registrationDao,
            ReplayDatabaseComparisonVersionDao versionDao,
            ReplayDatabaseComparisonConfigurationHasher hasher,
            SysUserDao userDao,
            JdbcTemplate diiResultJdbcTemplate,
            Clock clock) {
        this.registrationService = registrationService;
        this.metadataService = metadataService;
        this.registrationDao = registrationDao;
        this.versionDao = versionDao;
        this.hasher = hasher;
        this.userDao = userDao;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
        this.clock = clock;
    }

    public ReplayDbCompareVersionSummary generate(
            String suppliedToken,
            String expectedToken,
            ReplayIssueOperator operator) {
        requireToken(suppliedToken, expectedToken);
        String actorId = requireText(operator == null ? null : operator.username(), "用户未登录");
        String actorName = operator.realName() == null || operator.realName().isBlank()
                ? actorId : operator.realName().trim();
        ReplayDbCompareVersionGateResult gate;
        try {
            registrationService.synchronizePrimaryKeys();
            gate = validateAll(registrationDao.findAllActiveWithFields());
        } catch (ReplayBaseDatabaseUnavailableException exception) {
            gate = unavailableGate(registrationDao.findAllActiveWithFields());
        }
        requireGate(gate);
        List<ReplayDbCompareRegistration> snapshotRegistrations = gate.registrations();

        String configurationHash = hasher.hash(snapshotRegistrations);
        ReplayDatabaseComparisonVersionDao.StoredVersion latest = versionDao.findLatestStoredVersion();
        if (latest != null && latest.configurationHash().equals(configurationHash)) {
            throw error(HttpStatus.CONFLICT, "CONFIGURATION_UNCHANGED",
                    "当前登记与最新版本一致，无需重复生成", null);
        }

        requireUnchangedRegistrations(snapshotRegistrations, registrationDao.findAllActiveWithFields());
        LocalDateTime generatedAt = LocalDateTime.now(clock);
        String versionNo = newVersionNo(generatedAt);
        int fieldCount = snapshotRegistrations.stream()
                .mapToInt(registration -> registration.fields().size()).sum();
        try {
            ReplayDbCompareVersionSummary summary = transactionTemplate.execute(status -> {
                Map<String, String> groupOwnerUsernames = new LinkedHashMap<>();
                long versionId = versionDao.insertVersion(
                        versionNo, configurationHash, snapshotRegistrations.size(), fieldCount,
                        actorId, actorName, generatedAt);
                for (ReplayDbCompareRegistration registration : snapshotRegistrations) {
                    long versionTableId = versionDao.insertVersionTable(
                            versionId, registration,
                            resolveGroupOwnerUsername(registration.groupOwnerEmpNo(), groupOwnerUsernames));
                    versionDao.insertVersionFields(versionTableId, registration.fields());
                }
                return new ReplayDbCompareVersionSummary(
                        versionNo, actorId, actorName, generatedAt,
                        snapshotRegistrations.size(), fieldCount, true);
            });
            if (summary == null) {
                throw new IllegalStateException("数据库比对字段版本事务未完成");
            }
            return summary;
        } catch (DuplicateKeyException exception) {
            throw error(HttpStatus.CONFLICT, "VERSION_NUMBER_CONFLICT",
                    "当前秒已生成版本，请稍后重试", null);
        }
    }

    public ReplayDbCompareVersionSummary latest() {
        return versionDao.findLatestVersion();
    }

    public ReplayDbCompareVersionPage versions(int page, int size) {
        return versionDao.findVersions(page, size);
    }

    public ReplayDbCompareVersionTablePage searchVersion(
            String versionNo,
            ReplayDbCompareVersionQuery query) {
        return versionDao.searchVersion(versionNo, query);
    }

    public ReplayDbCompareHeaderFilterResult versionHeaderFilterOptions(
            String versionNo,
            ReplayDbCompareHeaderFilterRequest request) {
        ReplayDbCompareHeaderFilterResult result = versionDao.versionHeaderFilterOptions(versionNo, request);
        if (!"whereCondition".equals(request.targetColumn())) {
            return result;
        }
        String keyword = request.keyword() == null
                ? ""
                : request.keyword().trim().toLowerCase(Locale.ROOT);
        List<ReplayDbCompareHeaderFilterOption> matched = result.options().stream()
                .map(this::queryConditionOption)
                .filter(option -> keyword.isEmpty()
                        || option.label().toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
        List<ReplayDbCompareHeaderFilterOption> options = matched.stream()
                .limit(request.effectiveLimit())
                .toList();
        return new ReplayDbCompareHeaderFilterResult(
                options, matched.size(), result.matchedRegistrationCount(), matched.size() > options.size());
    }

    private ReplayDbCompareHeaderFilterOption queryConditionOption(
            ReplayDbCompareHeaderFilterOption option) {
        String label = FULL_TABLE_FILTER_VALUE.equals(option.value())
                ? "全表"
                : conditionLabeler.label(conditionCodec.decode(option.value()));
        return new ReplayDbCompareHeaderFilterOption(option.value(), label, option.count());
    }

    ReplayDbCompareVersionGateResult validateAll(
            List<ReplayDbCompareRegistration> registrations) {
        if (registrations.isEmpty()) {
            return new ReplayDbCompareVersionGateResult(List.of(), List.of());
        }
        List<ReplayDbCompareRegistration> valid = new ArrayList<>();
        List<ReplayDbCompareVersionGateError> errors = new ArrayList<>();
        for (int start = 0; start < registrations.size(); start += METADATA_BATCH_SIZE) {
            int end = Math.min(start + METADATA_BATCH_SIZE, registrations.size());
            List<ReplayDbCompareRegistration> batch = registrations.subList(start, end);
            try {
                Map<String, ReplayBaseMetadataSnapshot> metadata = metadataService.inspectTables(
                        batch.stream().map(ReplayDbCompareRegistration::tableName).toList());
                for (ReplayDbCompareRegistration registration : batch) {
                    validateRegistration(registration, metadata.get(normalize(registration.tableName())),
                            valid, errors);
                }
            } catch (ReplayBaseDatabaseUnavailableException exception) {
                batch.forEach(registration -> errors.add(gateError(
                        registration, ReplayDbCompareMetadataStatus.UNAVAILABLE,
                        List.of(), "母库校验暂不可用")));
            }
        }
        errors.sort(Comparator.comparing(ReplayDbCompareVersionGateError::tableName));
        valid.sort(Comparator.comparing((ReplayDbCompareRegistration item) -> normalize(item.schemaName()))
                .thenComparing(item -> normalize(item.tableName())));
        return new ReplayDbCompareVersionGateResult(valid, errors);
    }

    String newVersionNo(LocalDateTime generatedAt) {
        return generatedAt.atZone(clock.getZone()).withZoneSameInstant(SHANGHAI).format(VERSION_FORMAT);
    }

    private void validateRegistration(
            ReplayDbCompareRegistration registration,
            ReplayBaseMetadataSnapshot metadata,
            List<ReplayDbCompareRegistration> valid,
            List<ReplayDbCompareVersionGateError> errors) {
        if (metadata == null || !metadata.tableExists()) {
            errors.add(gateError(registration, ReplayDbCompareMetadataStatus.TABLE_MISSING,
                    List.of(), "母库表已删除"));
            return;
        }
        Map<String, ReplayBaseColumnOption> columns = new LinkedHashMap<>();
        metadata.columns().forEach(column -> columns.put(normalize(column.columnName()), column));
        int originalErrorCount = errors.size();
        List<String> missing = registration.fields().stream()
                .map(ReplayDbCompareField::columnName)
                .filter(name -> !columns.containsKey(normalize(name)))
                .toList();
        if (!missing.isEmpty()) {
            errors.add(gateError(registration, ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                    missing, "比对字段母库中不存在：" + String.join("、", missing)));
        }
        ReplayDbCompareCompiledScope compiledScope = null;
        try {
            compiledScope = scopeCompiler.compile(
                    registration.whereCondition(), registration.compareLimit(), metadata.columns());
        } catch (ReplayDatabaseComparisonScopeException exception) {
            exception.errors().forEach(error -> errors.add(gateError(
                    registration, ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                    List.of(), error.path() + "：" + error.reason())));
        }
        if (registration.compareLimit() != null) {
            if (!hasCompletePrimaryKeyOrder(metadata.columns())) {
                errors.add(gateError(registration, ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                        List.of(), "配置比对条数时，母库表必须存在完整且有序的主键"));
            } else {
                List<String> savedOrderingPrimaryKeys = registration.orderingPrimaryKeyNames().stream()
                        .map(this::normalize)
                        .toList();
                List<String> currentOrderingPrimaryKeys = metadata.columns().stream()
                        .filter(ReplayBaseColumnOption::primaryKey)
                        .sorted(Comparator.comparing(ReplayBaseColumnOption::primaryKeyOrder))
                        .map(ReplayBaseColumnOption::columnName)
                        .map(this::normalize)
                        .toList();
                if (!savedOrderingPrimaryKeys.isEmpty()
                        && !savedOrderingPrimaryKeys.equals(currentOrderingPrimaryKeys)) {
                    errors.add(gateError(
                            registration,
                            ReplayDbCompareMetadataStatus.ORDERING_PRIMARY_KEY_CHANGED,
                            List.of(),
                            "排序主键已变更，原顺序：" + String.join("、", savedOrderingPrimaryKeys)
                                    + "；当前顺序：" + String.join("、", currentOrderingPrimaryKeys)));
                }
            }
        }
        if (errors.size() > originalErrorCount) {
            return;
        }
        List<ReplayDbCompareField> snapshotFields = registration.fields().stream()
                .sorted(Comparator.comparingInt(ReplayDbCompareField::comparisonOrder))
                .map(field -> {
                    ReplayBaseColumnOption column = columns.get(normalize(field.columnName()));
                    return new ReplayDbCompareField(
                            column.columnName(), column.columnComment(), column.ordinalPosition(),
                            column.primaryKey(), field.comparisonOrder(),
                            column.primaryKeyOrder(), null);
                })
                .toList();
        valid.add(new ReplayDbCompareRegistration(
                registration.id(), registration.schemaName(), registration.tableName(),
                metadata.tableComment(), registration.domainName(), registration.reviserEmpNo(),
                registration.reviserUsername(), registration.reviserName(),
                registration.groupOwnerEmpNo(), registration.groupOwnerName(),
                registration.registeredDate(), false, null, null, null, registration.version(),
                registration.createdBy(), registration.createdName(), registration.createdAt(),
                registration.updatedBy(), registration.updatedName(), registration.updatedAt(),
                snapshotFields, compiledScope.conditionTree(), compiledScope.compareLimit(),
                registration.orderingPrimaryKeyNames(), compiledScope.whereSql(), null)
                .withPartitionNum(registration.partitionNum()));
    }

    private boolean hasCompletePrimaryKeyOrder(List<ReplayBaseColumnOption> columns) {
        List<ReplayBaseColumnOption> primaryKeys = columns.stream()
                .filter(ReplayBaseColumnOption::primaryKey)
                .toList();
        if (primaryKeys.isEmpty()) {
            return false;
        }
        Set<Integer> orders = new HashSet<>();
        for (ReplayBaseColumnOption primaryKey : primaryKeys) {
            Integer order = primaryKey.primaryKeyOrder();
            if (order == null || order <= 0 || !orders.add(order)) {
                return false;
            }
        }
        for (int order = 1; order <= primaryKeys.size(); order++) {
            if (!orders.contains(order)) {
                return false;
            }
        }
        return true;
    }

    private ReplayDbCompareVersionGateResult unavailableGate(
            List<ReplayDbCompareRegistration> registrations) {
        return new ReplayDbCompareVersionGateResult(List.of(), registrations.stream()
                .map(registration -> gateError(
                        registration, ReplayDbCompareMetadataStatus.UNAVAILABLE,
                        List.of(), "母库校验暂不可用"))
                .toList());
    }

    private ReplayDbCompareVersionGateError gateError(
            ReplayDbCompareRegistration registration,
            ReplayDbCompareMetadataStatus status,
            List<String> missingFields,
            String reason) {
        return new ReplayDbCompareVersionGateError(
                registration.schemaName(), registration.tableName(), registration.tableComment(),
                registration.reviserEmpNo(), registration.reviserUsername(), registration.reviserName(),
                registration.groupOwnerEmpNo(), registration.groupOwnerName(),
                status, missingFields, reason);
    }

    private void requireGate(ReplayDbCompareVersionGateResult gate) {
        if (gate.valid()) {
            return;
        }
        String message = gate.errors().isEmpty()
                ? "没有可生成版本的有效登记"
                : gate.errors().size() + " 张表未通过版本生成门禁";
        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "VERSION_GATE_BLOCKED",
                message, Map.of("errors", gate.errors()));
    }

    private void requireUnchangedRegistrations(
            List<ReplayDbCompareRegistration> validated,
            List<ReplayDbCompareRegistration> current) {
        List<RegistrationRevision> expected = revisions(validated);
        List<RegistrationRevision> actual = revisions(current);
        if (!expected.equals(actual)) {
            throw error(HttpStatus.CONFLICT, "REGISTRATION_CHANGED",
                    "登记数据已变化，请重新生成", null);
        }
    }

    private List<RegistrationRevision> revisions(List<ReplayDbCompareRegistration> registrations) {
        return registrations.stream()
                .map(registration -> new RegistrationRevision(registration.id(), registration.version()))
                .sorted(Comparator.comparing(RegistrationRevision::id))
                .toList();
    }

    private void requireToken(String supplied, String expected) {
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("版本生成口令未配置");
        }
        byte[] suppliedBytes = supplied == null
                ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(suppliedBytes, expectedBytes)) {
            throw error(HttpStatus.UNAUTHORIZED, "INVALID_TRIGGER_TOKEN", "口令错误", null);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private String resolveGroupOwnerUsername(String identity, Map<String, String> cache) {
        if (identity == null || identity.isBlank()) {
            return null;
        }
        String normalized = identity.trim();
        if (cache.containsKey(normalized)) {
            return cache.get(normalized);
        }
        SysUser user = userDao.findByEmpNo(normalized);
        if (user == null) {
            user = userDao.findByUsername(normalized);
        }
        String username = user == null || user.getUsername() == null || user.getUsername().isBlank()
                ? null : user.getUsername().trim();
        cache.put(normalized, username);
        return username;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private ReplayDatabaseComparisonGenerationException error(
            HttpStatus status,
            String code,
            String message,
            Object data) {
        return new ReplayDatabaseComparisonGenerationException(status, code, message, data);
    }


    private record RegistrationRevision(Long id, long version) {
    }
}
