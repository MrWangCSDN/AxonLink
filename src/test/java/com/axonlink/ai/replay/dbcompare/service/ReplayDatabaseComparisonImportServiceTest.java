package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseValidatedTable;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ReplayDatabaseComparisonImportServiceTest {

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonDao dao;
    private ReplayDatabaseComparisonVersionDao versionDao;
    private ReplayBaseMetadataService metadata;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"))
                .execute(jdbc.getDataSource());
        ReplayDatabaseComparisonServiceTest.createUsers(jdbc);
        dao = new ReplayDatabaseComparisonDao(jdbc);
        versionDao = new ReplayDatabaseComparisonVersionDao(jdbc);
        metadata = mock(ReplayBaseMetadataService.class);
        when(metadata.requireTableWithColumns(anyString(), any(Collection.class)))
                .thenAnswer(invocation -> validated(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    void returnsAllParserBaseAndPersonnelErrorsWithoutWriting() throws Exception {
        when(metadata.requireTableWithColumns(anyString(), any(Collection.class)))
                .thenAnswer(invocation -> {
                    String table = invocation.getArgument(0);
                    Collection<String> fields = invocation.getArgument(1);
                    if (table.equals("bad_table")) {
                        throw new IllegalArgumentException("母库表不存在：bad_table");
                    }
                    if (fields.contains("bad_field")) {
                        throw new IllegalArgumentException("母库字段不存在：bad_field");
                    }
                    return validated(table, fields);
                });

        ReplayDbCompareImportResult result = service().importFile(
                new ByteArrayInputStream(workbook(
                        input("存款", "", "acct_no", "创建人"),
                        input("贷款", "bad_table", "id", "不存在的人"),
                        input("公共", "customer_master", "bad_field", "钱经理"))),
                new ReplayIssueOperator("creator", "创建人"));

        assertFalse(result.success());
        assertTrue(result.errors().stream().anyMatch(error -> error.reason().equals("表英文名不能为空")));
        assertTrue(result.errors().stream().anyMatch(error -> error.reason().contains("bad_table")));
        assertTrue(result.errors().stream().anyMatch(error -> error.reason().contains("bad_field")));
        assertTrue(result.errors().stream().anyMatch(error -> error.reviserInput().equals("不存在的人")
                && error.reason().contains("人员不存在")));
        assertTrue(result.errors().stream().allMatch(error -> error.sheetName() != null));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_registration", Integer.class));
    }

    @Test
    void importsTrustedDataThenMergesFieldsAndPreservesExistingGroupOwner() throws Exception {
        ReplayDatabaseComparisonImportService service = service();
        ReplayDbCompareImportResult first = service.importFile(
                new ByteArrayInputStream(workbook(
                        input("存款", "acct_master", "acct_no", "创建人"))),
                new ReplayIssueOperator("editor", "编辑人"));
        ReplayDbCompareRegistration created = dao.findBySchemaAndTable("base_schema", "acct_master");
        jdbc.update("UPDATE dii_replay_db_compare_registration SET group_owner_emp_no='101',group_owner_name='赵经理' WHERE id=?",
                created.id());

        ReplayDbCompareImportResult second = service.importFile(
                new ByteArrayInputStream(workbook(
                        input("存款", "acct_master", "customer_no", "编辑人"))),
                new ReplayIssueOperator("creator", "创建人"));

        ReplayDbCompareRegistration merged = dao.findBySchemaAndTable("base_schema", "acct_master");
        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals("acct_master备注", merged.tableComment());
        assertEquals("存款组", merged.domainName());
        assertEquals(List.of("acct_no", "customer_no"), merged.fields().stream()
                .map(field -> field.columnName()).toList());
        assertEquals("200", merged.reviserEmpNo());
        assertEquals("editor", merged.reviserUsername());
        assertEquals("编辑人", merged.reviserName());
        assertEquals("101", merged.groupOwnerEmpNo());
        assertEquals("赵经理", merged.groupOwnerName());
        assertEquals(List.of("系统", "系统"),
                dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).items().stream()
                        .map(event -> event.operatorName()).toList());
        assertEquals(List.of("SYSTEM", "SYSTEM"),
                dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).items().stream()
                        .map(event -> event.operatorEmpNo()).toList());
        assertEquals(List.of("SYSTEM", "SYSTEM"),
                dao.searchAuditEvents(ReplayDbCompareAuditQuery.empty(0, 50)).items().stream()
                        .map(event -> event.operatorUsername()).toList());
    }

    @Test
    void allowsBlankReviserAndLeavesNewGroupOwnerBlank() throws Exception {
        ReplayDbCompareImportResult result = service().importFile(
                new ByteArrayInputStream(workbook(
                        input("结算", "settlement_master", "settlement_no", ""))),
                new ReplayIssueOperator("creator", "创建人"));

        ReplayDbCompareRegistration registration =
                dao.findBySchemaAndTable("base_schema", "settlement_master");
        assertTrue(result.success());
        assertNull(registration.reviserEmpNo());
        assertNull(registration.reviserUsername());
        assertNull(registration.reviserName());
        assertNull(registration.groupOwnerEmpNo());
        assertNull(registration.groupOwnerName());
    }

    @Test
    void deletedTableCollisionDoesNotReactivateRecord() throws Exception {
        ReplayDatabaseComparisonImportService service = service();
        service.importFile(new ByteArrayInputStream(workbook(
                        input("存款", "acct_master", "acct_no", "创建人"))),
                new ReplayIssueOperator("creator", "创建人"));
        ReplayDbCompareRegistration current = dao.findBySchemaAndTable("base_schema", "acct_master");
        dao.markDeleted(current.id(), current.version(), "删除", "200", "编辑人",
                java.time.LocalDateTime.of(2026, 9, 12, 15, 0));
        dao.clearFields(current.id());

        ReplayDbCompareImportResult result = service.importFile(
                new ByteArrayInputStream(workbook(
                        input("存款", "acct_master", "customer_no", "创建人"))),
                new ReplayIssueOperator("creator", "创建人"));

        assertFalse(result.success());
        assertTrue(result.errors().stream().anyMatch(error -> error.reason().contains("重新登记")));
        assertTrue(dao.findBySchemaAndTable("base_schema", "acct_master").deleted());
    }

    @Test
    void secondTableAuditDetailFailureRollsBackWholeImport() throws Exception {
        ReplayDatabaseComparisonDao failingDao = spy(new ReplayDatabaseComparisonDao(jdbc));
        AtomicInteger auditDetailWrites = new AtomicInteger();
        doAnswer(invocation -> {
            if (auditDetailWrites.incrementAndGet() == 2) {
                throw new IllegalStateException("second audit detail insert failed");
            }
            return invocation.callRealMethod();
        }).when(failingDao).insertAuditDetails(any(Long.class), any(List.class), any());
        ReplayDatabaseComparisonImportService failingService = new ReplayDatabaseComparisonImportService(
                new ReplayDatabaseComparisonExcelParser(), metadata, failingDao, versionDao, new SysUserDao(jdbc),
                new ReplayDatabaseComparisonAuditDiff(), jdbc,
                Clock.fixed(Instant.parse("2026-09-12T06:36:08Z"), ZoneId.of("Asia/Shanghai")));

        assertThrows(IllegalStateException.class, () -> failingService.importFile(
                new ByteArrayInputStream(workbook(
                        input("存款", "acct_master", "acct_no", "创建人"),
                        input("贷款", "loan_master", "loan_no", "编辑人"))),
                new ReplayIssueOperator("creator", "创建人")));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_registration", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_field", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_audit_event", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_audit_detail", Integer.class));
    }

    @Test
    void generationInProgressDoesNotBlockImport() throws Exception {
        ReplayDbCompareImportResult result = assertDoesNotThrow(
                () -> service().importFile(
                        new ByteArrayInputStream(workbook(
                                input("存款", "acct_master", "acct_no", "创建人"))),
                        new ReplayIssueOperator("creator", "创建人")));

        assertTrue(result.success());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_registration", Integer.class));
    }

    private ReplayDatabaseComparisonImportService service() {
        return new ReplayDatabaseComparisonImportService(
                new ReplayDatabaseComparisonExcelParser(), metadata, dao, versionDao, new SysUserDao(jdbc),
                new ReplayDatabaseComparisonAuditDiff(), jdbc,
                Clock.fixed(Instant.parse("2026-09-12T06:36:08Z"), ZoneId.of("Asia/Shanghai")));
    }

    private ReplayBaseValidatedTable validated(String tableName, Collection<String> fields) {
        List<ReplayBaseColumnOption> columns = fields.stream().map(String::toLowerCase).distinct()
                .map(name -> new ReplayBaseColumnOption(name, name + "备注",
                        name.equals("acct_no") ? 1 : 2, name.equals("acct_no")))
                .toList();
        return new ReplayBaseValidatedTable(
                "base_schema", tableName.toLowerCase(), tableName + "备注", columns);
    }

    private static ImportRow input(String sheet, String table, String field, String reviser) {
        return new ImportRow(sheet, table, field, reviser);
    }

    private static byte[] workbook(ImportRow... rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String sheetName : List.of("存款", "贷款", "公共", "结算")) {
                Sheet sheet = workbook.createSheet(sheetName);
                Row header = sheet.createRow(0);
                header.createCell(2).setCellValue("表英文名");
                header.createCell(4).setCellValue("字段英文名");
                header.createCell(6).setCellValue("负责人");
            }
            int rowIndex = 1;
            for (ImportRow input : rows) {
                Row row = workbook.getSheet(input.sheet()).createRow(rowIndex++);
                row.createCell(2).setCellValue(input.table());
                row.createCell(4).setCellValue(input.field());
                row.createCell(6).setCellValue(input.reviser());
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private record ImportRow(String sheet, String table, String field, String reviser) {
    }
}
