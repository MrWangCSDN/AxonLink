package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.DailyIssueSlice;
import com.axonlink.ai.replay.dto.DailyReportRow;
import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow;
import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow.Part;
import com.axonlink.ai.replay.service.ReplayIssueSummaryParser.SummaryRateTotals;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 回放问题日报 · .xlsx 快照生成（滚动窗口设计）。
 *
 * <p>滚动逻辑：
 * <ul>
 *   <li><b>首次导入</b>（同批次族无历史日报）：上半部分只保留表头，上一批次批次号与数据留空；
 *       下半部分读取本次 Excel「汇总信息」sheet。本批次交易静态指标保留 Excel 值，“合理差异”及
 *       问题动态列按本批次出现关系实时计算。</li>
 *   <li><b>后续导入</b>：日报的上半部分 = 上一份日报的"下半部分"，下半部分 = 本次 Excel 的下半部分。
 *       两部分的动态列同样实时算。</li>
 * </ul>
 *
 * <p>分组维度：领域 + 是否沙箱（沙箱-xxx）；上、下区域分别按各自 occurrence batch 实时统计。
 *
 * <p>快照语义：状态修改不影响日报数据（导入时落盘，文件不再变动）。
 */
@Service
public class ReplayIssueDailyReportService {

    private static final Logger log = LoggerFactory.getLogger(ReplayIssueDailyReportService.class);

    /** 上半部分"按 issue_type 分列"超过此上限合并到"其他类型"。 */
    private static final int TYPE_COLUMN_CAP = 20;
    /** 下半部分固定的 5 个 status 分类列（不含"已修复"，按用户要求）。 */
    private static final List<String> UNRESOLVED_STATUS_COLUMNS = List.of(
            "新建", "打开", "重新打开", "延后修复", "修复待验证");

    private final ReplayIssueDao dao;
    private final Path storageRoot;

    public ReplayIssueDailyReportService(ReplayIssueDao dao,
                                         @Value("${replay.daily-report.directory:daily-reports}") String directory) {
        this.dao = dao;
        this.storageRoot = Paths.get(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("日报落盘目录创建失败: " + this.storageRoot, e);
        }
    }

    /**
     * 导入完成后调用：滚动窗口生成日报。
     *
     * @param currentBatch  本次导入的"当前批次"标识（occurrence 的 batch_name，Excel 里有）
     * @param importedAt    本次导入时间
     * @param excelSummary  本次 Excel 的「汇总信息」sheet 解析结果（upperRows + lowerRows）
     * @return 落盘文件的绝对路径；生成失败返回 null（不影响导入）
     */
    public Path generateNext(String currentBatch, LocalDateTime importedAt,
                              ReplayIssueSummaryParser.ParsedSummary excelSummary) {
        if (currentBatch == null || currentBatch.isBlank()) {
            log.warn("[daily-report] 当前批次为空，跳过日报生成");
            return null;
        }
        if (excelSummary == null || excelSummary.lowerRows().isEmpty()) {
            throw new IllegalArgumentException("汇总信息未识别到本批次区域，请检查 Excel 下半部分格式");
        }
        // 找上一份日报（按修改时间倒序，排除当前正在生成的）
        Path previousReport = findPreviousReport(currentBatch);
        List<ReplayIssueSummaryRow> upperRows;
        String previousBatchNo;
        SummaryRateTotals upperTotals;
        if (previousReport != null) {
            // 续接：读上一份日报的"下半部分"作为本次的上半
            ReplayIssueSummaryParser.ParsedSummary prev = readReport(previousReport);
            if (prev.lowerRows().isEmpty()) {
                throw new IllegalStateException("无法读取上一份日报的本批次区域: " + previousReport.getFileName());
            }
            upperRows = new ArrayList<>(prev.lowerRows());
            upperTotals = prev.lowerTotals();
            // 上半对应的批次号 = 上一份日报下半里 batchNo 字段（= 上次的"当前批次"标识）
            previousBatchNo = upperRows.isEmpty() ? null : upperRows.get(0).batchNo();
        } else {
            // 首次导入：不存在真实上一批次，上半区只保留表头，不消费 Excel 上半区数据。
            upperRows = List.of();
            upperTotals = SummaryRateTotals.EMPTY;
            previousBatchNo = null;
        }
        List<ReplayIssueSummaryRow> lowerRows = excelSummary == null ? List.of() : new ArrayList<>(excelSummary.lowerRows());
        SummaryRateTotals lowerTotals = excelSummary == null ? SummaryRateTotals.EMPTY : excelSummary.lowerTotals();
        return writeReport(currentBatch, importedAt, previousBatchNo, upperRows, lowerRows,
                upperTotals, lowerTotals, previousReport);
    }

