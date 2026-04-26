package com.axonlink.service.impl;

import com.axonlink.service.FlowtranImpactExportService;
import com.axonlink.service.FlowtranImpactProjectionCache;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FlowtranImpactExportServiceImpl implements FlowtranImpactExportService {

    private static final Logger log = LoggerFactory.getLogger(FlowtranImpactExportServiceImpl.class);
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private final FlowtranImpactProjectionCache impactProjectionCache;
    private final AtomicReference<AllExportSnapshot> allExportSnapshotRef =
        new AtomicReference<>(AllExportSnapshot.empty());

    public FlowtranImpactExportServiceImpl(FlowtranImpactProjectionCache impactProjectionCache) {
        this.impactProjectionCache = impactProjectionCache;
    }

    @Override
    public ExportFile exportSingle(String mode, String id) {
        ExportMode exportMode = ExportMode.from(mode);
        if (isBlank(id)) {
            throw new IllegalArgumentException("导出目标不能为空");
        }
        ensureCacheReady();

        Map<String, Object> result = exportMode.singleResult(impactProjectionCache, id);
        if (result == null || result.isEmpty()) {
            throw new NoSuchElementException("未找到可导出的影响分析结果：" + id);
        }

        Map<String, Map<String, Object>> payload = new LinkedHashMap<>();
        payload.put(id, result);
        return new ExportFile(
            buildFileName(exportMode, sanitizeFilePart(id), false),
            buildWorkbook(exportMode, payload)
        );
    }

    @Override
    public ExportFile exportAll(String mode) {
        ExportMode exportMode = ExportMode.from(mode);
        ExportFile exportFile = allExportSnapshotRef.get().exportFiles.get(exportMode.code);
        if (exportFile == null || exportFile.getContent() == null || exportFile.getContent().length == 0) {
            throw new IllegalStateException("后台制作中，稍后重试");
        }
        return exportFile;
    }

    @Override
    public void clearAllCache(String reason) {
        allExportSnapshotRef.set(AllExportSnapshot.empty());
        log.info("[ImpactExport] 已清空全量 Excel 缓存，reason={}", firstNonBlank(reason, "build-start"));
    }

    @Override
    public synchronized Map<String, Object> rebuildAllCache() {
        ensureCacheReady();
        long totalStart = System.currentTimeMillis();
        Map<String, ExportFile> files = new LinkedHashMap<>();
        Map<String, Object> status = new LinkedHashMap<>();

        for (ExportMode mode : ExportMode.values()) {
            long start = System.currentTimeMillis();
            ExportFile exportFile = buildAllExportFile(mode);
            long elapsed = System.currentTimeMillis() - start;
            files.put(mode.code, exportFile);

            Map<String, Object> modeStatus = new LinkedHashMap<>();
            modeStatus.put("fileName", exportFile.getFileName());
            modeStatus.put("sizeBytes", exportFile.getContent().length);
            modeStatus.put("elapsedMs", elapsed);
            status.put(mode.code, modeStatus);

            log.info("[ImpactExport] 全量 Excel 生成完成 mode={} elapsedMs={} sizeBytes={} file={}",
                mode.code, elapsed, exportFile.getContent().length, exportFile.getFileName());
        }

        String builtAt = LocalDateTime.now().toString();
        allExportSnapshotRef.set(AllExportSnapshot.ready(builtAt, files));

        long totalElapsed = System.currentTimeMillis() - totalStart;
        status.put("ready", true);
        status.put("builtAt", builtAt);
        status.put("elapsedMs", totalElapsed);
        log.info("[ImpactExport] 三类全量 Excel 已就绪 builtAt={} elapsedMs={}", builtAt, totalElapsed);
        return status;
    }

    private void ensureCacheReady() {
        if (!impactProjectionCache.isReady()) {
            throw new IllegalStateException("影响分析缓存未就绪，请等待异步构建完成后再导出");
        }
    }

    private ExportFile buildAllExportFile(ExportMode exportMode) {
        Map<String, Map<String, Object>> payload = exportMode.allResults(impactProjectionCache);
        return new ExportFile(
            buildFileName(exportMode, "all", true),
            buildWorkbook(exportMode, payload)
        );
    }

    private byte[] buildWorkbook(ExportMode mode, Map<String, Map<String, Object>> payload) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            ModeWorkbookRows workbookRows = collectWorkbookRows(mode, payload);
            Set<String> usedSheetNames = new LinkedHashSet<>();

            writeSummarySheet(workbook, styles, usedSheetNames, workbookRows.summaryRows);
            for (Map.Entry<String, List<LayerExportRow>> entry : workbookRows.layerRows.entrySet()) {
                writeLayerSheet(workbook, styles, usedSheetNames, entry.getKey(), entry.getValue());
            }

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成 Excel 失败", e);
        }
    }

    private ModeWorkbookRows collectWorkbookRows(ExportMode mode, Map<String, Map<String, Object>> payload) {
        List<SummaryRow> summaryRows = new ArrayList<>();
        Map<String, List<LayerExportRow>> layerRows = new LinkedHashMap<>();
        for (String sheetName : mode.layerSheetNames()) {
            layerRows.put(sheetName, new ArrayList<>());
        }

        for (Map.Entry<String, Map<String, Object>> entry : payload.entrySet()) {
            Map<String, Object> result = entry.getValue();
            if (result == null || result.isEmpty()) {
                continue;
            }
            ExportRoot root = extractRoot(result);
            if (root == null) {
                continue;
            }
            RootExport export = mode.export(result, root);
            summaryRows.add(export.summaryRow);
            export.layerRows.forEach((sheetName, rows) ->
                layerRows.computeIfAbsent(sheetName, ignored -> new ArrayList<>()).addAll(rows)
            );
        }
        return new ModeWorkbookRows(summaryRows, layerRows);
    }

    private ExportRoot extractRoot(Map<String, Object> result) {
        Map<String, Object> root = mapValue(result.get("root"));
        String id = stringValue(root, "id");
        if (isBlank(id)) {
            return null;
        }
        return new ExportRoot(
            id,
            firstNonBlank(stringValue(root, "name"), id),
            stringValue(root, "domainId")
        );
    }

    private void writeSummarySheet(XSSFWorkbook workbook,
                                   Styles styles,
                                   Set<String> usedSheetNames,
                                   List<SummaryRow> rows) {
        Sheet sheet = workbook.createSheet(nextSheetName("汇总", usedSheetNames));
        String[] headers = {"根节点ID", "根节点名称", "根节点领域", "构件数", "服务数", "流程编排数", "交易数"};
        writeHeader(sheet, styles.headerStyle, headers);
        int rowIndex = 1;
        for (SummaryRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            writeCell(row, 0, rowData.rootId, styles.bodyStyle);
            writeCell(row, 1, rowData.rootName, styles.bodyStyle);
            writeCell(row, 2, rowData.rootDomain, styles.bodyStyle);
            writeCell(row, 3, rowData.componentCount, styles.bodyStyle);
            writeCell(row, 4, rowData.serviceCount, styles.bodyStyle);
            writeCell(row, 5, rowData.orchestrationCount, styles.bodyStyle);
            writeCell(row, 6, rowData.transactionCount, styles.bodyStyle);
        }
        configureColumnWidths(sheet, List.of(26, 28, 14, 12, 12, 14, 12));
    }

    private void writeLayerSheet(XSSFWorkbook workbook,
                                 Styles styles,
                                 Set<String> usedSheetNames,
                                 String sheetName,
                                 List<LayerExportRow> rows) {
        Sheet sheet = workbook.createSheet(nextSheetName(sheetName, usedSheetNames));
        String[] headers = {"根节点ID", "根节点名称", "根节点领域", "目标ID", "目标名称", "目标类型", "目标领域"};
        writeHeader(sheet, styles.headerStyle, headers);
        int rowIndex = 1;
        for (LayerExportRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            writeCell(row, 0, rowData.rootId, styles.bodyStyle);
            writeCell(row, 1, rowData.rootName, styles.bodyStyle);
            writeCell(row, 2, rowData.rootDomain, styles.bodyStyle);
            writeCell(row, 3, rowData.targetId, styles.bodyStyle);
            writeCell(row, 4, rowData.targetName, styles.bodyStyle);
            writeCell(row, 5, rowData.targetType, styles.bodyStyle);
            writeCell(row, 6, rowData.targetDomain, styles.bodyStyle);
        }
        configureColumnWidths(sheet, List.of(26, 28, 14, 26, 28, 14, 14));
    }

    private void writeHeader(Sheet sheet, CellStyle headerStyle, String[] headers) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);
    }

    private void writeCell(Row row, int cellIndex, Object value, CellStyle cellStyle) {
        Cell cell = row.createCell(cellIndex);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
        cell.setCellStyle(cellStyle);
    }

    private void configureColumnWidths(Sheet sheet, List<Integer> widths) {
        for (int i = 0; i < widths.size(); i++) {
            sheet.setColumnWidth(i, widths.get(i) * 256);
        }
    }

    private static String nextSheetName(String requestedName, Set<String> usedSheetNames) {
        String base = WorkbookUtil.createSafeSheetName(firstNonBlank(requestedName, "Sheet"));
        if (isBlank(base)) {
            base = "Sheet";
        }
        if (base.length() > 31) {
            base = base.substring(0, 31);
        }

        String candidate = base;
        int index = 2;
        while (usedSheetNames.contains(candidate)) {
            String suffix = "_" + index++;
            int maxBaseLength = Math.max(1, 31 - suffix.length());
            String prefix = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base;
            candidate = prefix + suffix;
        }
        usedSheetNames.add(candidate);
        return candidate;
    }

    private static List<Map<String, Object>> levels(Map<String, Object> result, int index) {
        Object value = result.get("levels");
        if (!(value instanceof List<?> levelList) || index < 0 || index >= levelList.size()) {
            return List.of();
        }
        Object levelValue = levelList.get(index);
        if (!(levelValue instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                nodes.add(map);
            }
        }
        return nodes;
    }

    private static List<Map<String, Object>> dedupeById(List<Map<String, Object>> nodes) {
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = stringValue(node, "id");
            if (isBlank(id) || deduped.containsKey(id)) {
                continue;
            }
            deduped.put(id, node);
        }
        return new ArrayList<>(deduped.values());
    }

    private static List<Map<String, Object>> dedupeTransactions(List<Map<String, Object>> nodes) {
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String key = firstNonBlank(stringValue(node, "name"), stringValue(node, "id"));
            if (isBlank(key) || deduped.containsKey(key)) {
                continue;
            }
            deduped.put(key, node);
        }
        return new ArrayList<>(deduped.values());
    }

    private static List<LayerExportRow> toLayerRows(ExportRoot root,
                                                    List<Map<String, Object>> nodes,
                                                    boolean transactionSheet) {
        List<LayerExportRow> rows = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            rows.add(new LayerExportRow(
                root.id,
                root.name,
                root.domain,
                firstNonBlank(stringValue(node, "id"), stringValue(node, "name")),
                firstNonBlank(stringValue(node, "name"), stringValue(node, "id")),
                transactionSheet ? "transaction" : firstNonBlank(stringValue(node, "type"), stringValue(node, "nodeType")),
                stringValue(node, "domainId")
            ));
        }
        return rows;
    }

    private static String buildFileName(ExportMode mode, String key, boolean all) {
        String ts = LocalDateTime.now().format(FILE_TS_FORMAT);
        return all
            ? "impact-" + mode.code + "-all-" + ts + ".xlsx"
            : "impact-" + mode.code + "-" + key + "-" + ts + ".xlsx";
    }

    private static String sanitizeFilePart(String value) {
        String sanitized = String.valueOf(value)
            .replaceAll("[\\\\/:*?\"<>|]+", "_")
            .replaceAll("\\s+", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private enum ExportMode {
        TABLE("table") {
            @Override
            Map<String, Object> singleResult(FlowtranImpactProjectionCache cache, String id) {
                return cache.getTableImpact(id);
            }

            @Override
            Map<String, Map<String, Object>> allResults(FlowtranImpactProjectionCache cache) {
                return cache.getAllTableImpacts();
            }

            @Override
            List<String> layerSheetNames() {
                return List.of("构件", "服务", "交易");
            }

            @Override
            RootExport export(Map<String, Object> result, ExportRoot root) {
                List<Map<String, Object>> components = dedupeById(levels(result, 0));
                List<Map<String, Object>> services = dedupeById(levels(result, 1));
                List<Map<String, Object>> transactions = dedupeTransactions(levels(result, 2));
                Map<String, List<LayerExportRow>> layers = new LinkedHashMap<>();
                layers.put("构件", toLayerRows(root, components, false));
                layers.put("服务", toLayerRows(root, services, false));
                layers.put("交易", toLayerRows(root, transactions, true));
                return new RootExport(
                    new SummaryRow(root.id, root.name, root.domain, components.size(), services.size(), null, transactions.size()),
                    layers
                );
            }
        },
        COMPONENT("component") {
            @Override
            Map<String, Object> singleResult(FlowtranImpactProjectionCache cache, String id) {
                return cache.getComponentImpact(id);
            }

            @Override
            Map<String, Map<String, Object>> allResults(FlowtranImpactProjectionCache cache) {
                return cache.getAllComponentImpacts();
            }

            @Override
            List<String> layerSheetNames() {
                return List.of("服务", "交易");
            }

            @Override
            RootExport export(Map<String, Object> result, ExportRoot root) {
                List<Map<String, Object>> services = dedupeById(levels(result, 0));
                List<Map<String, Object>> transactions = dedupeTransactions(levels(result, 1));
                Map<String, List<LayerExportRow>> layers = new LinkedHashMap<>();
                layers.put("服务", toLayerRows(root, services, false));
                layers.put("交易", toLayerRows(root, transactions, true));
                return new RootExport(
                    new SummaryRow(root.id, root.name, root.domain, null, services.size(), null, transactions.size()),
                    layers
                );
            }
        },
        SERVICE("service") {
            @Override
            Map<String, Object> singleResult(FlowtranImpactProjectionCache cache, String id) {
                return cache.getServiceImpact(id);
            }

            @Override
            Map<String, Map<String, Object>> allResults(FlowtranImpactProjectionCache cache) {
                return cache.getAllServiceImpacts();
            }

            @Override
            List<String> layerSheetNames() {
                return List.of("上游服务", "流程编排", "交易");
            }

            @Override
            RootExport export(Map<String, Object> result, ExportRoot root) {
                List<Map<String, Object>> upstreamServices = dedupeById(levels(result, 0));
                List<Map<String, Object>> orchestrations = dedupeById(levels(result, 1));
                List<Map<String, Object>> transactions = dedupeTransactions(levels(result, 2));
                Map<String, List<LayerExportRow>> layers = new LinkedHashMap<>();
                layers.put("上游服务", toLayerRows(root, upstreamServices, false));
                layers.put("流程编排", toLayerRows(root, orchestrations, false));
                layers.put("交易", toLayerRows(root, transactions, true));
                return new RootExport(
                    new SummaryRow(root.id, root.name, root.domain, null, upstreamServices.size(), orchestrations.size(), transactions.size()),
                    layers
                );
            }
        };

        private final String code;

        ExportMode(String code) {
            this.code = code;
        }

        abstract Map<String, Object> singleResult(FlowtranImpactProjectionCache cache, String id);

        abstract Map<String, Map<String, Object>> allResults(FlowtranImpactProjectionCache cache);

        abstract List<String> layerSheetNames();

        abstract RootExport export(Map<String, Object> result, ExportRoot root);

        private static ExportMode from(String mode) {
            String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
            for (ExportMode value : values()) {
                if (Objects.equals(value.code, normalized)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("不支持的导出模式：" + mode);
        }
    }

    private static final class Styles {
        private final CellStyle headerStyle;
        private final CellStyle bodyStyle;

        private Styles(XSSFWorkbook workbook) {
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);

            this.headerStyle = workbook.createCellStyle();
            this.headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            this.headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            this.headerStyle.setAlignment(HorizontalAlignment.CENTER);
            this.headerStyle.setFont(headerFont);

            this.bodyStyle = workbook.createCellStyle();
            this.bodyStyle.setAlignment(HorizontalAlignment.LEFT);
        }
    }

    private static final class ModeWorkbookRows {
        private final List<SummaryRow> summaryRows;
        private final Map<String, List<LayerExportRow>> layerRows;

        private ModeWorkbookRows(List<SummaryRow> summaryRows, Map<String, List<LayerExportRow>> layerRows) {
            this.summaryRows = summaryRows;
            this.layerRows = layerRows;
        }
    }

    private static final class RootExport {
        private final SummaryRow summaryRow;
        private final Map<String, List<LayerExportRow>> layerRows;

        private RootExport(SummaryRow summaryRow, Map<String, List<LayerExportRow>> layerRows) {
            this.summaryRow = summaryRow;
            this.layerRows = layerRows;
        }
    }

    private static final class ExportRoot {
        private final String id;
        private final String name;
        private final String domain;

        private ExportRoot(String id, String name, String domain) {
            this.id = id;
            this.name = name;
            this.domain = domain;
        }
    }

    private static final class SummaryRow {
        private final String rootId;
        private final String rootName;
        private final String rootDomain;
        private final Integer componentCount;
        private final Integer serviceCount;
        private final Integer orchestrationCount;
        private final Integer transactionCount;

        private SummaryRow(String rootId,
                           String rootName,
                           String rootDomain,
                           Integer componentCount,
                           Integer serviceCount,
                           Integer orchestrationCount,
                           Integer transactionCount) {
            this.rootId = rootId;
            this.rootName = rootName;
            this.rootDomain = rootDomain;
            this.componentCount = componentCount;
            this.serviceCount = serviceCount;
            this.orchestrationCount = orchestrationCount;
            this.transactionCount = transactionCount;
        }
    }

    private static final class LayerExportRow {
        private final String rootId;
        private final String rootName;
        private final String rootDomain;
        private final String targetId;
        private final String targetName;
        private final String targetType;
        private final String targetDomain;

        private LayerExportRow(String rootId,
                               String rootName,
                               String rootDomain,
                               String targetId,
                               String targetName,
                               String targetType,
                               String targetDomain) {
            this.rootId = rootId;
            this.rootName = rootName;
            this.rootDomain = rootDomain;
            this.targetId = targetId;
            this.targetName = targetName;
            this.targetType = targetType;
            this.targetDomain = targetDomain;
        }
    }

    private static final class AllExportSnapshot {
        private final boolean ready;
        private final String builtAt;
        private final Map<String, ExportFile> exportFiles;

        private AllExportSnapshot(boolean ready, String builtAt, Map<String, ExportFile> exportFiles) {
            this.ready = ready;
            this.builtAt = builtAt;
            this.exportFiles = exportFiles;
        }

        private static AllExportSnapshot empty() {
            return new AllExportSnapshot(false, null, Map.of());
        }

        private static AllExportSnapshot ready(String builtAt, Map<String, ExportFile> exportFiles) {
            return new AllExportSnapshot(true, builtAt, new LinkedHashMap<>(exportFiles));
        }
    }
}
