package com.axonlink.service.impl;

import com.axonlink.dto.FlowtranDomain;
import com.axonlink.dto.FlowtranTransaction;
import com.axonlink.service.FlowtranChainExportService;
import com.axonlink.service.FlowtranService;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowtranChainExportServiceImplTest {

    private StubFlowtranService flowtranService;
    private FlowtranChainExportService exportService;

    @BeforeEach
    void setUp() {
        flowtranService = new StubFlowtranService();
        exportService = new FlowtranChainExportServiceImpl(flowtranService);
    }

    @Test
    void exportsFiveSheetsSortedByTransactionAndNodeCode() throws Exception {
        flowtranService.setTransactions(List.of(
                transaction("TX002", "交易二"),
                transaction("TX001", "交易一")));
        flowtranService.setChain("TX001", chain("TX001", "交易一",
                List.of(node("SVC-B", "服务B", "pbs"), node("SVC-A", "服务A", "pcs"),
                        node("SVC-A", "服务A", "pcs")),
                List.of(node("CMP-B", "构件B", "pbcb"), node("CMP-A", "构件A", "pbcc")),
                List.of(table("TAB-B", "表B"), table("TAB-A", "表A"))));
        flowtranService.setChain("TX002", chain("TX002", "交易二",
                List.of(node("SVC-A", "服务A", "pcs")),
                List.of(),
                List.of(table("TAB-C", "表C"))));

        FlowtranChainExportService.ExportFile file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            assertEquals(List.of("交易清单", "服务清单", "构件清单", "数据库表清单", "DAO方法清单"),
                    IntStream.range(0, workbook.getNumberOfSheets())
                            .mapToObj(i -> workbook.getSheetAt(i).getSheetName()).toList());

            Sheet transactions = workbook.getSheet("交易清单");
            assertEquals("TX001", text(transactions, 1, 2));
            assertEquals("TX002", text(transactions, 2, 2));

            Sheet services = workbook.getSheet("服务清单");
            assertEquals(4, services.getPhysicalNumberOfRows());
            assertEquals("TX001", text(services, 1, 0));
            assertEquals("SVC-A", text(services, 1, 2));
            assertEquals("SVC-B", text(services, 2, 2));
            assertEquals("TX002", text(services, 3, 0));
            assertEquals("SVC-A", text(services, 3, 2));

            Sheet components = workbook.getSheet("构件清单");
            assertEquals("CMP-A", text(components, 1, 2));
            assertEquals("CMP-B", text(components, 2, 2));

            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals("TAB-A", text(tables, 1, 2));
            assertEquals("TAB-B", text(tables, 2, 2));
            assertEquals("TAB-C", text(tables, 3, 2));
        }
    }

    @Test
    void keepsFailedTransactionAndContinuesExportingLaterTransactions() throws Exception {
        flowtranService.setTransactions(List.of(
                transaction("TX001", "交易一"),
                transaction("TX002", "交易二")));
        flowtranService.setChain("TX001", null);
        flowtranService.setChain("TX002", chain("TX002", "交易二",
                List.of(node("SVC-B", "服务B", "pbs")), List.of(), List.of()));

        FlowtranChainExportService.ExportFile file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet transactions = workbook.getSheet("交易清单");
            assertEquals("TX001", text(transactions, 1, 2));
            assertEquals("公共领域", text(transactions, 1, 1));
            assertEquals("失败", text(transactions, 1, 9));
            assertFalse(text(transactions, 1, 10).isBlank());
            assertEquals("TX002", text(workbook.getSheet("服务清单"), 1, 0));
        }
    }

    @Test
    void rejectsDomainWithoutTransactions() {
        flowtranService.setTransactions(List.of());

        NoSuchElementException error = assertThrows(NoSuchElementException.class,
                () -> exportService.exportDomain("missing"));

        assertEquals("未找到可导出的领域交易：missing", error.getMessage());
    }

    @Test
    void readsEveryTransactionPage() throws Exception {
        List<FlowtranTransaction> transactions = new ArrayList<>();
        for (int i = 101; i >= 1; i--) {
            String txId = "TX" + String.format("%03d", i);
            transactions.add(transaction(txId, "交易" + i));
            flowtranService.setChain(txId, chain(txId, "交易" + i,
                    List.of(), List.of(), List.of()));
        }
        flowtranService.setTransactions(transactions);

        FlowtranChainExportService.ExportFile file = exportService.exportDomain("public");

        assertEquals(List.of(1, 2), flowtranService.requestedPages());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            assertEquals(102, workbook.getSheet("交易清单").getPhysicalNumberOfRows());
            assertEquals("TX001", text(workbook.getSheet("交易清单"), 1, 2));
            assertEquals("TX101", text(workbook.getSheet("交易清单"), 101, 2));
        }
    }

    @Test
    void exportsEachCalledDaoMethodInItsOwnRowWithMatchingTableMetadata() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一")));
        Map<String, Object> specialDao = new LinkedHashMap<>(dao("TAB-A", "readA"));
        specialDao.put("code", "com.example.SpecialDao#readA");
        specialDao.put("daoClassName", "com.example.SpecialDao");
        specialDao.put("domainKey", "other");
        specialDao.put("domain", "其他领域");
        Map<String, Object> writeDao = new LinkedHashMap<>(dao("TAB-A", "writeA"));
        writeDao.put("name", "displayNameOnly");
        flowtranService.setChain("TX001", chain("TX001", "交易一", List.of(), List.of(),
                List.of(table("TAB-A", "表A"), table("TAB-B", "表B"), table("TAB-A", "表A")),
                List.of(writeDao, dao("TAB-A", " readA "), dao("TAB-A", "readA"),
                        dao("TAB-B", "readB"), specialDao,
                        Map.of("tableCode", "TAB-A", "methodName", " ", "name", "fallbackA", "daoClassName", "com.example.PublicDao"),
                        Map.of("tableCode", "TAB-A", "methodName", " ", "name", " "))));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet sheet = workbook.getSheet("DAO方法清单");
            assertNotNull(sheet);
            assertEquals(List.of("交易码", "交易名称", "表英文名", "表中文名", "表所属领域编码", "表所属领域名称", "来源工程", "DAO类名", "DAO方法名"),
                    IntStream.range(0, sheet.getRow(0).getLastCellNum()).mapToObj(index -> text(sheet, 0, index)).toList());
            assertEquals(6, sheet.getPhysicalNumberOfRows());
            assertEquals("TX001", text(sheet, 1, 0));
            assertEquals("TAB-A", text(sheet, 1, 2));
            assertEquals("表A", text(sheet, 1, 3));
            assertEquals("public", text(sheet, 1, 4));
            assertEquals("公共领域", text(sheet, 1, 5));
            assertEquals("public-online", text(sheet, 1, 6));
            assertEquals("com.example.PublicDao", text(sheet, 1, 7));
            assertEquals(List.of("fallbackA", "readA", "writeA", "readA", "readB"),
                    IntStream.rangeClosed(1, 5).mapToObj(index -> text(sheet, index, 8)).toList());
            assertEquals("com.example.SpecialDao", text(sheet, 4, 7));
            assertEquals("public", text(sheet, 4, 4));
            assertEquals("公共领域", text(sheet, 4, 5));
            assertEquals("TAB-B", text(sheet, 5, 2));
            assertEquals("A1:I6", workbook.getSheet("DAO方法清单").getCTWorksheet().getAutoFilter().getRef());
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals(9, tables.getRow(0).getLastCellNum());
            assertEquals(3, tables.getPhysicalNumberOfRows());
            assertEquals("com.example.PublicDao.fallbackA\ncom.example.PublicDao.readA\n"
                    + "com.example.PublicDao.writeA\ncom.example.SpecialDao.readA", text(tables, 1, 8));
            assertEquals("com.example.PublicDao.readB", text(tables, 2, 8));
        }
    }

    @Test
    void showsScreenshotDaoMethodsNextToClassOnTheTableSheet() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易日志查询示例")));
        Map<String, Object> table = new LinkedHashMap<>(table("kapb_txn_log", "交易日志表"));
        table.put("daoClassName", "Kapb_txn_logDao");
        Map<String, Object> first = Map.of("code", "Kapb_txn_logDao#selectAll_odb3",
                "tableCode", "kapb_txn_log", "daoClassName", "Kapb_txn_logDao", "methodName", "selectAll_odb3");
        Map<String, Object> second = Map.of("code", "Kapb_txn_logDao#selectAll_odb2",
                "tableCode", "kapb_txn_log", "daoClassName", "Kapb_txn_logDao", "methodName", "selectAll_odb2");
        flowtranService.setChain("TX001", chain("TX001", "交易日志查询示例", List.of(), List.of(),
                List.of(table), List.of(first, second, first)));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals(List.of("交易码", "交易名称", "表英文名", "表中文名", "表所属领域编码",
                            "表所属领域名称", "来源工程", "DAO类名", "DAO方法名"),
                    IntStream.range(0, tables.getRow(0).getLastCellNum())
                            .mapToObj(index -> text(tables, 0, index)).toList());
            assertEquals(2, tables.getPhysicalNumberOfRows());
            assertEquals("Kapb_txn_logDao", text(tables, 1, 7));
            assertEquals("Kapb_txn_logDao.selectAll_odb2\nKapb_txn_logDao.selectAll_odb3", text(tables, 1, 8));
            assertTrue(tables.getRow(1).getCell(8).getCellStyle().getWrapText());
            assertTrue(tables.getRow(1).getHeightInPoints() > tables.getDefaultRowHeightInPoints());
            assertEquals("A1:I2", workbook.getSheet("数据库表清单").getCTWorksheet().getAutoFilter().getRef());
            Sheet methods = workbook.getSheet("DAO方法清单");
            assertEquals(3, methods.getPhysicalNumberOfRows());
            assertEquals("selectAll_odb2", text(methods, 1, 8));
            assertEquals("selectAll_odb3", text(methods, 2, 8));
        }
    }

    @Test
    void limitsTableSummaryToCompleteLinesWhileKeepingAllMethodDetails() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一")));
        List<Map<String, Object>> methods = IntStream.range(0, 200)
                .mapToObj(index -> dao("TAB-A", "read" + String.format("%03d", index) + "_" + "x".repeat(200)))
                .toList();
        flowtranService.setChain("TX001", chain("TX001", "交易一", List.of(), List.of(),
                List.of(table("TAB-A", "表A")), methods));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals(9, tables.getRow(0).getLastCellNum());
            String summary = text(tables, 1, 8);
            assertTrue(summary.length() <= SpreadsheetVersion.EXCEL2007.getMaxTextLength());
            assertTrue(summary.endsWith("\n…（完整方法见DAO方法清单）"));
            List<String> calledMethods = methods.stream()
                    .map(method -> method.get("daoClassName") + "." + method.get("methodName")).toList();
            String[] lines = summary.split("\n");
            assertTrue(lines.length > 1);
            List<String> visibleMethods = Arrays.asList(lines).subList(0, lines.length - 1);
            assertEquals(calledMethods.subList(0, visibleMethods.size()), visibleMethods);
            Sheet details = workbook.getSheet("DAO方法清单");
            assertEquals(201, details.getPhysicalNumberOfRows());
            assertEquals(methods.get(199).get("methodName"), text(details, 200, 8));
        }
    }

    @Test
    void expandsTableRowForWrappedLongMethodNames() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一")));
        String methodName = "read" + "W".repeat(100);
        flowtranService.setChain("TX001", chain("TX001", "交易一", List.of(), List.of(),
                List.of(table("TAB-A", "表A")), List.of(dao("TAB-A", methodName))));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals("com.example.PublicDao." + methodName, text(tables, 1, 8));
            assertTrue(tables.getRow(1).getHeightInPoints() >= 2 * tables.getDefaultRowHeightInPoints());
            assertEquals(methodName, text(workbook.getSheet("DAO方法清单"), 1, 8));
        }
    }

    @Test
    void keepsSharedDaoMethodsAndTableMatchesSeparateAcrossTransactions() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一"), transaction("TX002", "交易二")));
        flowtranService.setChain("TX001", chain("TX001", "交易一", List.of(), List.of(), List.of(table("TAB-A", "表A")),
                List.of(dao("TAB-A", "sharedRead"), dao("TAB-A", "readA"))));
        flowtranService.setChain("TX002", chain("TX002", "交易二", List.of(), List.of(), List.of(table("TAB-A", "表A")),
                List.of(dao("TAB-A", "sharedRead"), dao("TAB-A", "writeA"))));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet sheet = workbook.getSheet("DAO方法清单");
            assertNotNull(sheet);
            assertEquals(5, sheet.getPhysicalNumberOfRows());
            assertEquals(List.of("TX001", "TX001", "TX002", "TX002"),
                    IntStream.rangeClosed(1, 4).mapToObj(index -> text(sheet, index, 0)).toList());
            assertEquals(List.of("readA", "sharedRead", "sharedRead", "writeA"),
                    IntStream.rangeClosed(1, 4).mapToObj(index -> text(sheet, index, 8)).toList());
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals(9, tables.getRow(0).getLastCellNum());
            assertEquals("com.example.PublicDao.readA\ncom.example.PublicDao.sharedRead", text(tables, 1, 8));
            assertEquals("com.example.PublicDao.sharedRead\ncom.example.PublicDao.writeA", text(tables, 2, 8));
        }
    }

    @Test
    void exportsDaoSheetHeadersWhenLegacyChainHasNoDaoLayer() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一")));
        Map<String, Object> result = chain("TX001", "交易一", List.of(), List.of(), List.of(table("TAB-A", "表A")));
        ((Map<String, Object>) result.get("chain")).put("data", Map.of("table", List.of(table("TAB-A", "表A"))));
        flowtranService.setChain("TX001", result);

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet sheet = workbook.getSheet("DAO方法清单");
            assertNotNull(sheet);
            assertEquals(1, sheet.getPhysicalNumberOfRows());
            assertEquals(9, sheet.getRow(0).getLastCellNum());
            assertEquals("TAB-A", text(workbook.getSheet("数据库表清单"), 1, 2));
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals(9, tables.getRow(0).getLastCellNum());
            assertEquals("", text(tables, 1, 8));
        }
    }

    @Test
    void preservesNamedDaoMethodsWhenTableMetadataOrNodeIdentifiersAreMissing() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一")));
        Map<String, Object> legacyTable = Map.of("tableId", "LEGACY-TAB", "tableLongname", "旧表", "domainKey", "legacy",
                "domain", "旧领域", "projectName", "legacy-project", "daoClassName", "com.example.LegacyDao");
        Map<String, Object> legacyDao = Map.of("tableCode", "LEGACY-TAB", "name", "legacyRead");
        flowtranService.setChain("TX001", chain("TX001", "交易一", List.of(), List.of(), List.of(legacyTable),
                List.of(legacyDao, legacyDao, dao("MISSING-TABLE", "unmappedRead"),
                        Map.of("methodName", "noTableMethod", "daoClassName", "com.example.OtherDao"))));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet sheet = workbook.getSheet("DAO方法清单");
            assertNotNull(sheet);
            assertEquals(4, sheet.getPhysicalNumberOfRows());
            assertEquals("noTableMethod", text(sheet, 1, 8));
            assertEquals("", text(sheet, 1, 2));
            assertEquals("", text(sheet, 1, 6));
            assertEquals("LEGACY-TAB", text(sheet, 2, 2));
            assertEquals("旧表", text(sheet, 2, 3));
            assertEquals("legacy", text(sheet, 2, 4));
            assertEquals("legacy-project", text(sheet, 2, 6));
            assertEquals("com.example.LegacyDao", text(sheet, 2, 7));
            assertEquals("legacyRead", text(sheet, 2, 8));
            assertEquals("MISSING-TABLE", text(sheet, 3, 2));
            assertEquals("", text(sheet, 3, 3));
            assertEquals("public", text(sheet, 3, 4));
            assertEquals("", text(sheet, 3, 6));
            assertEquals("unmappedRead", text(sheet, 3, 8));
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals(9, tables.getRow(0).getLastCellNum());
            assertEquals(2, tables.getPhysicalNumberOfRows());
            assertEquals("com.example.LegacyDao.legacyRead", text(tables, 1, 8));
        }
    }

    @Test
    void doesNotAttachUnmappedMethodsToAnUnknownTable() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一")));
        flowtranService.setChain("TX001", chain("TX001", "交易一", List.of(), List.of(),
                List.of(Map.of("name", "未知表")),
                List.of(Map.of("methodName", "unmappedRead", "daoClassName", "com.example.OtherDao"))));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals(9, tables.getRow(0).getLastCellNum());
            assertEquals("", text(tables, 1, 8));
            assertEquals("unmappedRead", text(workbook.getSheet("DAO方法清单"), 1, 8));
        }
    }

    @Test
    void showsBareMethodNameWhenMatchedTableAndDaoHaveNoClassName() throws Exception {
        flowtranService.setTransactions(List.of(transaction("TX001", "交易一")));
        flowtranService.setChain("TX001", chain("TX001", "交易一", List.of(), List.of(),
                List.of(Map.of("code", "TAB-A", "name", "表A")),
                List.of(Map.of("tableCode", "TAB-A", "methodName", "readA"))));

        var file = exportService.exportDomain("public");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            Sheet tables = workbook.getSheet("数据库表清单");
            assertEquals("", text(tables, 1, 7));
            assertEquals("readA", text(tables, 1, 8));
            assertEquals("", text(workbook.getSheet("DAO方法清单"), 1, 7));
            assertEquals("readA", text(workbook.getSheet("DAO方法清单"), 1, 8));
        }
    }

    private static Map<String, Object> dao(String tableCode, String methodName) {
        return Map.of("code", "com.example.PublicDao#" + methodName.trim(),
                "methodName", methodName, "name", methodName, "tableCode", tableCode,
                "daoClassName", "com.example.PublicDao", "domainKey", "public", "domain", "公共领域");
    }

    private static String text(Sheet sheet, int rowIndex, int cellIndex) {
        return sheet.getRow(rowIndex).getCell(cellIndex).getStringCellValue();
    }

    private static FlowtranTransaction transaction(String id, String name) {
        FlowtranTransaction transaction = new FlowtranTransaction();
        transaction.setId(id);
        transaction.setLongname(name);
        transaction.setDomainKey("public");
        transaction.setTxnMode("R");
        transaction.setFromJar("public-online.jar");
        return transaction;
    }

    private static Map<String, Object> chain(String id,
                                             String name,
                                             List<Map<String, Object>> services,
                                             List<Map<String, Object>> components,
                                             List<Map<String, Object>> tables) {
        return chain(id, name, services, components, tables, List.of());
    }

    private static Map<String, Object> chain(String id,
                                             String name,
                                             List<Map<String, Object>> services,
                                             List<Map<String, Object>> components,
                                             List<Map<String, Object>> tables,
                                             List<Map<String, Object>> daos) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("service", services);
        chain.put("component", components);
        chain.put("data", Map.of("table", tables, "dao", daos));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("domain", "公共领域");
        result.put("serviceCount", (long) services.stream().map(item -> item.get("code")).distinct().count());
        result.put("componentCount", (long) components.stream().map(item -> item.get("code")).distinct().count());
        result.put("tableCount", (long) tables.stream().map(item -> item.get("code")).distinct().count());
        result.put("chain", chain);
        return result;
    }

    private static Map<String, Object> node(String code, String name, String prefix) {
        return Map.of(
                "code", code,
                "name", name,
                "prefix", prefix,
                "domainKey", "public",
                "domain", "公共领域");
    }

    private static Map<String, Object> table(String code, String name) {
        return Map.of(
                "code", code,
                "name", name,
                "domainKey", "public",
                "domain", "公共领域",
                "projectName", "public-online",
                "daoClassName", "com.example.PublicDao");
    }

    private static final class StubFlowtranService implements FlowtranService {
        private List<FlowtranTransaction> transactions = List.of();
        private final Map<String, Map<String, Object>> chains = new LinkedHashMap<>();
        private final List<Integer> requestedPages = new ArrayList<>();

        void setTransactions(List<FlowtranTransaction> transactions) {
            this.transactions = new ArrayList<>(transactions);
        }

        void setChain(String txId, Map<String, Object> chain) {
            chains.put(txId, chain);
        }

        List<Integer> requestedPages() {
            return List.copyOf(requestedPages);
        }

        @Override
        public List<FlowtranDomain> listDomains() {
            FlowtranDomain domain = new FlowtranDomain();
            domain.setDomainKey("public");
            domain.setDomainName("公共领域");
            domain.setTxCount(transactions.size());
            return List.of(domain);
        }

        @Override
        public Map<String, Object> listTransactions(String domainKey, int page, int size, String keyword) {
            requestedPages.add(page);
            int from = Math.min((page - 1) * size, transactions.size());
            int to = Math.min(from + size, transactions.size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", new ArrayList<>(transactions.subList(from, to)));
            result.put("total", (long) transactions.size());
            result.put("page", page);
            result.put("size", size);
            return result;
        }

        @Override
        public Map<String, Object> getChain(String txId) {
            return chains.get(txId);
        }

        @Override
        public Map<String, Object> collectChainMethods(String txId) {
            return null;
        }
    }
}
