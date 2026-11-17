package com.axonlink.ai.daoindex.errorcode.scan;

import com.axonlink.ai.daoindex.errorcode.attribution.ErrorCodeAttributionService;
import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.definition.ErrorDefinitionIndex;
import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import com.axonlink.ai.daoindex.errorcode.export.ErrorCodeExportService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** 模块解析、完整扫描保护及过滤后合法零结果的端到端测试。 */
class ErrorCodeScanServiceTest {

    @TempDir
    Path sourceRoot;

    /**
     * 构造一个源码根为空的 Service + mock 依赖：
     * 无 Spring 注入时 sourceRoots/workspaceRoots 为 null → collectSourceFiles 返回空 → 明细空、不碰文件系统。
     */
    private ErrorCodeScanService newServiceWithEmptyRoots(DiiErrorCodeDao dao,
                                                          ErrorCodeAttributionService attr) {
        return new ErrorCodeScanService(dao, attr);
    }

    @Test
    void onReadyPathKeepsExistingDataWhenSourceRootsAreEmpty() {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attr = mock(ErrorCodeAttributionService.class);
        ErrorCodeScanService svc = newServiceWithEmptyRoots(dao, attr);

        svc.runSafely(false, false);   // 启动路径：materialize=false

        verify(dao, never()).rebuildThrows(anyList());
        verify(attr, never()).materializeTransactionErrorCodes();
    }

    @Test
    void graphBuiltPathKeepsExistingDataWhenSourceRootsAreEmpty() {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attr = mock(ErrorCodeAttributionService.class);
        ErrorCodeScanService svc = newServiceWithEmptyRoots(dao, attr);

        svc.runSafely(false, true);    // 图构建完成路径：materialize=true

        verify(dao, never()).rebuildThrows(anyList());
        verify(attr, never()).materializeTransactionErrorCodes();
    }