    /**
     * 找 daily-reports 目录下最近的一份日报（修改时间倒序，不包含 currentBatch）。
     */
    private Path findPreviousReport(String currentBatch) {
        if (!Files.exists(storageRoot)) {
            return null;
        }
        String currentFileName = reportFileName(currentBatch);
        try (Stream<Path> stream = Files.list(storageRoot)) {
            return stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".xlsx"))
                    .filter(p -> !p.getFileName().toString().equals(currentFileName))
                    .filter(p -> sameBatchFamily(currentBatch, p))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(null);
        } catch (IOException e) {
            log.warn("[daily-report] 查找上一份日报失败: {}", e.getMessage());
            return null;
        }
    }

    private static boolean sameBatchFamily(String currentBatch, Path candidate) {
        return batchFamily(currentBatch).equals(batchFamily(candidate.getFileName().toString()));
    }

    private static String batchFamily(String batch) {
        if (batch != null && batch.startsWith("RPT")) {
            return "RPT";
        }
        if (batch != null && batch.startsWith("DZ")) {
            return "DZ";
        }
        return "LEGACY";
    }

    /**
     * 读取已落盘的日报文件，提取下半部分行（用于滚动接续）。
     */
    private ReplayIssueSummaryParser.ParsedSummary readReport(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            // 复用 parser 的 sheet 处理：sheet 名固定"汇总信息"，但这个解析路径会按字段找，
            // 无法直接区分上下半。这里用临时封装走 POI 直接读 + 解析两个表头行。
            // 为简单起见：把整个 sheet 当作"上半部分"读取，然后解析"下半部分"——
            // 但旧格式没有 part 区分，所以这份"历史日报"我们整体当作 upper 读取（兼容旧版），
            // 实际上 generateNext 用上一份日报的"下半"，但旧版日报是单一区块。
            // 因此约定：generateNext 上线后，旧版单一区块的日报被整体当作 upper 读取（旧版无下半概念）。
            Workbook workbook = WorkbookFactory.create(input);
            Sheet sheet = workbook.getSheet(ReplayIssueSummaryParser.SHEET_NAME);
            if (sheet == null) {
                return new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(), false);
            }
            // 旧版日报：扫描整个 sheet 找所有已知字段当作 single-block 读取，作为 upper 使用。
            // 新版日报（本服务生成）按 part 字段区分下半。
            return readHistoricalReport(sheet, workbook);
        } catch (IOException e) {
            log.warn("[daily-report] 读取历史日报失败 {}: {}", file, e.getMessage());
            return new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(), false);
        }
    }

    /**
     * 读历史日报：优先用 cell 里 "lowerRows" 的标志（通过 file 内的 part 区分）。
     * 但旧版日报没有 part 字段 → 整段当作"上半"读（兼容）。
     * 新版日报（自身生成）直接读所有 row，按 row 的 part 字段分上下半。
     */
    private ReplayIssueSummaryParser.ParsedSummary readHistoricalReport(Sheet sheet, Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<ReplayIssueSummaryRow> upper = new ArrayList<>();
        List<ReplayIssueSummaryRow> lower = new ArrayList<>();
        // 简单判别：如果存在两个独立表头区域（有"排查进度"行 + 有"解决进度"行），按新版读；
        // 否则旧版，整体作为上半。
        int inspectionRow = findHeaderRow(sheet, formatter, evaluator, "问题排查进度");
        int resolutionRow = findHeaderRow(sheet, formatter, evaluator, "问题解决进度");
        SummaryRateTotals upperTotals = SummaryRateTotals.EMPTY;
        SummaryRateTotals lowerTotals = SummaryRateTotals.EMPTY;
        if (inspectionRow >= 0 && resolutionRow >= 0 && resolutionRow > inspectionRow) {
            // 新版日报：解析两段
            upper.addAll(extractRegion(sheet, formatter, evaluator, inspectionRow, resolutionRow - 1, Part.UPPER));
            lower.addAll(extractRegion(sheet, formatter, evaluator, resolutionRow, -1, Part.LOWER));
            upperTotals = extractRegionTotals(sheet, formatter, evaluator, inspectionRow, resolutionRow - 1);
            lowerTotals = extractRegionTotals(sheet, formatter, evaluator, resolutionRow, -1);
        } else {
            // 旧版：单一区块，简化处理——当作上半
            int firstFieldCol = 0;
            int headerRow = inspectionRow >= 0 ? inspectionRow : findFirstHeaderRow(sheet, formatter, evaluator);
            if (headerRow < 0) {
                return new ReplayIssueSummaryParser.ParsedSummary(List.of(), List.of(), true);
            }
            // 旧版：无法确定上下半，全部当作上半（已有路径兼容）
            upper.addAll(extractLegacy(sheet, formatter, evaluator, headerRow, firstFieldCol));
        }
        return new ReplayIssueSummaryParser.ParsedSummary(upper, lower, true, upperTotals, lowerTotals);
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, String keyword) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int colIndex = row.getFirstCellNum(); colIndex < row.getLastCellNum(); colIndex++) {
                Cell cell = row.getCell(colIndex);
                if (cell == null) continue;
                String text = formatter.formatCellValue(cell, evaluator);
                if (text != null && text.contains(keyword)) {
                    return rowIndex;
                }
            }
        }
        return -1;
    }

    private int findFirstHeaderRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        // 找包含 BATCH_NO 字段的第一行
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int colIndex = row.getFirstCellNum(); colIndex < row.getLastCellNum(); colIndex++) {
                Cell cell = row.getCell(colIndex);
                if (cell == null) continue;
                String text = formatter.formatCellValue(cell, evaluator).trim();
                if ("批次".equals(text) || "批次号".equals(text) || text.startsWith("批次")) {
                    return rowIndex;
                }
            }
        }
        return -1;
    }

    private List<ReplayIssueSummaryRow> extractRegion(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                                                       int headerRow, int nextHeaderRow, Part part) {
        Map<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, Integer> cols = locateCols(sheet, headerRow, formatter, evaluator);
        if (cols.isEmpty()) return List.of();
        List<ReplayIssueSummaryRow> rows = new ArrayList<>();
        int firstFieldCol = cols.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int endRow = nextHeaderRow >= 0 ? Math.min(nextHeaderRow, sheet.getLastRowNum()) : sheet.getLastRowNum();
        int dataStartRow = headerRow + headerDepth(sheet, headerRow, formatter, evaluator);
        for (int rowIndex = dataStartRow; rowIndex <= endRow; rowIndex++) {
            Cell firstColCell = sheet.getRow(rowIndex) == null ? null : sheet.getRow(rowIndex).getCell(firstFieldCol);
            String firstText = firstColCell == null ? "" : formatter.formatCellValue(firstColCell, evaluator).trim();
            if (isExcludedRow(firstText)) continue;
            Map<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, String> group = new LinkedHashMap<>();
            for (Map.Entry<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, Integer> entry : cols.entrySet()) {
                Cell c = cellAt(sheet, rowIndex, entry.getValue());
                group.put(entry.getKey(), fieldValue(entry.getKey(), c, formatter, evaluator));
            }
            if (isExcludedRow(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.BATCH_NO))
                    || isExcludedRow(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.DOMAIN))) continue;
            ReplayIssueSummaryRow parsed = toRow(group, part);
            if (parsed.isStaticDataRow()) rows.add(parsed);
        }
        return rows;
    }

    /** 从已生成日报的某一区域恢复交易比例合计，供下一份日报滚动继承。 */
    private SummaryRateTotals extractRegionTotals(Sheet sheet, DataFormatter formatter,
                                                   FormulaEvaluator evaluator,
                                                   int headerRow, int nextHeaderRow) {
        Map<ReplayIssueSummaryParser.Field, Integer> cols = locateCols(sheet, headerRow, formatter, evaluator);
        if (cols.isEmpty()) return SummaryRateTotals.EMPTY;
        int firstFieldCol = cols.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int endRow = nextHeaderRow >= 0 ? Math.min(nextHeaderRow, sheet.getLastRowNum()) : sheet.getLastRowNum();
        int dataStartRow = headerRow + headerDepth(sheet, headerRow, formatter, evaluator);
        Integer domainCol = cols.get(ReplayIssueSummaryParser.Field.DOMAIN);
        for (int rowIndex = dataStartRow; rowIndex <= endRow; rowIndex++) {
            String firstText = formattedCell(sheet, rowIndex, firstFieldCol, formatter, evaluator);
            String domainText = domainCol == null ? "" : formattedCell(sheet, rowIndex, domainCol, formatter, evaluator);
            if (!isTotalLabel(firstText) && !isTotalLabel(domainText)) continue;
            return new SummaryRateTotals(
                    parsePercent(rateFieldValue(sheet, rowIndex,
                            cols.get(ReplayIssueSummaryParser.Field.SUCCESS_RATE), formatter, evaluator)),
                    parsePercent(rateFieldValue(sheet, rowIndex,
                            cols.get(ReplayIssueSummaryParser.Field.MATCH_PASS_RATE), formatter, evaluator)));
        }
        return SummaryRateTotals.EMPTY;
    }

    private String formattedField(Sheet sheet, int rowIndex, Integer colIndex,
                                  DataFormatter formatter, FormulaEvaluator evaluator) {
        return colIndex == null ? "" : formattedCell(sheet, rowIndex, colIndex, formatter, evaluator);
    }

    private String formattedCell(Sheet sheet, int rowIndex, int colIndex,
                                 DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = cellAt(sheet, rowIndex, colIndex);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }

    private String rateFieldValue(Sheet sheet, int rowIndex, Integer colIndex,
                                  DataFormatter formatter, FormulaEvaluator evaluator) {
        return colIndex == null ? "" : fieldValue(ReplayIssueSummaryParser.Field.MATCH_PASS_RATE,
                cellAt(sheet, rowIndex, colIndex), formatter, evaluator);
    }

    private String fieldValue(ReplayIssueSummaryParser.Field field, Cell cell,
                              DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        if (field != ReplayIssueSummaryParser.Field.SUCCESS_RATE
                && field != ReplayIssueSummaryParser.Field.MATCH_PASS_RATE) {
            return formatter.formatCellValue(cell, evaluator).trim();
        }
        Double numeric = numericCellValue(cell, evaluator);
        if (numeric == null) return formatter.formatCellValue(cell, evaluator).trim();
        String format = cell.getCellStyle() == null ? null : cell.getCellStyle().getDataFormatString();
        double percent = format != null && format.contains("%") ? numeric * 100.0 : numeric;
        return Double.toString(percent);
    }

    private static Double numericCellValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        if (cell.getCellType() != CellType.FORMULA) return null;
        CellValue evaluated = evaluator.evaluate(cell);
        return evaluated != null && evaluated.getCellType() == CellType.NUMERIC
                ? evaluated.getNumberValue() : null;
    }

    private List<ReplayIssueSummaryRow> extractLegacy(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                                                      int headerRow, int firstFieldCol) {
        // 旧版日报：读整个 sheet，把"批次/领域"列当 upper 读
        Map<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, Integer> cols = locateCols(sheet, headerRow, formatter, evaluator);
        if (cols.isEmpty()) return List.of();
        List<ReplayIssueSummaryRow> rows = new ArrayList<>();
        for (int rowIndex = headerRow + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Cell firstColCell = sheet.getRow(rowIndex) == null ? null : sheet.getRow(rowIndex).getCell(firstFieldCol);
            String firstText = firstColCell == null ? "" : formatter.formatCellValue(firstColCell, evaluator).trim();
            if (isExcludedRow(firstText)) continue;
            Map<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, String> group = new LinkedHashMap<>();
            for (Map.Entry<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, Integer> entry : cols.entrySet()) {
                Cell c = cellAt(sheet, rowIndex, entry.getValue());
                group.put(entry.getKey(), c == null ? "" : formatter.formatCellValue(c, evaluator).trim());
            }
            ReplayIssueSummaryRow parsed = toRow(group, Part.UPPER);
            if (parsed.isStaticDataRow()) rows.add(parsed);
        }
        return rows;
    }

    private Map<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, Integer> locateCols(
            Sheet sheet, int headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, Integer> cols = new LinkedHashMap<>();
        Row row = sheet.getRow(headerRow);
        if (row == null) return cols;
        for (int scanRow = headerRow; scanRow <= headerRow + 1; scanRow++) {
            Row candidate = sheet.getRow(scanRow);
            if (candidate == null) continue;
            for (int colIndex = candidate.getFirstCellNum(); colIndex < candidate.getLastCellNum(); colIndex++) {
                Cell cell = candidate.getCell(colIndex);
                if (cell == null) continue;
                String text = formatter.formatCellValue(cell, evaluator).trim();
                com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field field = matchField(text);
                if (field != null && !cols.containsKey(field)) cols.put(field, colIndex);
            }
        }
        return cols;
    }

    private int headerDepth(Sheet sheet, int headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row child = sheet.getRow(headerRow + 1);
        if (child == null) return 1;
        int matched = 0;
        for (Cell cell : child) {
            if (matchField(formatter.formatCellValue(cell, evaluator).trim()) != null) matched++;
        }
        return matched >= 2 ? 2 : 1;
    }

    private ReplayIssueSummaryRow toRow(Map<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, String> group, Part part) {
        return new ReplayIssueSummaryRow(
                group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.BATCH_NO),
                group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.DOMAIN),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.COVERED_INTERFACE_COUNT)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.SENT_TRANSACTION_COUNT)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.C528_SUCCESS_CCBS_FAIL)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.CCBS_FAILURE_DETAIL)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.C528_FAIL_CCBS_SUCCESS)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.BOTH_FAIL_SAME_CODE)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.BOTH_FAIL_DIFF_CODE)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.BOTH_SUCCESS)),
                parseLong(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.CODE_IGNORED)),
                parsePercent(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.SUCCESS_RATE)),
                parsePercent(group.get(com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field.MATCH_PASS_RATE)),
                part,
                null);
    }

    private com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field matchField(String text) {
        String normalized = normalize(text);
        for (Map.Entry<com.axonlink.ai.replay.service.ReplayIssueSummaryParser.Field, List<String>> entry :
                com.axonlink.ai.replay.service.ReplayIssueSummaryParser.FIELD_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalize(alias).equals(normalized)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)) continue;
            if (character >= 'A' && character <= 'Z') character = (char) (character + ('a' - 'A'));
            result.append(character);
        }
        return result.toString();
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replaceAll("[,\\s]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) return null;
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double parsePercent(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replace("%", "").replaceAll("[,\\s]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Cell cellAt(Sheet sheet, int rowIndex, int colIndex) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(colIndex);
        if (cell != null) return cell;
        // 合并单元格回退
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            org.apache.poi.ss.util.CellRangeAddress region = sheet.getMergedRegion(i);
            if (region.isInRange(rowIndex, colIndex)) {
                Row first = sheet.getRow(region.getFirstRow());
                return first == null ? null : first.getCell(region.getFirstColumn());
            }
        }
        return null;
    }

    private static boolean isExcludedRow(String firstColumnValue) {
        if (firstColumnValue == null) return false;
        String text = firstColumnValue.trim();
        if (text.isEmpty()) return false;
        return text.startsWith("合计") || text.startsWith("总计") || text.startsWith("小计")
                || text.startsWith("问题清单") || text.startsWith("上一批次")
                || text.startsWith("工作问题") || text.startsWith("注意事项")
                || text.startsWith("需求") || text.startsWith("批次号");
    }

    private static boolean isTotalLabel(String value) {
        if (value == null) return false;
        String text = value.trim();
        return text.startsWith("合计") || text.startsWith("总计");
    }

    /** 写日报文件：上半 + 下半 + 实时算的动态列。 */
    private Path writeReport(String currentBatch, LocalDateTime importedAt, String previousBatchNo,
                              List<ReplayIssueSummaryRow> upperRows, List<ReplayIssueSummaryRow> lowerRows,
                              SummaryRateTotals upperTotals, SummaryRateTotals lowerTotals,
                              Path previousReport) {
        Path target = storageRoot.resolve(reportFileName(currentBatch));

        List<DailyReportRow> previousAggregated = previousBatchNo == null ? List.of()
                : aggregate(dao.findDailySlicesByBatch(previousBatchNo));
        List<DailyReportRow> currentAggregated = aggregate(dao.findDailySlicesByBatch(currentBatch));

        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(target)) {
            Sheet sheet = workbook.createSheet("汇总信息");
            ReportStyles styles = createStyles(workbook);
            int nextRow = writeUpperPart(sheet, upperRows, previousAggregated, previousBatchNo, upperTotals, styles);
            writeLowerPart(sheet, nextRow + 2, lowerRows, currentAggregated, previousAggregated,
                    currentBatch, lowerTotals, styles);
            configureSheet(sheet);
            workbook.write(out);
            log.info("[daily-report] 已生成 current={} previous={} 上份日报={} -> {} 上半={}行 下半={}行",
                    currentBatch, previousBatchNo, previousReport, target,
                    upperRows.size(), lowerRows.size());
            return target;
        } catch (IOException e) {
            log.error("[daily-report] 生成失败 current={} previous={}", currentBatch, previousBatchNo, e);
            return null;
        }
    }

    private int writeUpperPart(Sheet sheet, List<ReplayIssueSummaryRow> excelUpper,
                               List<DailyReportRow> previousRows, String previousBatch,
                               SummaryRateTotals sourceTotals,
                               ReportStyles styles) {
        List<String> issueTypes = discoverIssueTypesFromAggregated(previousRows);
        if (issueTypes.isEmpty()) issueTypes = List.of("其他问题");
        int lastCol = 14 + issueTypes.size();
        writeTitle(sheet, 0, lastCol, "批次号：" + displayBatch(previousBatch) + "（上一批次）", styles.upperTitle());
        writeCommonHeader(sheet, 1, lastCol, styles.upperHeader());
        mergeParent(sheet, 1, 4, 10, "交易核对分类统计", styles.upperHeader());
        mergeParent(sheet, 1, 14, 13 + issueTypes.size(), "已解决问题分类统计", styles.issueHeader());
        setVerticalHeader(sheet, 1, 0, "批次", styles.upperHeader());
        setVerticalHeader(sheet, 1, 1, "领域", styles.upperHeader());
        setVerticalHeader(sheet, 1, 2, "覆盖528接口", styles.upperHeader());
        setVerticalHeader(sheet, 1, 3, "发送交易量", styles.upperHeader());
        setVerticalHeader(sheet, 1, 11, "接口成功率", styles.upperHeader());
        setVerticalHeader(sheet, 1, 12, "比对通过率", styles.upperHeader());
        setVerticalHeader(sheet, 1, 13, "问题总数", styles.issueHeader());
        setVerticalHeader(sheet, 1, lastCol, "问题排查进度", styles.issueHeader());
        writeTransactionChildren(sheet, 2, styles.upperHeader());
        for (int i = 0; i < issueTypes.size(); i++) setCell(sheet.getRow(2), 14 + i, issueTypes.get(i), styles.issueHeader());

        if (previousBatch == null || previousBatch.isBlank()) {
            return 3;
        }

        Map<GroupKey, ReplayIssueSummaryRow> staticByKey = new LinkedHashMap<>();
        for (ReplayIssueSummaryRow r : excelUpper) {
            staticByKey.putIfAbsent(groupKey(r.domain()), r);
        }
        Map<GroupKey, DailyReportRow> aggregatedByKey = indexAggregated(previousRows);
        int rowIndex = 3;
        for (Map.Entry<GroupKey, ReplayIssueSummaryRow> entry : staticByKey.entrySet()) {
            GroupKey key = entry.getKey();
            ReplayIssueSummaryRow stat = entry.getValue();
            DailyReportRow agg = aggregatedByKey.get(key);
            Row sheetRow = sheet.createRow(rowIndex++);
            int col = 0;
            setCell(sheetRow, col++, stat.batchNo(), styles.body());
            setCell(sheetRow, col++, displayDomain(key), styles.body());
            col = writeStaticMetrics(sheetRow, col, stat, agg, true, styles);
            setNumber(sheetRow, col++, agg == null ? 0 : agg.totalCount(), styles.issueBody());
            for (String type : issueTypes) {
                setNumber(sheetRow, col++, agg == null ? 0 : issueTypeCount(agg, type, issueTypes), styles.issueBody());
            }
            setPercent(sheetRow, col, agg == null ? 0 : agg.inspectionProgress(), styles.issuePercent());
        }
        writeUpperTotal(sheet, rowIndex, lastCol, staticByKey.values(), aggregatedByKey,
                issueTypes, sourceTotals, true, styles);
        return rowIndex + 1;
    }

    private void writeLowerPart(Sheet sheet, int startRow, List<ReplayIssueSummaryRow> excelLower,
                                List<DailyReportRow> currentRows, List<DailyReportRow> previousRows,
                                String currentBatch, SummaryRateTotals sourceTotals, ReportStyles styles) {
        int lastCol = 16 + UNRESOLVED_STATUS_COLUMNS.size();
        writeTitle(sheet, startRow, lastCol, "批次号：" + currentBatch + "（本批次）", styles.lowerTitle());
        int headerRow = startRow + 1;
        writeCommonHeader(sheet, headerRow, lastCol, styles.lowerHeader());
        mergeParent(sheet, headerRow, 4, 10, "交易核对分类统计", styles.lowerHeader());
        mergeParent(sheet, headerRow, 16, 15 + UNRESOLVED_STATUS_COLUMNS.size(),
                "上一批次未解决问题分类统计", styles.issueHeader());
        setVerticalHeader(sheet, headerRow, 0, "批次", styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 1, "领域", styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 2, "覆盖528接口", styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 3, "发送交易量", styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 11, "接口成功率", styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 12, "比对通过率", styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 13, "问题总数", styles.issueHeader());
        setVerticalHeader(sheet, headerRow, 14, "上一批次未解决问题数量", styles.issueHeader());
        setVerticalHeader(sheet, headerRow, 15, "上轮问题解决率", styles.issueHeader());
        setVerticalHeader(sheet, headerRow, lastCol, "问题解决进度", styles.issueHeader());
        writeTransactionChildren(sheet, headerRow + 1, styles.lowerHeader());
        for (int i = 0; i < UNRESOLVED_STATUS_COLUMNS.size(); i++) {
            setCell(sheet.getRow(headerRow + 1), 16 + i, UNRESOLVED_STATUS_COLUMNS.get(i), styles.issueHeader());
        }
        Map<GroupKey, ReplayIssueSummaryRow> staticByKey = new LinkedHashMap<>();
        for (ReplayIssueSummaryRow r : excelLower) {
            staticByKey.putIfAbsent(groupKey(r.domain()), r);
        }
        Map<GroupKey, DailyReportRow> currentByKey = indexAggregated(currentRows);
        Map<GroupKey, DailyReportRow> previousByKey = indexAggregated(previousRows);
        int rowIndex = headerRow + 2;
        for (Map.Entry<GroupKey, ReplayIssueSummaryRow> entry : staticByKey.entrySet()) {
            GroupKey key = entry.getKey();
            ReplayIssueSummaryRow stat = entry.getValue();
            DailyReportRow current = currentByKey.get(key);
            DailyReportRow previous = previousByKey.get(key);
            Row sheetRow = sheet.createRow(rowIndex++);
            int col = 0;
            setCell(sheetRow, col++, stat.batchNo(), styles.body());
            setCell(sheetRow, col++, displayDomain(key), styles.body());
            col = writeStaticMetrics(sheetRow, col, stat, current, false, styles);
            setNumber(sheetRow, col++, current == null ? 0 : current.totalCount(), styles.issueBody());
            setNumber(sheetRow, col++, previous == null ? 0 : previous.unresolvedCount(), styles.issueBody());
            setPercent(sheetRow, col++, previous == null ? 0 : previous.fixRate(), styles.issuePercent());
            for (String status : UNRESOLVED_STATUS_COLUMNS) {
                setNumber(sheetRow, col++, previous == null ? 0 : previous.unresolvedByStatus().getOrDefault(status, 0L), styles.issueBody());
            }
            setPercent(sheetRow, col, previous == null ? 0 : previous.resolutionProgress(), styles.issuePercent());
        }
        writeLowerTotal(sheet, rowIndex, lastCol, staticByKey.values(), currentByKey, previousByKey,
                sourceTotals, false, styles);
    }

    private int writeStaticMetrics(Row row, int col, ReplayIssueSummaryRow stat,
                                   DailyReportRow batchMetrics, boolean sourceAlreadyAdjusted,
                                   ReportStyles styles) {
        setNullableNumber(row, col++, stat.coveredInterfaceCount(), styles.body());
        setNullableNumber(row, col++, stat.sentTransactionCount(), styles.body());
        setNullableNumber(row, col++, stat.c528SuccessCcbsFail(), styles.body());
        setNullableNumber(row, col++, stat.c528FailCcbsSuccess(), styles.body());
        setNullableNumber(row, col++, stat.bothFailSameCode(), styles.body());
        setNullableNumber(row, col++, stat.bothFailDiffCode(), styles.body());
        setNullableNumber(row, col++, stat.bothSuccess(), styles.body());
        long reasonableDifference = batchMetrics == null ? 0 : batchMetrics.reasonableDifferenceCount();
        setNumber(row, col++, reasonableDifference, styles.body());
        setNullableNumber(row, col++, stat.codeIgnored(), styles.body());
        setPercent(row, col++, calculateSuccessRate(stat, reasonableDifference), styles.percent());
        setPercent(row, col++, calculateMatchPassRate(stat, reasonableDifference, sourceAlreadyAdjusted), styles.percent());
        return col;
    }

    private void writeCommonHeader(Sheet sheet, int headerRow, int lastCol, CellStyle style) {
        Row parent = sheet.createRow(headerRow);
        Row child = sheet.createRow(headerRow + 1);
        parent.setHeightInPoints(28);
        child.setHeightInPoints(38);
        for (int col = 0; col <= lastCol; col++) {
            setCell(parent, col, "", style);
            setCell(child, col, "", style);
        }
    }

    private void writeTransactionChildren(Sheet sheet, int rowIndex, CellStyle style) {
        String[] names = {"528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致",
                "二者均失败响应码不一致", "二者均成功", "合理差异", "响应码忽略"};
        for (int i = 0; i < names.length; i++) setCell(sheet.getRow(rowIndex), 4 + i, names[i], style);
    }

    private void setVerticalHeader(Sheet sheet, int headerRow, int col, String text, CellStyle style) {
        setCell(sheet.getRow(headerRow), col, text, style);
        setCell(sheet.getRow(headerRow + 1), col, "", style);
        sheet.addMergedRegion(new CellRangeAddress(headerRow, headerRow + 1, col, col));
    }

    private void mergeParent(Sheet sheet, int row, int firstCol, int lastCol, String text, CellStyle style) {
        setCell(sheet.getRow(row), firstCol, text, style);
        for (int col = firstCol + 1; col <= lastCol; col++) setCell(sheet.getRow(row), col, "", style);
        if (lastCol > firstCol) sheet.addMergedRegion(new CellRangeAddress(row, row, firstCol, lastCol));
    }

    private void writeTitle(Sheet sheet, int rowIndex, int lastCol, String text, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(26);
        for (int col = 0; col <= lastCol; col++) setCell(row, col, col == 0 ? text : "", style);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, lastCol));
    }

    private void writeUpperTotal(Sheet sheet, int rowIndex, int lastCol,
                                 Iterable<ReplayIssueSummaryRow> staticRows,
                                 Map<GroupKey, DailyReportRow> previousByKey, List<String> issueTypes,
                                 SummaryRateTotals sourceTotals,
                                 boolean sourceAlreadyAdjusted,
                                 ReportStyles styles) {
        Row row = sheet.createRow(rowIndex);
        setCell(row, 0, "", styles.total());
        setCell(row, 1, "合计", styles.total());
        List<ReplayIssueSummaryRow> statList = new ArrayList<>();
        staticRows.forEach(statList::add);
        writeStaticTotals(row, statList, previousByKey, sourceTotals, sourceAlreadyAdjusted, styles);
        long total = previousByKey.values().stream().mapToLong(DailyReportRow::totalCount).sum();
        setNumber(row, 13, total, styles.issueTotal());
        for (int i = 0; i < issueTypes.size(); i++) {
            String type = issueTypes.get(i);
            long count = previousByKey.values().stream().mapToLong(a -> issueTypeCount(a, type, issueTypes)).sum();
            setNumber(row, 14 + i, count, styles.issueTotal());
        }
        long progressed = previousByKey.values().stream().mapToLong(DailyReportRow::progressCount).sum();
        setPercent(row, lastCol, total == 0 ? 0 : progressed * 100.0 / total, styles.issueTotalPercent());
        fillMissingCells(row, lastCol, styles.total());
    }

    private void writeLowerTotal(Sheet sheet, int rowIndex, int lastCol,
                                 Iterable<ReplayIssueSummaryRow> staticRows,
                                 Map<GroupKey, DailyReportRow> currentByKey,
                                 Map<GroupKey, DailyReportRow> previousByKey,
                                 SummaryRateTotals sourceTotals, boolean sourceAlreadyAdjusted,
                                 ReportStyles styles) {
        Row row = sheet.createRow(rowIndex);
        setCell(row, 0, "", styles.total());
        setCell(row, 1, "合计", styles.total());
        List<ReplayIssueSummaryRow> statList = new ArrayList<>();
        staticRows.forEach(statList::add);
        writeStaticTotals(row, statList, currentByKey, sourceTotals, sourceAlreadyAdjusted, styles);
        long currentTotal = currentByKey.values().stream().mapToLong(DailyReportRow::totalCount).sum();
        long previousTotal = previousByKey.values().stream().mapToLong(DailyReportRow::totalCount).sum();
        long fixed = previousByKey.values().stream().mapToLong(DailyReportRow::fixedCount).sum();
        long unresolved = previousByKey.values().stream().mapToLong(DailyReportRow::unresolvedCount).sum();
        setNumber(row, 13, currentTotal, styles.issueTotal());
        setNumber(row, 14, unresolved, styles.issueTotal());
        setPercent(row, 15, previousTotal == 0 ? 0 : fixed * 100.0 / previousTotal, styles.issueTotalPercent());
        for (int i = 0; i < UNRESOLVED_STATUS_COLUMNS.size(); i++) {
            String status = UNRESOLVED_STATUS_COLUMNS.get(i);
            long count = previousByKey.values().stream()
                    .mapToLong(a -> a.unresolvedByStatus().getOrDefault(status, 0L)).sum();
            setNumber(row, 16 + i, count, styles.issueTotal());
        }
        long progress = fixed + previousByKey.values().stream().mapToLong(a ->
                a.unresolvedByStatus().getOrDefault("延后修复", 0L)
                        + a.unresolvedByStatus().getOrDefault("修复待验证", 0L)).sum();
        setPercent(row, lastCol, previousTotal == 0 ? 0 : progress * 100.0 / previousTotal, styles.issueTotalPercent());
        fillMissingCells(row, lastCol, styles.total());
    }

    private void writeStaticTotals(Row row, List<ReplayIssueSummaryRow> rows,
                                   Map<GroupKey, DailyReportRow> batchMetrics,
                                   SummaryRateTotals sourceTotals, boolean sourceAlreadyAdjusted,
                                   ReportStyles styles) {
        List<java.util.function.ToLongFunction<ReplayIssueSummaryRow>> getters = List.of(
                r -> value(r.coveredInterfaceCount()), r -> value(r.sentTransactionCount()),
                r -> value(r.c528SuccessCcbsFail()), r -> value(r.c528FailCcbsSuccess()), r -> value(r.bothFailSameCode()),
                r -> value(r.bothFailDiffCode()), r -> value(r.bothSuccess()));
        for (int i = 0; i < getters.size(); i++) {
            java.util.function.ToLongFunction<ReplayIssueSummaryRow> getter = getters.get(i);
            setNumber(row, 2 + i, rows.stream().mapToLong(getter).sum(), styles.total());
        }
        long reasonableDifference = batchMetrics.values().stream()
                .mapToLong(DailyReportRow::reasonableDifferenceCount).sum();
        setNumber(row, 9, reasonableDifference, styles.total());
        long ignored = rows.stream().mapToLong(r -> value(r.codeIgnored())).sum();
        setNumber(row, 10, ignored, styles.total());
        long sent = rows.stream().mapToLong(r -> value(r.sentTransactionCount())).sum();
        long sameFail = rows.stream().mapToLong(r -> value(r.bothFailSameCode())).sum();
        long bothSuccess = rows.stream().mapToLong(r -> value(r.bothSuccess())).sum();
        long adjustedDenominator = sent - ignored - reasonableDifference;
        double successRate = percentage(sameFail + bothSuccess, adjustedDenominator);
        long noFieldDifference;
        if (sourceTotals != null && sourceTotals.matchPassRate() != null) {
            noFieldDifference = inferNoFieldDifferenceCount(sent, ignored, sameFail, bothSuccess,
                    reasonableDifference, sourceTotals.matchPassRate(), sourceAlreadyAdjusted);
        } else {
            noFieldDifference = rows.stream().mapToLong(stat -> {
                DailyReportRow metrics = batchMetrics.get(groupKey(stat.domain()));
                long rowReasonable = metrics == null ? 0 : metrics.reasonableDifferenceCount();
                return inferNoFieldDifferenceCount(stat, rowReasonable, sourceAlreadyAdjusted);
            }).sum();
        }
        double matchPassRate = percentage(noFieldDifference + sameFail, adjustedDenominator);
        setPercent(row, 11, successRate, styles.totalPercent());
        setPercent(row, 12, matchPassRate, styles.totalPercent());
    }

    private static double calculateSuccessRate(ReplayIssueSummaryRow row, long reasonableDifference) {
        long denominator = value(row.sentTransactionCount()) - value(row.codeIgnored()) - reasonableDifference;
        return percentage(value(row.bothFailSameCode()) + value(row.bothSuccess()), denominator);
    }

    private static double calculateMatchPassRate(ReplayIssueSummaryRow row, long reasonableDifference,
                                                 boolean sourceAlreadyAdjusted) {
        long denominator = value(row.sentTransactionCount()) - value(row.codeIgnored()) - reasonableDifference;
        long noFieldDifference = inferNoFieldDifferenceCount(row, reasonableDifference, sourceAlreadyAdjusted);
        return percentage(noFieldDifference + value(row.bothFailSameCode()), denominator);
    }

    private static long inferNoFieldDifferenceCount(ReplayIssueSummaryRow row, long reasonableDifference,
                                                    boolean sourceAlreadyAdjusted) {
        return inferNoFieldDifferenceCount(value(row.sentTransactionCount()), value(row.codeIgnored()),
                value(row.bothFailSameCode()), value(row.bothSuccess()), reasonableDifference,
                row.matchPassRate(), sourceAlreadyAdjusted);
    }

    private static long inferNoFieldDifferenceCount(long sent, long ignored, long sameFail, long bothSuccess,
                                                    long reasonableDifference, Double sourceMatchPassRate,
                                                    boolean sourceAlreadyAdjusted) {
        long sourceDenominator = sent - ignored - (sourceAlreadyAdjusted ? reasonableDifference : 0);
        if (sourceDenominator <= 0 || sourceMatchPassRate == null) return 0;
        long inferred = Math.round(sourceMatchPassRate / 100.0 * sourceDenominator - sameFail);
        return Math.max(0, Math.min(bothSuccess, inferred));
    }

    private static double percentage(long numerator, long denominator) {
        return denominator <= 0 ? 0 : numerator * 100.0 / denominator;
    }

    private static long value(Long value) { return value == null ? 0 : value; }
    private static double value(Double value) { return value == null ? 0 : value; }

    private static void fillMissingCells(Row row, int lastCol, CellStyle style) {
        for (int col = 0; col <= lastCol; col++) if (row.getCell(col) == null) setCell(row, col, "", style);
    }

    private static long issueTypeCount(DailyReportRow row, String type, List<String> displayedTypes) {
        if (!"其他类型".equals(type)) return row.fixedByIssueType().getOrDefault(type, 0L);
        return row.fixedByIssueType().entrySet().stream()
                .filter(e -> !displayedTypes.contains(e.getKey())).mapToLong(Map.Entry::getValue).sum();
    }

    private static Map<GroupKey, DailyReportRow> indexAggregated(List<DailyReportRow> rows) {
        Map<GroupKey, DailyReportRow> result = new LinkedHashMap<>();
        for (DailyReportRow row : rows) result.put(new GroupKey(normalizeGroup(row.groupName()), row.sandbox()), row);
        return result;
    }

    private static GroupKey groupKey(String domain) {
        return new GroupKey(normalizeGroup(domain), isSandbox(domain));
    }

    private static String normalizeGroup(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.startsWith("沙箱-")) result = result.substring("沙箱-".length());
        else if (result.startsWith("沙箱")) result = result.substring("沙箱".length()).replaceFirst("^[-—_]", "");
        return result.trim();
    }

    private static String displayDomain(GroupKey key) {
        return key.sandbox() ? "沙箱-" + key.domain() : key.domain();
    }

    private static String displayBatch(String batch) {
        return batch == null || batch.isBlank() ? "" : batch;
    }

    private static void configureSheet(Sheet sheet) {
        int[] widths = {24, 18, 14, 15, 18, 18, 22, 24, 15, 14, 15, 14, 14, 13, 18, 18, 14, 14, 14, 14, 14, 16};
        for (int col = 0; col < widths.length; col++) sheet.setColumnWidth(col, widths[col] * 256);
        sheet.createFreezePane(2, 3);
        sheet.setAutobreaks(true);
        sheet.setFitToPage(true);
        sheet.setHorizontallyCenter(true);
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
    }

    private static ReportStyles createStyles(XSSFWorkbook workbook) {
        CellStyle upperTitle = style(workbook, "C6E0B4", true, false, false);
        CellStyle upperHeader = style(workbook, "C6E0B4", true, true, false);
        CellStyle lowerTitle = style(workbook, "F4CCCC", true, false, false);
        CellStyle lowerHeader = style(workbook, "F4CCCC", true, true, false);
        CellStyle issueHeader = style(workbook, "FFF2CC", true, true, false);
        CellStyle body = style(workbook, "FFFFFF", false, true, false);
        CellStyle percent = style(workbook, "FFFFFF", false, true, true);
        CellStyle issueBody = style(workbook, "FFF9E6", false, true, false);
        CellStyle issuePercent = style(workbook, "FFF9E6", false, true, true);
        CellStyle total = style(workbook, "E2F0D9", true, true, false);
        CellStyle totalPercent = style(workbook, "E2F0D9", true, true, true);
        CellStyle issueTotal = style(workbook, "FFF2CC", true, true, false);
        CellStyle issueTotalPercent = style(workbook, "FFF2CC", true, true, true);
        return new ReportStyles(upperTitle, upperHeader, lowerTitle, lowerHeader, issueHeader, body,
                percent, issueBody, issuePercent, total, totalPercent, issueTotal, issueTotalPercent);
    }

    private static CellStyle style(XSSFWorkbook workbook, String rgb, boolean bold, boolean bordered, boolean percent) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(Color.decode("#" + rgb), new DefaultIndexedColorMap()));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        if (bordered) {
            style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
        }
        if (percent) style.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
        Font font = workbook.createFont();
        font.setBold(bold);
        style.setFont(font);
        return style;
    }

    private static void setCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void setNumber(Row row, int col, double value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void setNullableNumber(Row row, int col, Long value, CellStyle style) {
        if (value == null) setCell(row, col, "", style); else setNumber(row, col, value, style);
    }

    private static void setPercent(Row row, int col, double percent, CellStyle style) {
        setNumber(row, col, percent / 100.0, style);
    }

    private static void setNullablePercent(Row row, int col, Double percent, CellStyle style) {
        if (percent == null) setCell(row, col, "", style); else setPercent(row, col, percent, style);
    }

    private record ReportStyles(CellStyle upperTitle, CellStyle upperHeader, CellStyle lowerTitle,
                                CellStyle lowerHeader, CellStyle issueHeader, CellStyle body,
                                CellStyle percent, CellStyle issueBody, CellStyle issuePercent,
                                CellStyle total, CellStyle totalPercent,
                                CellStyle issueTotal, CellStyle issueTotalPercent) {}

    /** 从 domain 字符串推断是否沙箱（domain 含"沙箱-"前缀视为沙箱）。 */
    private static boolean isSandbox(String domain) {
        return domain != null && domain.contains("沙箱");
    }

    private static List<String> discoverIssueTypesFromAggregated(List<DailyReportRow> rows) {
        TreeSet<String> types = new TreeSet<>();
        for (DailyReportRow row : rows) {
            types.addAll(row.allByIssueType().keySet());
        }
        boolean hasOther = types.remove("其他问题");
        List<String> result = new ArrayList<>(types);
        int totalSize = result.size() + (hasOther ? 1 : 0);
        if (totalSize > TYPE_COLUMN_CAP) {
            int regularLimit = TYPE_COLUMN_CAP - 1 - (hasOther ? 1 : 0);
            List<String> capped = new ArrayList<>(result.subList(0, regularLimit));
            capped.add("其他类型");
            if (hasOther) capped.add("其他问题");
            return capped;
        }
        if (hasOther) result.add("其他问题");
        return result;
    }

    private static List<DailyReportRow> aggregate(List<DailyIssueSlice> slices) {
        // 按 (occurrence group_name, is_sandbox) 聚合，与 GroupKey(domain,sandbox) 不同维度，
        // 不使用 GroupKey 以避免 Excel domain 与 occurrence group_name 混淆
        Map<String, List<DailyIssueSlice>> grouped = new LinkedHashMap<>();
        for (DailyIssueSlice slice : slices) {
            String key = slice.groupName() + "|" + slice.sandbox();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(slice);
        }
        List<DailyReportRow> result = new ArrayList<>();
        for (Map.Entry<String, List<DailyIssueSlice>> entry : grouped.entrySet()) {
            DailyIssueSlice first = entry.getValue().get(0);
            result.add(DailyReportRow.aggregate(first.groupName(), first.sandbox(), entry.getValue()));
        }
        return result;
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) return "report";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 落盘根目录。 */
    public Path getStorageRoot() {
        return storageRoot;
    }

    /** 给定 batch 解析日报文件路径（不一定存在）。 */
    public Path locateReport(String batchName) {
        if (batchName == null || batchName.isBlank()) return null;
        return storageRoot.resolve(reportFileName(batchName));
    }

    private static String reportFileName(String batchName) {
        return safeFileName(batchName) + "日报.xlsx";
    }

    /** 按 (Excel domain, isSandbox) 分组的键（日报行键）。 */
    private record GroupKey(String domain, boolean sandbox) {
    }
}
