package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTablePage;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonVersionDaoTest {

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonVersionDao dao;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayDatabaseComparisonVersionDao(jdbc);
        now = LocalDateTime.of(2026, 9, 14, 15, 30, 0);
    }

    @Test
    void persistsAndQueriesImmutableVersionHistoryNewestFirst() {
        long firstId = dao.insertVersion(
                "20260914-120000", "a".repeat(64), 1, 2,
                "100", "张三", now.minusHours(1));
        ReplayDbCompareRegistration account = registration(
                7L, 3L, "acct_master", "账户主表", "存款组", "100", "张三",
                List.of(field("acct_no", "账号", 1, true, 1),
                        field("customer_no", "客户号", 2, false, 2)));
        long tableId = dao.insertVersionTable(firstId, account, "li-manager");
        dao.insertVersionFields(tableId, account.fields());
        dao.insertVersion(
                "20260914-130000", "b".repeat(64), 0, 0,
                "101", "李四", now);

        ReplayDatabaseComparisonVersionDao.StoredVersion latest = dao.findLatestStoredVersion();
        ReplayDbCompareVersionPage versions = dao.findVersions(0, 20);
        ReplayDbCompareVersionTablePage snapshot = dao.searchVersion(
                "20260914-120000",
                new ReplayDbCompareVersionQuery(
                        0, 50, "acct", "customer", List.of("存款组"),
                        List.of("100"), List.of("200"),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));

        assertEquals("20260914-130000", latest.versionNo());
        assertEquals("b".repeat(64), latest.configurationHash());
        assertEquals(List.of("20260914-130000", "20260914-120000"),
                versions.items().stream().map(item -> item.versionNo()).toList());
        assertTrue(versions.items().get(0).latest());
        assertFalse(versions.items().get(1).latest());
        assertEquals(1, snapshot.total());
        assertEquals(7L, snapshot.items().get(0).sourceRegistrationId());
        assertEquals("li-manager", snapshot.items().get(0).groupOwnerUsername());
        assertEquals(List.of("acct_no", "customer_no"), snapshot.items().get(0).fields().stream()
                .map(item -> item.columnName()).toList());
        assertTrue(snapshot.items().get(0).fields().get(0).primaryKey());
    }

    @Test
    void returnsSnapshotHeaderOptionsWithoutBaseMetadata() {
        long versionId = dao.insertVersion(
                "20260914-120000", "a".repeat(64), 1, 1,
                "100", "张三", now);
        ReplayDbCompareRegistration account = registration(
                7L, 3L, "acct_master", "账户主表", "存款组", "100", "张三",
                List.of(field("acct_no", "账号", 1, true, 1)));
        long tableId = dao.insertVersionTable(versionId, account);
        dao.insertVersionFields(tableId, account.fields());

        var fieldOptions = dao.versionHeaderFilterOptions(
                "20260914-120000",
                new ReplayDbCompareHeaderFilterRequest(
                        "fieldName", "账号", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));
        var tableOptions = dao.versionHeaderFilterOptions(
                "20260914-120000",
                new ReplayDbCompareHeaderFilterRequest(
                        "tableName", "账户主", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));
        var reviserOptions = dao.versionHeaderFilterOptions(
                "20260914-120000",
                new ReplayDbCompareHeaderFilterRequest(
                        "reviser", "zhangsan", 20, null, null,
                        List.of(), List.of(), List.of(), null, null));

        assertEquals(1, fieldOptions.options().size());
        assertEquals("acct_no", fieldOptions.options().get(0).value());
        assertEquals("acct_no（账号）", fieldOptions.options().get(0).label());
        assertEquals(1, fieldOptions.matchedRegistrationCount());
        assertEquals("acct_master（账户主表）", tableOptions.options().get(0).label());
        assertEquals("张三（zhangsan）", reviserOptions.options().get(0).label());
    }

    @Test
    void reportsMissingVersionAndCapsHistoryPageSize() {
        ReplayDatabaseComparisonGenerationException error = assertThrows(
                ReplayDatabaseComparisonGenerationException.class,
                () -> dao.searchVersion("missing", ReplayDbCompareVersionQuery.empty(0, 50)));

        assertEquals("VERSION_NOT_FOUND", error.errorCode());
        assertEquals(100, dao.findVersions(0, 1000).size());
    }

    @Test
    void readsEveryTableAndFieldFromAnImmutableVersionWithoutPagination() {
        long versionId = dao.insertVersion(
                "20260914-172637", "c".repeat(64), 3, 4,
                "100", "张三", now);
        ReplayDbCompareRegistration zTable = registration(
                7L, 1L, "z_table", "Z表", "存款组", "100", "张三",
                List.of(field("normal_field", "普通字段", 2, false, 2),
                        field("pk_field", "主键字段", 1, true, 1)));
        ReplayDbCompareRegistration loanTable = registration(
                8L, 2L, "loan_table", "贷款表", "贷款组", "101", "李四",
                List.of(field("loan_id", "贷款编号", 1, true, 1)));
        ReplayDbCompareRegistration aTable = registration(
                9L, 3L, "a_table", "A表", "存款组", "102", "王五",
                List.of(field("account_id", "账户编号", 1, true, 1)));
        for (ReplayDbCompareRegistration registration : List.of(zTable, loanTable, aTable)) {
            long tableId = dao.insertVersionTable(versionId, registration);
            dao.insertVersionFields(tableId, registration.fields());
        }

        ReplayDatabaseComparisonVersionDao.StoredVersion stored =
                dao.findStoredVersion("20260914-172637");
        List<com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTableItem> snapshot =
                dao.findCompleteSnapshot(stored.id());

        assertEquals(versionId, stored.id());
        assertEquals(List.of("a_table", "z_table"), snapshot.stream()
                .filter(row -> row.domainName().equals("存款组"))
                .map(row -> row.tableName()).toList());
        assertEquals(List.of(1, 2), snapshot.stream()
                .filter(row -> row.tableName().equals("z_table"))
                .findFirst().orElseThrow().fields().stream()
                .map(field -> field.comparisonOrder()).toList());
        assertTrue(snapshot.stream()
                .filter(row -> row.tableName().equals("z_table"))
                .findFirst().orElseThrow().fields().get(0).primaryKey());
    }

    @Test
    void returnsNullForAnUnknownStoredVersionAndEmptySnapshotForAnEmptyVersion() {
        long versionId = dao.insertVersion(
                "20260914-180000", "d".repeat(64), 0, 0,
                "100", "张三", now);

        assertNull(dao.findStoredVersion("missing"));
        assertTrue(dao.findCompleteSnapshot(versionId).isEmpty());
    }

    private ReplayDbCompareRegistration registration(
            long id,
            long version,
            String tableName,
            String tableComment,
            String domain,
            String reviserEmpNo,
            String reviserName,
            List<ReplayDbCompareField> fields) {
        return new ReplayDbCompareRegistration(
                id, "base_schema", tableName, tableComment, domain,
                reviserEmpNo, "zhangsan", reviserName, "200", "李经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, version,
                "100", "张三", now, "100", "张三", now, fields);
    }

    private ReplayDbCompareField field(
            String name,
            String comment,
            int ordinal,
            boolean primaryKey,
            int comparisonOrder) {
        return new ReplayDbCompareField(name, comment, ordinal, primaryKey, comparisonOrder);
    }
}