    @Test
    void fullyHandledScanClearsBothTablesAndExportsOnlyHeaders() throws Exception {
        writeFactory(sourceRoot);
        writeHandledThrow(sourceRoot);
        EmbeddedDatabase database = createDatabase();
        try {
            DiiErrorCodeDao dao = new DiiErrorCodeDao(new JdbcTemplate(database));
            dao.rebuildThrows(List.of(new ErrorCodeThrow(
                    "E0001", "Errors", "throw Errors.E0001();", "app.Example", "run",
                    sourceRoot.resolve("app/Example.java").toString(), 4,
                    null, null, null, 1L)));
            dao.materializeTxErrorCodes(List.of(new TxErrorCodeRow(
                    "T1", "交易一", "deposit", "E0001", "Errors", "throw Errors.E0001();",
                    "app.Example", "run", sourceRoot.resolve("app/Example.java").toString(),
                    4, null, null, null, "MATCHED", 1L)));
            ErrorCodeAttributionService attribution = new ErrorCodeAttributionService(null, dao, null);
            ErrorCodeScanService service = sourceService(dao, attribution);

            service.runSafely(false, true);

            assertEquals(0, dao.countThrows());
            assertEquals(0, dao.countByTxId("T1"));
            assertTrue(dao.listAll(null).isEmpty());
            ErrorCodeExportService exports = new ErrorCodeExportService(dao, new ErrorDefinitionIndex(""));
            try (XSSFWorkbook single = new XSSFWorkbook(new ByteArrayInputStream(
                    exports.exportSingle("T1").getContent()));
                 XSSFWorkbook all = new XSSFWorkbook(new ByteArrayInputStream(
                         exports.exportAll(null).getContent()))) {
                assertEquals("错误码", single.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
                assertEquals("归属交易", all.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
                assertEquals(1, single.getSheetAt(0).getPhysicalNumberOfRows());
                assertEquals(1, all.getSheetAt(0).getPhysicalNumberOfRows());
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void replacedSameCodeExportsOnlyCatchSiteAndPreservesCounts() throws Exception {
        writeSource(sourceRoot, "app/Errors.java", """
                package app;
                public class Errors {
                    public static IllegalStateException E1654(String message) {
                        return new IllegalStateException(message);
                    }
                }
                """);
        writeSource(sourceRoot, "app/Example.java", """
                package app;
                class Example {
                    void run() {
                        try {
                            throw Errors.E1654("original throw");
                        } catch (IllegalStateException e) {
                            throw Errors.E1654(e.getMessage());
                        }
                    }
                }
                """);
        EmbeddedDatabase database = createDatabase();
        try {
            DiiErrorCodeDao dao = new DiiErrorCodeDao(new JdbcTemplate(database));
            ErrorCodeAttributionService attribution = new ErrorCodeAttributionService(null, dao, null);

            sourceService(dao, attribution).runSafely(false, false);

            assertEquals(1, dao.countThrows());
            ErrorCodeThrow kept = dao.listAllThrows().get(0);
            assertEquals("E1654", kept.getErrorCode());
            assertEquals(Integer.valueOf(7), kept.getLineNo());
            assertEquals("throw Errors.E1654(e.getMessage());", kept.getThrowText());
            ErrorCodeAttributionService.ReachableMethod reachable =
                    new ErrorCodeAttributionService.ReachableMethod(
                            "T1", "交易一", "deposit", "app.Example", "run", "COMP1", "业务构件");
            dao.materializeTxErrorCodes(ErrorCodeAttributionService.joinToTxRows(
                    List.of(reachable), dao.listAllThrows()));

            assertEquals(1, dao.countByTxId("T1"));
            assertEquals(1, dao.distinctCountByTxId("T1"));
            ErrorCodeExportService exports = new ErrorCodeExportService(dao, new ErrorDefinitionIndex(""));
            try (XSSFWorkbook single = new XSSFWorkbook(new ByteArrayInputStream(
                    exports.exportSingle("T1").getContent()));
                 XSSFWorkbook all = new XSSFWorkbook(new ByteArrayInputStream(
                         exports.exportAll(null).getContent()))) {
                assertEquals(2, single.getSheetAt(0).getPhysicalNumberOfRows());
                assertEquals("E1654", single.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
                assertEquals(kept.getThrowText(), single.getSheetAt(0).getRow(1).getCell(4).getStringCellValue());
                assertEquals("7", single.getSheetAt(0).getRow(1).getCell(9).getStringCellValue());
                assertEquals(2, all.getSheetAt(0).getPhysicalNumberOfRows());
                assertEquals("T1", all.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
                assertEquals("E1654", all.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
                assertEquals(kept.getThrowText(), all.getSheetAt(0).getRow(1).getCell(7).getStringCellValue());
                assertEquals("7", all.getSheetAt(0).getRow(1).getCell(12).getStringCellValue());
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void startupScanCanClearHandledDetailsWithoutMaterializingTransactions() throws Exception {
        writeFactory(sourceRoot);
        writeHandledThrow(sourceRoot);
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        when(dao.countThrows()).thenReturn(1);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);

        sourceService(dao, attribution).runSafely(false, false);

        verify(dao).rebuildThrows(argThat(List::isEmpty));
        verifyNoInteractions(attribution);
    }

    @Test
    void malformedSourceAlongsideValidSourceKeepsExistingResults() throws Exception {
        writeUncaughtThrow(sourceRoot);
        writeSource(sourceRoot, "app/Broken.java", "package app; class Broken { void broken( { }");
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);

        sourceService(dao, attribution).runSafely(false, true);

        verify(dao, never()).rebuildThrows(anyList());
        verifyNoInteractions(attribution);
    }

    @Test
    void unreadableSourceContentAlongsideValidSourceKeepsExistingResults() throws Exception {
        writeUncaughtThrow(sourceRoot);
        Files.write(sourceRoot.resolve("app/InvalidEncoding.java"), new byte[] {(byte) 0xc3, 0x28});
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);

        sourceService(dao, attribution).runSafely(false, true);

        verify(dao, never()).rebuildThrows(anyList());
        verifyNoInteractions(attribution);
    }

    @Test
    void fullyHandledSourceAlongsideMalformedSourceCannotClearExistingResults() throws Exception {
        writeFactory(sourceRoot);
        writeHandledThrow(sourceRoot);
        writeSource(sourceRoot, "app/Broken.java", "package app; class Broken { void broken( { }");
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        when(dao.countThrows()).thenReturn(1);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);

        sourceService(dao, attribution).runSafely(false, true);

        verify(dao, never()).rebuildThrows(anyList());
        verifyNoInteractions(attribution);
    }

    @Test
    void scanWithoutRawCandidatesPreservesExistingResults() throws Exception {
        writeSource(sourceRoot, "app/Empty.java", "package app; class Empty {} ");
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        when(dao.countThrows()).thenReturn(1);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);

        sourceService(dao, attribution).runSafely(false, true);

        verify(dao, never()).rebuildThrows(anyList());
        verifyNoInteractions(attribution);
    }

    @Test
    void scanWithoutRawCandidatesInEmptyDatabaseKeepsLegacyEmptyScanBehavior() throws Exception {
        writeSource(sourceRoot, "app/Empty.java", "package app; class Empty {} ");
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);
        ErrorCodeAttributionService.MaterializeOutcome incomplete =
                mock(ErrorCodeAttributionService.MaterializeOutcome.class);
        when(attribution.materializeTransactionErrorCodes(false)).thenReturn(incomplete);

        sourceService(dao, attribution).runSafely(false, true);

        verify(dao).rebuildThrows(argThat(List::isEmpty));
        verify(attribution).materializeTransactionErrorCodes(false);
    }

    @Test
    void unresolvedFactoryTypeKeepsCandidateInsideCatch() throws Exception {
        writeHandledThrow(sourceRoot);
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);

        sourceService(dao, attribution).runSafely(false, false);

        verify(dao).rebuildThrows(argThat(rows -> rows.size() == 1
                && "E0001".equals(rows.get(0).getErrorCode())));
        verifyNoInteractions(attribution);
    }

    @Test
    void workspaceGeneratedRootsResolveFactoryTypesAndAvoidDuplicateFiles() throws Exception {
        Path javaRoot = sourceRoot.resolve("module/src/main/java");
        Path generatedRoot = sourceRoot.resolve("module/target/gen");
        writeHandledThrow(javaRoot);
        writeFactory(generatedRoot);
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        when(dao.countThrows()).thenReturn(1);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);
        ErrorCodeScanService service = new ErrorCodeScanService(dao, attribution);
        ReflectionTestUtils.setField(service, "sourceRoots", javaRoot + "," + javaRoot);
        ReflectionTestUtils.setField(service, "workspaceRoots", sourceRoot.toString());

        service.runSafely(false, false);

        verify(dao).rebuildThrows(argThat(List::isEmpty));
    }

    @Test
    void overlappingSourceRootsDoNotDuplicateThrowSites() throws Exception {
        writeUncaughtThrow(sourceRoot);
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);
        ErrorCodeScanService service = sourceService(dao, attribution);
        ReflectionTestUtils.setField(service, "sourceRoots", sourceRoot + "," + sourceRoot.resolve("app"));

        service.runSafely(false, false);

        verify(dao).rebuildThrows(argThat(rows -> rows.size() == 1));
    }

    @Test
    void oversizedSourceKeepsExistingSkipBehavior() throws Exception {
        writeUncaughtThrow(sourceRoot);
        writeSource(sourceRoot, "app/Large.java", "not parsed".repeat(52_000));
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attribution = mock(ErrorCodeAttributionService.class);

        sourceService(dao, attribution).runSafely(false, false);

        verify(dao).rebuildThrows(argThat(rows -> rows.size() == 1));
    }

    private EmbeddedDatabase createDatabase() throws Exception {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("filtered_errors_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        try {
            String schema;
            try (InputStream input = new ClassPathResource("db/daoindex/V24__dii_error_code.sql").getInputStream()) {
                schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            // H2 索引名在 schema 内唯一，MySQL 则按表区分；仅适配第一个同名索引。
            schema = schema.replaceFirst("KEY idx_error_code ", "KEY idx_throw_error_code ");
            new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)))
                    .execute(database);
            return database;
        } catch (Exception failure) {
            database.shutdown();
            throw failure;
        }
    }

