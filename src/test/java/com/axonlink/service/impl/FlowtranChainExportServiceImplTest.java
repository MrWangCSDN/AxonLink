package com.axonlink.service.impl;

import com.axonlink.dto.FlowtranDomain;
import com.axonlink.dto.FlowtranTransaction;
import com.axonlink.service.FlowtranChainExportService;
import com.axonlink.service.FlowtranService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowtranChainExportServiceImplTest {

    private StubFlowtranService flowtranService;
    private FlowtranChainExportService exportService;

    @BeforeEach
    void setUp() {
        flowtranService = new StubFlowtranService();
        exportService = new FlowtranChainExportServiceImpl(flowtranService);
    }

    @Test
    void exportsFourSheetsSortedByTransactionAndNodeCode() throws Exception {
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
            assertEquals(List.of("交易清单", "服务清单", "构件清单", "数据库表清单"),
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
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("service", services);
        chain.put("component", components);
        chain.put("data", Map.of("table", tables, "dao", List.of()));

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
