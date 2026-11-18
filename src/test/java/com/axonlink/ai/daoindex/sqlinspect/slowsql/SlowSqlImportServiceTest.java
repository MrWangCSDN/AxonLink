package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlCollectFilterDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlOptimizeDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiWhitelistApplicationDao;
import com.axonlink.ai.daoindex.sqlinspect.service.NsqlIdProjectIndex;
import com.axonlink.ai.daoindex.sqlinspect.service.SlowSqlOptimizeService;
import com.axonlink.ai.daoindex.sqlinspect.service.WhitelistApplicationService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class SlowSqlImportServiceTest {

    private static final String ROUND = "20261101-20261107";
    private static final String EARLIER_ROUND = "20261025-20261031";
    private static final String SQL = "select * from account where id = ?";
    private static final Object[] HEADERS = {
            "ServiceName", "slowSqlStatement", "slowSqlParams", "slowSqlTimeCost", "slowSqlLocation", "sssCount"
    };

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DiiSlowSqlDao dao;
    private SlowSqlImportService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("slow_import_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE dii_slow_sql ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "domain VARCHAR(16), biz_type VARCHAR(16), abstract_sql VARCHAR(1000) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, max_time_cost_ms BIGINT, max_time_cost_raw VARCHAR(32),"
                + "exec_params VARCHAR(1000), source_location VARCHAR(512), exec_count INT NOT NULL,"
                + "round VARCHAR(20) NOT NULL, repeat_rounds VARCHAR(1000), imported_at DATETIME NOT NULL,"
                + "whitelist_app_id BIGINT, whitelist_status VARCHAR(20), is_whitelist TINYINT DEFAULT 0,"
                + "optimize_status VARCHAR(20), optimized_round VARCHAR(20), reappeared_round VARCHAR(20),"
                + "CONSTRAINT uk_round_svc_hash UNIQUE (round, service_name, abstract_hash))");
        jdbc.execute("CREATE TABLE dii_slow_sql_collect_filter (prefix VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE dii_whitelist_application ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, target_type VARCHAR(16), sql_hash CHAR(64),"
                + "project_name VARCHAR(128), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE dii_slow_sql_optimization ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,"
                + "optimized_round VARCHAR(20) NOT NULL, reappeared_round VARCHAR(20),"
                + "optimized_by VARCHAR(100), optimized_by_name VARCHAR(64), optimize_note VARCHAR(200),"
                + "optimized_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,"
                + "CONSTRAINT uk_optimize_svc_hash UNIQUE (service_name, abstract_hash))");
        jdbc.execute("CREATE TABLE dii_slow_sql_optimize_history ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, service_name VARCHAR(128) NOT NULL,"
                + "abstract_hash CHAR(64) NOT NULL, optimized_round VARCHAR(20) NOT NULL,"
                + "optimized_by VARCHAR(100), optimized_by_name VARCHAR(64), optimize_note VARCHAR(200),"
                + "optimized_at DATETIME NOT NULL, reappeared_round VARCHAR(20))");
        dao = new DiiSlowSqlDao(jdbc);
        service = importService(dao);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void addsSuppliedCountsAndKeepsLargestCostSample(String format) throws IOException {
        var result = service.importFile(file(format, HEADERS,
                new Object[]{"ccbs-loan-online", SQL, "first-params", "100ms", "[TX1:Loan.Entity.find]", 12},
                new Object[]{"ccbs-loan-online", SQL, "slow-params", "250ms", "[TX2:Loan.Entity.find]", 8}),
                "test", ROUND);

        Map<String, Object> row = row("ccbs-loan-online", ROUND);
        assertEquals(20, ((Number) row.get("exec_count")).intValue());
        assertEquals(250L, ((Number) row.get("max_time_cost_ms")).longValue());
        assertEquals("250ms", row.get("max_time_cost_raw"));
        assertEquals("slow-params", row.get("exec_params"));
        assertEquals("TX2:Loan.Entity.find", row.get("source_location"));
        assertEquals("贷款", row.get("domain"));
        assertEquals(2, result.get("rawRows"));
        assertEquals(1, result.get("aggregatedRows"));
        assertEquals(true, result.get("preAggregated"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void importsAllEmptyArrayParametersWithSuppliedCounts(String format) throws IOException {
        Object[] headers = {"slowSqlStatement", "serviceName", "slowSqlParameter",
                "slowSqlTimeCost", "slowSqlLocation", "sssCount"};
        var result = service.importFile(file(format, headers,
                new Object[]{SQL, "svcA", "[]", 100, "[source-one]", 12},
                new Object[]{SQL, "svcA", "[]", 250, "[source-two]", 8}), "test", ROUND);

        Map<String, Object> stored = row("svcA", ROUND);
        assertEquals(SQL, stored.get("abstract_sql"));
        assertEquals(20, ((Number) stored.get("exec_count")).intValue());
        assertEquals(250L, ((Number) stored.get("max_time_cost_ms")).longValue());
        assertEquals("source-two", stored.get("source_location"));
        assertNull(stored.get("exec_params"));
        assertEquals(2, result.get("rawRows"));
        assertEquals(1, result.get("aggregatedRows"));
        assertEquals(0, result.get("skipped"));
        assertEquals(true, result.get("preAggregated"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void importsScreenshotLayoutWithBlankSqlHeaderAndCount(String format) throws IOException {
        Object[] headers = {"", "serviceName", "slowSqlParameter",
                "slowSqlTimeCost", "slowSqlLocation", "sssCount"};
        var result = service.importFile(file(format, headers,
                new Object[]{SQL, "svcA", "[]", 100, "[source-one]", 12},
                new Object[]{SQL, "svcA", "[]", 250, "[source-two]", 8}), "test", ROUND);

        Map<String, Object> stored = row("svcA", ROUND);
        assertEquals(SQL, stored.get("abstract_sql"));
        assertEquals("svcA", stored.get("service_name"));
        assertEquals(20, ((Number) stored.get("exec_count")).intValue());
        assertNull(stored.get("exec_params"));
        assertEquals(2, result.get("rawRows"));
        assertEquals(1, result.get("aggregatedRows"));
        assertEquals(true, result.get("preAggregated"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void importsBlankSqlHeaderWithoutCountUsingLegacyRowCounting(String format) throws IOException {
        Object[] headers = {"", "serviceName", "slowSqlParameter", "slowSqlTimeCost", "slowSqlLocation"};
        var result = service.importFile(file(format, headers,
                new Object[]{SQL, "svcA", "[]", 100, "[source-one]"},
                new Object[]{SQL, "svcA", "[]", 250, "[source-two]"}), "test", ROUND);

        Map<String, Object> stored = row("svcA", ROUND);
        assertEquals(SQL, stored.get("abstract_sql"));
        assertEquals(2, ((Number) stored.get("exec_count")).intValue());
        assertNull(stored.get("exec_params"));
        assertEquals(false, result.get("preAggregated"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void normalizesOnlyEmptyParametersAndKeepsLargestCostRowAsSample(String format) throws IOException {
        var result = service.importFile(file(format, HEADERS,
                new Object[]{"svc-real", SQL, " [  ] ", 100, "source", 12},
                new Object[]{"svc-real", SQL, " [\"[]\"] ", 250, "source", 8},
                new Object[]{"svc-empty", SQL, "[42]", 100, "source", 3},
                new Object[]{"svc-empty", SQL, "[]", 250, "source", 4},
                new Object[]{"svc-blank", SQL, " \t ", 100, "source", 5},
                new Object[]{"svc-null", SQL, null, 100, "source", 6},
                new Object[]{"svc-spaces", SQL, "[ \t ]", 100, "source", 8}), "test", ROUND);

        assertEquals("[\"[]\"]", row("svc-real", ROUND).get("exec_params"));
        assertEquals(20, ((Number) row("svc-real", ROUND).get("exec_count")).intValue());
        assertNull(row("svc-empty", ROUND).get("exec_params"));
        assertEquals(7, ((Number) row("svc-empty", ROUND).get("exec_count")).intValue());
        assertEquals(250L, ((Number) row("svc-empty", ROUND).get("max_time_cost_ms")).longValue());
        assertNull(row("svc-blank", ROUND).get("exec_params"));
        assertNull(row("svc-null", ROUND).get("exec_params"));
        assertNull(row("svc-spaces", ROUND).get("exec_params"));
        assertEquals(7, result.get("rawRows"));
        assertEquals(5, result.get("aggregatedRows"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void preservesPerInputRowCountingWithoutCountColumn(String format) throws IOException {
        Object[] headers = {"slowSqlStatement", "ServiceName", "params", "cost", "location"};
        var result = service.importFile(file(format, headers,
                new Object[]{SQL, "svcA", "first", "100ms", "[source-one]"},
                new Object[]{SQL, "svcA", "second", "200ms", "[source-two]"}), "test", ROUND);

        Map<String, Object> row = row("svcA", ROUND);
        assertEquals(2, ((Number) row.get("exec_count")).intValue());
        assertEquals(200L, ((Number) row.get("max_time_cost_ms")).longValue());
        assertEquals("second", row.get("exec_params"));
        assertEquals("source-two", row.get("source_location"));
        assertEquals(2, result.get("rawRows"));
        assertEquals(1, result.get("aggregatedRows"));
        assertEquals(false, result.get("preAggregated"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void readsShuffledHeadersWithCountInSecondColumn(String format) throws IOException {
        Object[] headers = {
                "slowSqlLocation", "sssCount", "slowSqlStatement", "slowSqlParams", "ServiceName", "slowSqlTimeCost"
        };
        service.importFile(file(format, headers,
                new Object[]{"[TX:Loan.Entity.find]", 12, SQL, "first", "ccbs-loan-online", "100ms"},
                new Object[]{"[TX:Loan.Entity.update]", 8, SQL, "second", "ccbs-loan-online", "200ms"}),
                "test", ROUND);

        Map<String, Object> row = row("ccbs-loan-online", ROUND);
        assertEquals(SQL, row.get("abstract_sql"));
        assertEquals(20, ((Number) row.get("exec_count")).intValue());
        assertEquals(200L, ((Number) row.get("max_time_cost_ms")).longValue());
        assertEquals("second", row.get("exec_params"));
        assertEquals("TX:Loan.Entity.update", row.get("source_location"));
    }

    @ParameterizedTest
    @MethodSource("invalidCounts")
    void rejectsInvalidCountWithRowNumberBeforeReplacingExistingRound(String format, String invalidCount)
            throws IOException {
        insertExisting("svcA", ROUND);
        List<Map<String, Object>> before = roundRows(ROUND);
        MockMultipartFile input = file(format, HEADERS,
                new Object[]{"svcA", SQL, "[]", "100ms", "source", 12},
                new Object[]{"svcA", SQL, "[]", "200ms", "source", invalidCount});

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.importFile(input, "test", ROUND));

        assertTrue(exception.getMessage().replace(" ", "").contains("第3行"), exception.getMessage());
        assertEquals(before, roundRows(ROUND));
    }

    static Stream<Arguments> invalidCounts() {
        return Stream.of("csv", "xlsx").flatMap(format -> Stream.of("", "-1", "1.5", "2147483648", "invalid")
                .map(value -> Arguments.of(format, value)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void acceptsZeroAndLargestSupportedExcelNumericCount(int count) throws IOException {
        service.importFile(file("xlsx", HEADERS,
                new Object[]{"svcA", SQL, "params", "100ms", "source", count}), "test", ROUND);

        assertEquals(count, ((Number) row("svcA", ROUND).get("exec_count")).intValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "xlsx"})
    void rejectsSummedCountOverflowBeforeReplacingExistingRound(String format) throws IOException {
        insertExisting("svcA", ROUND);
        List<Map<String, Object>> before = roundRows(ROUND);
        MockMultipartFile input = file(format, HEADERS,
                new Object[]{"svcA", SQL, "first", "100ms", "source", Integer.MAX_VALUE},
                new Object[]{"svcA", SQL, "second", "200ms", "source", 1});

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.importFile(input, "test", ROUND));

        assertTrue(exception.getMessage().replace(" ", "").contains("第3行"), exception.getMessage());
        assertEquals(before, roundRows(ROUND));
    }

    @Test
    void keepsPhysicalRowStatisticsForSkippedAndFilteredWeightedRows() throws IOException {
        jdbc.update("INSERT INTO dii_slow_sql_collect_filter (prefix) VALUES (?)", "EXPLAIN");
        var result = service.importFile(file("csv", HEADERS,
                new Object[]{"svcA", "", "empty", "100ms", "source", 100},
                new Object[]{"svcA", "EXPLAIN select 1", "filtered", "100ms", "source", 100},
                new Object[]{"svcA", SQL, "first", "100ms", "source", 12},
                new Object[]{"svcA", SQL, "second", "200ms", "source", 8}), "test", ROUND);

        assertEquals(2, result.get("rawRows"));
        assertEquals(1, result.get("skipped"));
        assertEquals(1, result.get("filtered"));
        assertEquals(1, result.get("aggregatedRows"));
        assertEquals(20, ((Number) row("svcA", ROUND).get("exec_count")).intValue());
    }

    @Test
    void preservesServiceAndSqlIdentityForRepeatWhitelistAndOptimizationInheritance() throws IOException {
        insertExisting("svcA", EARLIER_ROUND);
        insertExisting("svcB", EARLIER_ROUND);
        jdbc.update("INSERT INTO dii_whitelist_application (target_type, sql_hash, project_name, status) "
                + "VALUES ('SLOW_SQL', ?, 'svcA', 'APPROVED')", sqlHash());
        DiiSlowSqlOptimizeDao optimizeDao = new DiiSlowSqlOptimizeDao(jdbc);
        LocalDateTime now = LocalDateTime.now();
        optimizeDao.upsertOptimized("svcB", sqlHash(), EARLIER_ROUND, "user", "用户", "add index", now);
        optimizeDao.appendHistory("svcB", sqlHash(), EARLIER_ROUND, "user", "用户", "add index", now);
        optimizeDao.upsertOptimized("outside", sqlHash(), EARLIER_ROUND, "user", "用户", "add index", now);

        var result = service.importFile(file("csv", HEADERS,
                new Object[]{"svcA", SQL, "a1", "100ms", "source", 12},
                new Object[]{"svcA", SQL, "a2", "200ms", "source", 8},
                new Object[]{"svcB", SQL, "b", "100ms", "source", 6},
                new Object[]{"svcC", SQL, "c", "100ms", "source", 7}), "test", ROUND);

        Map<String, Object> a = row("svcA", ROUND);
        Map<String, Object> b = row("svcB", ROUND);
        Map<String, Object> c = row("svcC", ROUND);
        assertEquals(3, result.get("aggregatedRows"));
        assertEquals(2, result.get("repeatHit"));
        assertEquals(1, result.get("reappearedHit"));
        assertEquals(EARLIER_ROUND, a.get("repeat_rounds"));
        assertEquals(EARLIER_ROUND, b.get("repeat_rounds"));
        assertNull(c.get("repeat_rounds"));
        assertEquals(20, ((Number) a.get("exec_count")).intValue());
        assertEquals("APPROVED", a.get("whitelist_status"));
        assertEquals(1, ((Number) a.get("is_whitelist")).intValue());
        assertNull(b.get("whitelist_status"));
        assertNull(a.get("optimize_status"));
        assertEquals("REGRESSED", b.get("optimize_status"));
        assertEquals(ROUND, b.get("reappeared_round"));
        assertNull(c.get("whitelist_status"));
        assertNull(c.get("optimize_status"));
        assertEquals("OPTIMIZED", optimizeDao.findByKey("outside", sqlHash()).get("status"));
    }

    @Test
    void rollsBackExistingRoundWhenLaterInsertChunkFails() throws IOException {
        insertExisting("svcA", ROUND);
        List<Map<String, Object>> before = roundRows(ROUND);
        DiiSlowSqlDao failingDao = spy(dao);
        AtomicInteger chunks = new AtomicInteger();
        doAnswer(invocation -> {
            if (chunks.incrementAndGet() == 2) {
                throw new DataAccessResourceFailureException("second chunk failed");
            }
            return invocation.callRealMethod();
        }).when(failingDao).batchInsert(anyList(), anyString(), any(LocalDateTime.class));
        SlowSqlImportService failingService = importService(failingDao);
        Object[][] rows = new Object[2002][];
        rows[0] = HEADERS;
        for (int i = 1; i < rows.length; i++) {
            rows[i] = new Object[]{"svcA", "select " + i, "params", "100ms", "source", 12};
        }
        MockMultipartFile input = file("csv", rows);

        assertThrows(DataAccessResourceFailureException.class,
                () -> failingService.importFile(input, "test", ROUND));

        assertEquals(2, chunks.get());
        assertEquals(before, roundRows(ROUND));
    }

    private SlowSqlImportService importService(DiiSlowSqlDao slowSqlDao) {
        DiiWhitelistApplicationDao whitelistDao = new DiiWhitelistApplicationDao(jdbc);
        WhitelistApplicationService whitelist = new WhitelistApplicationService(
                whitelistDao, null, null, slowSqlDao, new DaoIndexAnalysisProperties());
        SlowSqlOptimizeService optimization = new SlowSqlOptimizeService(
                new DiiSlowSqlOptimizeDao(jdbc), slowSqlDao, whitelistDao);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("whitelist", whitelist);
        beans.addBean("optimization", optimization);
        return new SlowSqlImportService(slowSqlDao, new DiiSlowSqlCollectFilterDao(jdbc),
                new NsqlIdProjectIndex(null), location -> "loan-bcc",
                beans.getBeanProvider(WhitelistApplicationService.class),
                beans.getBeanProvider(SlowSqlOptimizeService.class));
    }

    private void insertExisting(String serviceName, String round) {
        jdbc.update("INSERT INTO dii_slow_sql (service_name, abstract_sql, abstract_hash, max_time_cost_ms, "
                        + "max_time_cost_raw, exec_params, source_location, exec_count, round, imported_at) "
                        + "VALUES (?, ?, ?, 900, '900ms', 'old-params', 'old-source', 9, ?, ?)",
                serviceName, SQL, sqlHash(), round, Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 10, 0)));
    }

    private Map<String, Object> row(String serviceName, String round) {
        return jdbc.queryForMap("SELECT * FROM dii_slow_sql WHERE service_name = ? AND round = ?",
                serviceName, round);
    }

    private List<Map<String, Object>> roundRows(String round) {
        return jdbc.queryForList("SELECT * FROM dii_slow_sql WHERE round = ? ORDER BY id", round);
    }

    private static String sqlHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(SQL.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static MockMultipartFile file(String format, Object[]... rows) throws IOException {
        if ("csv".equals(format)) {
            StringWriter contents = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(contents, CSVFormat.DEFAULT)) {
                for (Object[] row : rows) {
                    printer.printRecord(Arrays.asList(row));
                }
            }
            return new MockMultipartFile("file", "slow-sql.csv", "text/csv",
                    contents.toString().getBytes(StandardCharsets.UTF_8));
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("slow-sql");
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                Row row = sheet.createRow(rowIndex);
                Object[] values = rows[rowIndex];
                for (int column = 0; column < values.length; column++) {
                    var cell = row.createCell(column);
                    Object value = values[column];
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value != null) {
                        cell.setCellValue(value.toString());
                    }
                }
            }
            workbook.write(output);
            return new MockMultipartFile("file", "slow-sql.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