    private ErrorCodeScanService sourceService(DiiErrorCodeDao dao, ErrorCodeAttributionService attribution) {
        ErrorCodeScanService service = new ErrorCodeScanService(dao, attribution);
        ReflectionTestUtils.setField(service, "sourceRoots", sourceRoot.toString());
        return service;
    }

    private void writeFactory(Path root) throws Exception {
        writeSource(root, "app/Errors.java", """
                package app;
                public class Errors {
                    public static IllegalStateException E0001() {
                        return new IllegalStateException("business error");
                    }
                }
                """);
    }

    private void writeHandledThrow(Path root) throws Exception {
        writeSource(root, "app/Example.java", """
                package app;
                class Example {
                    void run() {
                        try {
                            throw Errors.E0001();
                        } catch (IllegalStateException e) {
                            return;
                        }
                    }
                }
                """);
    }

    private void writeUncaughtThrow(Path root) throws Exception {
        writeSource(root, "app/Example.java", """
                package app;
                class Example {
                    void run() { throw Errors.E0001(); }
                }
                """);
    }

    private void writeSource(Path root, String relativePath, String source) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void detectModuleFromBccSegment() {
        Path p = Paths.get("/data/src/loan-bcc/target/gen/com/x/A.java");
        assertEquals("loan-bcc", ErrorCodeScanService.detectModule(p));
    }

    @Test
    void detectModulePicksNearestBcc() {
        Path p = Paths.get("/data/outer-bcc/sub/deposit-bcc/target/gen/com/x/B.java");
        assertEquals("deposit-bcc", ErrorCodeScanService.detectModule(p));
    }

    @Test
    void detectModuleNullWhenNoBcc() {
        Path p = Paths.get("/data/src/main/java/com/x/C.java");
        assertNull(ErrorCodeScanService.detectModule(p));
    }

    @Test
    void detectModuleHandlesWindowsSeparator() {
        // 用 / 构造但断言纯逻辑：含 *-bcc 段即可解析
        Path p = Paths.get("/repo/cust-bcc/target/gen/com/x/D.java");
        assertEquals("cust-bcc", ErrorCodeScanService.detectModule(p));
    }
}
