package com.axonlink.service.impl;

import com.axonlink.dto.FlowtranTransaction;
import com.axonlink.service.FlowtranChainExportService;
import com.axonlink.service.FlowtranService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

@Service
public class FlowtranChainExportServiceImpl implements FlowtranChainExportService {
    private static final int PAGE_SIZE = 100;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final String[] TRANSACTION_HEADERS = {
            "领域编码", "领域名称", "交易码", "交易名称", "交易模式", "来源工程",
            "服务数", "构件数", "数据库表数", "导出状态", "失败原因"
    };
    private static final String[] SERVICE_HEADERS = {
            "交易码", "交易名称", "服务编码", "服务名称", "服务类型", "服务领域编码", "服务领域名称"
    };
    private static final String[] COMPONENT_HEADERS = {
            "交易码", "交易名称", "构件编码", "构件名称", "构件类型", "构件领域编码", "构件领域名称"
    };
    private static final String[] TABLE_HEADERS = {
            "交易码", "交易名称", "表英文名", "表中文名", "表所属领域编码", "表所属领域名称", "来源工程", "DAO类名"
    };

    private final FlowtranService flowtranService;

    public FlowtranChainExportServiceImpl(FlowtranService flowtranService) {
        this.flowtranService = flowtranService;
    }

    @Override
    public ExportFile exportDomain(String domainKey) {
        String normalizedDomain = domainKey == null ? "" : domainKey.trim();
        if (normalizedDomain.isEmpty()) {
            throw new IllegalArgumentException("领域编码不能为空");
        }

        List<FlowtranTransaction> transactions = loadAllTransactions(normalizedDomain);
        if (transactions.isEmpty()) {
            throw new NoSuchElementException("未找到可导出的领域交易：" + normalizedDomain);
        }
        transactions.sort(Comparator.comparing(
                FlowtranTransaction::getId,
                Comparator.nullsLast(String::compareTo)));

        List<TransactionRow> transactionRows = new ArrayList<>();
        Map<String, NodeRow> serviceRows = new TreeMap<>();
        Map<String, NodeRow> componentRows = new TreeMap<>();
        Map<String, TableRow> tableRows = new TreeMap<>();
        String domainName = resolveDomainName(normalizedDomain);

        for (FlowtranTransaction transaction : transactions) {
            try {
                Map<String, Object> result = flowtranService.getChain(transaction.getId());
                if (result == null || result.isEmpty()) {
                    transactionRows.add(TransactionRow.failed(transaction, domainName, "未获取到交易链路"));
                    continue;
                }
                domainName = firstNonBlank(text(result.get("domain")), domainName);
                Map<String, Object> chain = map(result.get("chain"));
                for (Map<String, Object> node : maps(chain.get("service"))) {
                    NodeRow row = NodeRow.from(transaction, node);
                    serviceRows.putIfAbsent(row.key(), row);
                }
                for (Map<String, Object> node : maps(chain.get("component"))) {
                    NodeRow row = NodeRow.from(transaction, node);
                    componentRows.putIfAbsent(row.key(), row);
                }
                Map<String, Object> data = map(chain.get("data"));
                for (Map<String, Object> node : maps(data.get("table"))) {
                    TableRow row = TableRow.from(transaction, node);
                    tableRows.putIfAbsent(row.key(), row);
                }
                transactionRows.add(TransactionRow.success(transaction, domainName,
                        number(result.get("serviceCount")),
                        number(result.get("componentCount")),
                        number(result.get("tableCount"))));
            } catch (RuntimeException exception) {
                transactionRows.add(TransactionRow.failed(transaction, domainName,
                        firstNonBlank(exception.getMessage(), exception.getClass().getSimpleName())));
            }
        }

        byte[] content = buildWorkbook(transactionRows, serviceRows.values().stream().toList(),
                componentRows.values().stream().toList(), tableRows.values().stream().toList());
        String fileName = safeFilePart(domainName) + "-全量交易链路-" + FILE_TIME.format(LocalDateTime.now()) + ".xlsx";
        return new ExportFile(fileName, content);
    }

    private List<FlowtranTransaction> loadAllTransactions(String domainKey) {
        Map<String, Object> firstPage = flowtranService.listTransactions(domainKey, 1, PAGE_SIZE, null);
        long total = number(firstPage.get("total"));
        List<FlowtranTransaction> transactions = new ArrayList<>(transactions(firstPage.get("list")));
        int pages = (int) ((total + PAGE_SIZE - 1) / PAGE_SIZE);
        for (int page = 2; page <= pages; page++) {
            Map<String, Object> nextPage = flowtranService.listTransactions(domainKey, page, PAGE_SIZE, null);
            transactions.addAll(transactions(nextPage.get("list")));
        }
        return transactions;
    }

    private String resolveDomainName(String domainKey) {
        try {
            return flowtranService.listDomains().stream()
                    .filter(domain -> domainKey.equals(domain.getDomainKey()))
                    .map(domain -> text(domain.getDomainName()))
                    .filter(name -> !name.isBlank())
                    .findFirst()
                    .orElse(domainKey);
        } catch (RuntimeException exception) {
            return domainKey;
        }
    }

    private byte[] buildWorkbook(List<TransactionRow> transactions,
                                 List<NodeRow> services,
                                 List<NodeRow> components,
                                 List<TableRow> tables) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            writeTransactions(workbook, headerStyle, transactions);
            writeNodes(workbook, headerStyle, "服务清单", SERVICE_HEADERS, services);
            writeNodes(workbook, headerStyle, "构件清单", COMPONENT_HEADERS, components);
            writeTables(workbook, headerStyle, tables);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成交易链路 Excel 失败", exception);
        } finally {
            workbook.dispose();
        }
    }

    private static void writeTransactions(SXSSFWorkbook workbook,
                                          CellStyle headerStyle,
                                          List<TransactionRow> rows) {
        Sheet sheet = createSheet(workbook, "交易清单", TRANSACTION_HEADERS, headerStyle);
        int index = 1;
        for (TransactionRow item : rows) {
            Row row = sheet.createRow(index++);
            strings(row, item.domainKey, item.domainName, item.txId, item.txName, item.txnMode, item.fromJar);
            row.createCell(6).setCellValue(item.serviceCount);
            row.createCell(7).setCellValue(item.componentCount);
            row.createCell(8).setCellValue(item.tableCount);
            row.createCell(9).setCellValue(item.status);
            row.createCell(10).setCellValue(item.failureReason);
        }
        finishSheet(sheet, TRANSACTION_HEADERS.length, index);
    }

    private static void writeNodes(SXSSFWorkbook workbook,
                                   CellStyle headerStyle,
                                   String sheetName,
                                   String[] headers,
                                   List<NodeRow> rows) {
        Sheet sheet = createSheet(workbook, sheetName, headers, headerStyle);
        int index = 1;
        for (NodeRow item : rows) {
            Row row = sheet.createRow(index++);
            strings(row, item.txId, item.txName, item.code, item.name, item.prefix,
                    item.domainKey, item.domainName);
        }
        finishSheet(sheet, headers.length, index);
    }

    private static void writeTables(SXSSFWorkbook workbook,
                                    CellStyle headerStyle,
                                    List<TableRow> rows) {
        Sheet sheet = createSheet(workbook, "数据库表清单", TABLE_HEADERS, headerStyle);
        int index = 1;
        for (TableRow item : rows) {
            Row row = sheet.createRow(index++);
            strings(row, item.txId, item.txName, item.code, item.name, item.domainKey,
                    item.domainName, item.projectName, item.daoClassName);
        }
        finishSheet(sheet, TABLE_HEADERS.length, index);
    }

    private static Sheet createSheet(SXSSFWorkbook workbook,
                                     String name,
                                     String[] headers,
                                     CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
            header.getCell(i).setCellStyle(headerStyle);
            sheet.setColumnWidth(i, Math.min(50, Math.max(14, headers[i].length() * 3)) * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private static void finishSheet(Sheet sheet, int columnCount, int nextRowIndex) {
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, nextRowIndex - 1), 0, columnCount - 1));
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static void strings(Row row, String... values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
        }
    }

    private static List<FlowtranTransaction> transactions(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<FlowtranTransaction> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof FlowtranTransaction transaction) result.add(transaction);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) result.add(map(item));
        }
        return result;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private static String safeFilePart(String value) {
        String result = firstNonBlank(value, "领域").replaceAll("[\\\\/:*?\"<>|]", "_");
        return result.isBlank() ? "领域" : result;
    }

    private record TransactionRow(String domainKey, String domainName, String txId, String txName,
                                  String txnMode, String fromJar, long serviceCount, long componentCount,
                                  long tableCount, String status, String failureReason) {
        static TransactionRow success(FlowtranTransaction tx, String domainName,
                                      long serviceCount, long componentCount, long tableCount) {
            return new TransactionRow(text(tx.getDomainKey()), text(domainName), text(tx.getId()),
                    text(tx.getLongname()), text(tx.getTxnMode()), text(tx.getFromJar()),
                    serviceCount, componentCount, tableCount, "成功", "");
        }

        static TransactionRow failed(FlowtranTransaction tx, String domainName, String reason) {
            return new TransactionRow(text(tx.getDomainKey()), text(domainName), text(tx.getId()),
                    text(tx.getLongname()), text(tx.getTxnMode()), text(tx.getFromJar()),
                    0, 0, 0, "失败", text(reason));
        }
    }

    private record NodeRow(String txId, String txName, String code, String name, String prefix,
                           String domainKey, String domainName) {
        static NodeRow from(FlowtranTransaction tx, Map<String, Object> node) {
            return new NodeRow(text(tx.getId()), text(tx.getLongname()), text(node.get("code")),
                    text(node.get("name")), text(node.get("prefix")), text(node.get("domainKey")),
                    text(node.get("domain")));
        }

        String key() {
            return txId + '\u0000' + code;
        }
    }

    private record TableRow(String txId, String txName, String code, String name, String domainKey,
                            String domainName, String projectName, String daoClassName) {
        static TableRow from(FlowtranTransaction tx, Map<String, Object> node) {
            return new TableRow(text(tx.getId()), text(tx.getLongname()),
                    firstNonBlank(text(node.get("code")), text(node.get("tableId"))),
                    firstNonBlank(text(node.get("name")), text(node.get("tableLongname"))),
                    text(node.get("domainKey")), text(node.get("domain")),
                    text(node.get("projectName")), text(node.get("daoClassName")));
        }

        String key() {
            return txId + '\u0000' + code;
        }
    }
}
