package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow;
import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow.Part;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析回放清单 Excel 的「汇总信息」页签。
 *
 * <p>支持两种布局：
 * <ul>
 *   <li><b>横排</b>（多行数据）：表头在一行（多个 fields 在同一行），数据行从 header+1 到下一个表头/末尾。</li>
 *   <li><b>竖排</b>（单组数据）：字段名在某一列（如 A 列），值在字段名右侧（同一行下一列）。</li>
 * </ul>
 *
 * <p>「汇总信息」页签通常包含两段：
 * <ul>
 *   <li><b>上半部分</b>（上一批次统计）：特征列含"问题排查进度"或"按问题类型分类"；</li>
 *   <li><b>下半部分</b>（本批次统计）：特征列含"问题解决进度"且不含"排查"。</li>
 * </ul>
 *
 * <p>支持合并单元格；末尾"合计/问题清单/上一批次..."等说明行被自动跳过。
 * 找不到「汇总信息」页签时返回空结果（向后兼容旧工作簿）。
 */
@Service
public class ReplayIssueSummaryParser {

    static final String SHEET_NAME = "汇总信息";

    /** 字段别名表：业务字段可匹配多个表头写法（归一化后精确匹配）。 */
    public static final Map<Field, List<String>> FIELD_ALIASES = Map.ofEntries(
            Map.entry(Field.BATCH_NO, List.of("批次", "批次号", "回放批次")),
            Map.entry(Field.DOMAIN, List.of("领域", "业务领域")),
            Map.entry(Field.COVERED_INTERFACE_COUNT,
                    List.of("覆盖528接口", "覆盖528", "覆盖接口数", "覆盖接口", "覆盖接口数量")),
            Map.entry(Field.SENT_TRANSACTION_COUNT, List.of("发送交易量", "发送量", "交易量", "发送交易笔数")),
            Map.entry(Field.C528_SUCCESS_CCBS_FAIL,
                    List.of("528成功/CCBS失败", "528成功/ccbs失败", "528成功ccbs失败",
                            "528成功/CCBS失败(本批笔数/总笔数)",
                            "528成功/ccbs失败(本批笔数/总笔数)")),
            Map.entry(Field.CCBS_FAILURE_DETAIL, List.of("CCBS失败明细", "ccbs失败明细")),
            Map.entry(Field.C528_FAIL_CCBS_SUCCESS,
                    List.of("528失败/CCBS成功", "528失败/ccbs成功", "528失败ccbs成功")),
            Map.entry(Field.BOTH_FAIL_SAME_CODE, List.of("二者均失败响应码一致", "均失败响应码一致")),
            Map.entry(Field.BOTH_FAIL_DIFF_CODE, List.of("二者均失败响应码不一致", "均失败响应码不一致")),
            Map.entry(Field.BOTH_SUCCESS, List.of("二者均成功", "均成功")),
            Map.entry(Field.CODE_IGNORED, List.of("响应码忽略", "忽略")),
            Map.entry(Field.SUCCESS_RATE, List.of("接口成功率", "成功率")),
            Map.entry(Field.MATCH_PASS_RATE, List.of("比对通过率", "比对一致率")));

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 汇总区域合计行中的两个交易比例；空值由日报生成层按领域明细加权回退。 */
    public record SummaryRateTotals(Double successRate, Double matchPassRate) {
        public static final SummaryRateTotals EMPTY = new SummaryRateTotals(null, null);
    }

    /** 解析结果：上半部分行 + 下半部分行 + 页签是否存在 + 两区交易比例合计。 */
    public record ParsedSummary(List<ReplayIssueSummaryRow> upperRows,
                                 List<ReplayIssueSummaryRow> lowerRows,
                                 boolean sheetFound,
                                 SummaryRateTotals upperTotals,
                                 SummaryRateTotals lowerTotals) {
        public ParsedSummary(List<ReplayIssueSummaryRow> upperRows,
                             List<ReplayIssueSummaryRow> lowerRows,
                             boolean sheetFound) {
            this(upperRows, lowerRows, sheetFound, SummaryRateTotals.EMPTY, SummaryRateTotals.EMPTY);
        }
    }

    public ParsedSummary parse(MultipartFile file) throws IOException {
        return parse(file, ReplayIssueImportMode.QUERY);
    }

    public ParsedSummary parse(MultipartFile file, ReplayIssueImportMode mode) throws IOException {
        ReplayIssueImportMode effectiveMode = mode == null ? ReplayIssueImportMode.QUERY : mode;
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                return new ParsedSummary(List.of(), List.of(), false);
            }
            return normalizeBatches(parseSheet(sheet), effectiveMode);
        }
    }

    private ParsedSummary normalizeBatches(ParsedSummary summary, ReplayIssueImportMode mode) {
        return new ParsedSummary(
                summary.upperRows().stream().map(row -> normalizeBatch(row, mode)).toList(),
                summary.lowerRows().stream().map(row -> normalizeBatch(row, mode)).toList(),
                summary.sheetFound(), summary.upperTotals(), summary.lowerTotals());
    }

    private ReplayIssueSummaryRow normalizeBatch(ReplayIssueSummaryRow row, ReplayIssueImportMode mode) {
        return new ReplayIssueSummaryRow(mode.normalizeBatch(row.batchNo()), row.domain(),
                row.coveredInterfaceCount(), row.sentTransactionCount(), row.c528SuccessCcbsFail(),
                row.ccbsFailureDetail(), row.c528FailCcbsSuccess(), row.bothFailSameCode(),
                row.bothFailDiffCode(), row.bothSuccess(), row.codeIgnored(), row.successRate(),
                row.matchPassRate(), row.part(), row.rawJson());
    }

    private ParsedSummary parseSheet(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

        // 收集所有非空单元格的 (row, col) → text
        Map<String, String> rawCells = new LinkedHashMap<>();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int colIndex = row.getFirstCellNum(); colIndex < row.getLastCellNum(); colIndex++) {
                Cell cell = row.getCell(colIndex);
                if (cell == null) continue;
                String text = formatter.formatCellValue(cell, evaluator).trim();
                if (!text.isEmpty()) {
                    rawCells.put("R" + rowIndex + "C" + colIndex, text);
                }
            }
        }

        // 检测布局：横排（多 fields 同一行）vs 竖排（多 fields 同一列）
        Layout layout = detectLayout(sheet, formatter, evaluator);
        List<ReplayIssueSummaryRow> upperRows;
        List<ReplayIssueSummaryRow> lowerRows;
        SummaryRateTotals upperTotals = SummaryRateTotals.EMPTY;
        SummaryRateTotals lowerTotals = SummaryRateTotals.EMPTY;
        if (layout == Layout.HORIZONTAL) {
            HorizontalSections sections = scanHorizontal(sheet, formatter, evaluator);
            upperRows = extractHorizontalSection(sheet, formatter, evaluator, sections.upper, Part.UPPER);
            lowerRows = extractHorizontalSection(sheet, formatter, evaluator, sections.lower, Part.LOWER);
            upperTotals = extractHorizontalTotals(sheet, formatter, evaluator, sections.upper);
            lowerTotals = extractHorizontalTotals(sheet, formatter, evaluator, sections.lower);
        } else {
            VerticalSections sections = scanVertical(sheet, formatter, evaluator);
            upperRows = extractVerticalSection(sheet, formatter, evaluator, sections.upper, Part.UPPER);
            lowerRows = extractVerticalSection(sheet, formatter, evaluator, sections.lower, Part.LOWER);
        }

        String rawJson = toJson(rawCells);
        upperRows = upperRows.stream().map(r -> withRaw(r, rawJson)).toList();
        lowerRows = lowerRows.stream().map(r -> withRaw(r, rawJson)).toList();
        return new ParsedSummary(upperRows, lowerRows, true, upperTotals, lowerTotals);
    }

    private enum Layout { HORIZONTAL, VERTICAL }

    /** 布局检测：找含最多 fields 的行/列，谁多就选谁。 */
    private Layout detectLayout(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<Integer, Integer> fieldCountByRow = new LinkedHashMap<>();
        Map<Integer, Integer> fieldCountByCol = new LinkedHashMap<>();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<Field, Integer> rowFields = locateColsForRow(row, formatter, evaluator);
            if (!rowFields.isEmpty()) fieldCountByRow.put(rowIndex, rowFields.size());
            for (int colIndex = row.getFirstCellNum(); colIndex < row.getLastCellNum(); colIndex++) {
                Cell cell = row.getCell(colIndex);
                if (cell == null) continue;
                String text = formatter.formatCellValue(cell, evaluator).trim();
                if (matchField(text) != null) {
                    fieldCountByCol.merge(colIndex, 1, Integer::sum);
                }
            }
        }
        int maxRowFields = fieldCountByRow.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int maxColFields = fieldCountByCol.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return maxRowFields >= maxColFields ? Layout.HORIZONTAL : Layout.VERTICAL;
    }

    /** 横排扫描：找上半/下半表头行。 */
    private record HorizontalSections(HeaderRegion upper, HeaderRegion lower) {
    }

    private HorizontalSections scanHorizontal(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<HeaderRegion> candidates = new ArrayList<>();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<Field, Integer> cols = locateColsForRow(row, formatter, evaluator);
            Map<Field, Integer> childCols = locateColsForRow(sheet.getRow(rowIndex + 1), formatter, evaluator);
            Map<Field, Integer> combined = new LinkedHashMap<>(cols);
            childCols.forEach(combined::putIfAbsent);
            // 横排区段的当前行必须包含批次/领域等主表头。
            // 不能用 combined 判断，否则“批次标题行 + 下一行父表头”会被误识别成一个区段。
            boolean anchored = cols.containsKey(Field.BATCH_NO) || cols.containsKey(Field.DOMAIN);
            if (!anchored || combined.size() < 3 || !containsInspectionField(combined)) continue;
            int depth = childCols.keySet().stream().anyMatch(field -> !cols.containsKey(field)) ? 2 : 1;
            candidates.add(new HeaderRegion(rowIndex, rowIndex + depth, -1,
                    combined.values().stream().mapToInt(Integer::intValue).min().orElse(0), combined));
        }
        // 取前两个：一个上半 + 一个下半（按顺序）
        HeaderRegion upper = candidates.isEmpty() ? null : candidates.get(0);
        HeaderRegion lower = candidates.size() >= 2 ? candidates.get(1) : null;
        // 设置 nextHeaderRow
        if (upper != null && lower != null) {
            upper = new HeaderRegion(upper.headerRow, upper.dataStartRow, lower.headerRow,
                    upper.firstFieldCol, upper.cols);
        }
        return new HorizontalSections(upper, lower);
    }

    /** 竖排扫描：找上半/下半字段名列（A 列内连续含 fields 的行段）。 */
    private record VerticalSections(VerticalSection upper, VerticalSection lower) {
    }

    private record VerticalSection(int fieldCol, int startRow, int endRow, Map<Field, Integer> cols) {
    }

    private VerticalSections scanVertical(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        // 竖排：每行只含 1 个 field label，字段名列 = 含 fields 的列
        // 简化：找"连续 N 行（>=3）每行含 1 个 field"的列
        Map<Integer, Integer> continuousByCol = new LinkedHashMap<>();
        Map<Integer, Integer> bestCols = new LinkedHashMap<>(); // col -> (startRow, endRow) 连续段
        for (int col = 0; col <= 20; col++) {
            int startRow = -1, endRow = -1, count = 0;
            for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                Cell cell = row.getCell(col);
                if (cell == null) continue;
                String text = formatter.formatCellValue(cell, evaluator).trim();
                Field field = matchField(text);
                if (field != null) {
                    if (startRow < 0) startRow = rowIndex;
                    endRow = rowIndex;
                    count++;
                } else {
                    if (count >= 3) break; // 找到一段就够
                    startRow = -1;
                    endRow = -1;
                    count = 0;
                }
            }
            if (count >= 3) {
                continuousByCol.put(col, count);
                bestCols.put(col, count);
            }
        }
        // 取第一个合格列作为字段名列
        int fieldCol = continuousByCol.keySet().stream().findFirst().orElse(-1);
        if (fieldCol < 0) {
            return new VerticalSections(null, null);
        }
        // 提取该列所有 field → col+1 为值列
        Map<Field, Integer> cols = new LinkedHashMap<>();
        int startRow = -1, endRow = -1;
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Cell cell = row.getCell(fieldCol);
            if (cell == null) continue;
            String text = formatter.formatCellValue(cell, evaluator).trim();
            Field field = matchField(text);
            if (field != null && !cols.containsKey(field)) {
                cols.put(field, fieldCol + 1);
                if (startRow < 0) startRow = rowIndex;
                endRow = rowIndex;
            }
        }
        // 竖排只有一个区段（单组），上下半都指向同一段
        VerticalSection section = new VerticalSection(fieldCol, startRow, endRow, cols);
        // 尝试找第二个字段列段（如果有，视为下半）——简化：返回同一段
        return new VerticalSections(section, null);
    }

    /** 提取横排一段。 */
    private List<ReplayIssueSummaryRow> extractHorizontalSection(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                                                                  HeaderRegion region, Part part) {
        if (region == null) return List.of();
        List<ReplayIssueSummaryRow> rows = new ArrayList<>();
        int endRow = region.nextHeaderRow >= 0 ? Math.min(region.nextHeaderRow - 1, sheet.getLastRowNum()) : sheet.getLastRowNum();
        for (int rowIndex = region.dataStartRow; rowIndex <= endRow; rowIndex++) {
            Cell firstColCell = cellAt(sheet, rowIndex, region.firstFieldCol);
            String firstText = firstColCell == null ? "" : formatter.formatCellValue(firstColCell, evaluator).trim();
            if (isExcludedRow(firstText)) continue;
            Map<Field, String> group = new LinkedHashMap<>();
            for (Map.Entry<Field, Integer> entry : region.cols.entrySet()) {
                Cell c = cellAt(sheet, rowIndex, entry.getValue());
                group.put(entry.getKey(), fieldValue(entry.getKey(), c, formatter, evaluator));
            }
            if (isExcludedRow(group.get(Field.BATCH_NO)) || isExcludedRow(group.get(Field.DOMAIN))) continue;
            ReplayIssueSummaryRow row = toRow(group, part);
            if (row.isStaticDataRow()) rows.add(row);
        }
        return rows;
    }

    /** 合计行不进入领域明细，但其两个交易比例需要原样传给日报生成层。 */
    private SummaryRateTotals extractHorizontalTotals(Sheet sheet, DataFormatter formatter,
                                                       FormulaEvaluator evaluator, HeaderRegion region) {
        if (region == null) return SummaryRateTotals.EMPTY;
        int endRow = region.nextHeaderRow >= 0
                ? Math.min(region.nextHeaderRow - 1, sheet.getLastRowNum())
                : sheet.getLastRowNum();
        Integer domainCol = region.cols.get(Field.DOMAIN);
        for (int rowIndex = region.dataStartRow; rowIndex <= endRow; rowIndex++) {
            String firstText = formattedCell(sheet, rowIndex, region.firstFieldCol, formatter, evaluator);
            String domainText = domainCol == null ? "" : formattedCell(sheet, rowIndex, domainCol, formatter, evaluator);
            if (!isTotalLabel(firstText) && !isTotalLabel(domainText)) continue;
            return new SummaryRateTotals(
                    parsePercent(rateFieldValue(sheet, rowIndex, region.cols.get(Field.SUCCESS_RATE), formatter, evaluator)),
                    parsePercent(rateFieldValue(sheet, rowIndex, region.cols.get(Field.MATCH_PASS_RATE), formatter, evaluator)));
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

    /** 提取竖排一段（每个数据列是一组）。 */
    private List<ReplayIssueSummaryRow> extractVerticalSection(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                                                                 VerticalSection section, Part part) {
        if (section == null) return List.of();
        List<ReplayIssueSummaryRow> rows = new ArrayList<>();
        int maxCol = maxColumnIndex(sheet);
        // 竖排：每个数据列是一组（值在字段名的右侧 cell）
        for (int col = section.fieldCol + 1; col <= maxCol; col++) {
            Map<Field, String> group = new LinkedHashMap<>();
            for (int rowIndex = section.startRow; rowIndex <= section.endRow; rowIndex++) {
                Cell labelCell = cellAt(sheet, rowIndex, section.fieldCol);
                if (labelCell == null) continue;
                String labelText = formatter.formatCellValue(labelCell, evaluator).trim();
                Field field = matchField(labelText);
                if (field == null) continue;
                Cell valueCell = cellAt(sheet, rowIndex, col);
                String value = fieldValue(field, valueCell, formatter, evaluator);
                group.put(field, value);
            }
            ReplayIssueSummaryRow row = toRow(group, part);
            if (row.isStaticDataRow()) rows.add(row);
        }
        return rows;
    }

    private static int maxColumnIndex(Sheet sheet) {
        int max = -1;
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) max = Math.max(max, row.getLastCellNum() - 1);
        }
        return max;
    }

    /** 找一行内匹配的所有 fields（key=colIndex, value=Field）。 */
    private Map<Field, Integer> locateColsForRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<Field, Integer> cols = new LinkedHashMap<>();
        if (row == null) return cols;
        for (int colIndex = row.getFirstCellNum(); colIndex < row.getLastCellNum(); colIndex++) {
            Cell cell = row.getCell(colIndex);
            if (cell == null) continue;
            String text = formatter.formatCellValue(cell, evaluator).trim();
            Field field = matchField(text);
            if (field != null && !cols.containsKey(field)) {
                cols.put(field, colIndex);
            }
        }
        return cols;
    }

    private static boolean containsInspectionField(Map<Field, Integer> cols) {
        return cols.containsKey(Field.COVERED_INTERFACE_COUNT)
                || cols.containsKey(Field.SENT_TRANSACTION_COUNT)
                || cols.containsKey(Field.C528_SUCCESS_CCBS_FAIL);
    }

    private static boolean containsResolutionField(Map<Field, Integer> cols) {
        return cols.containsKey(Field.COVERED_INTERFACE_COUNT)
                || cols.containsKey(Field.C528_SUCCESS_CCBS_FAIL)
                || cols.containsKey(Field.SUCCESS_RATE);
    }

    /** 横排区段：表头行 + 下一表头行 + 第一字段列 + 该行的字段映射。 */
    private record HeaderRegion(int headerRow, int dataStartRow, int nextHeaderRow,
                                int firstFieldCol, Map<Field, Integer> cols) {
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

    private ReplayIssueSummaryRow toRow(Map<Field, String> group, Part part) {
        return new ReplayIssueSummaryRow(
                group.get(Field.BATCH_NO),
                group.get(Field.DOMAIN),
                parseLong(group.get(Field.COVERED_INTERFACE_COUNT)),
                parseLong(group.get(Field.SENT_TRANSACTION_COUNT)),
                parseLong(group.get(Field.C528_SUCCESS_CCBS_FAIL)),
                parseLong(group.get(Field.CCBS_FAILURE_DETAIL)),
                parseLong(group.get(Field.C528_FAIL_CCBS_SUCCESS)),
                parseLong(group.get(Field.BOTH_FAIL_SAME_CODE)),
                parseLong(group.get(Field.BOTH_FAIL_DIFF_CODE)),
                parseLong(group.get(Field.BOTH_SUCCESS)),
                parseLong(group.get(Field.CODE_IGNORED)),
                parsePercent(group.get(Field.SUCCESS_RATE)),
                parsePercent(group.get(Field.MATCH_PASS_RATE)),
                part,
                null);
    }

    private static Field matchField(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return null;
        for (Map.Entry<Field, List<String>> entry : FIELD_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalize(alias).equals(normalized)) return entry.getKey();
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

    private String rateFieldValue(Sheet sheet, int rowIndex, Integer colIndex,
                                  DataFormatter formatter, FormulaEvaluator evaluator) {
        return colIndex == null ? "" : fieldValue(Field.MATCH_PASS_RATE,
                cellAt(sheet, rowIndex, colIndex), formatter, evaluator);
    }

    /**
     * 百分比数值单元格直接读取底层 double，避免 DataFormatter 按 0.00% 格式截断精度。
     * 返回值仍使用 0～100 的百分数口径，与字符串百分比解析保持一致。
     */
    private String fieldValue(Field field, Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        if (field != Field.SUCCESS_RATE && field != Field.MATCH_PASS_RATE) {
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

    /**
     * 读取单元格值；对合并单元格的非左上角位置返回左上角的值。
     */
    private Cell cellAt(Sheet sheet, int rowIndex, int colIndex) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(colIndex);
        if (cell != null) return cell;
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress region = sheet.getMergedRegion(i);
            if (region.isInRange(rowIndex, colIndex)) {
                Row first = sheet.getRow(region.getFirstRow());
                return first == null ? null : first.getCell(region.getFirstColumn());
            }
        }
        return null;
    }

    private String toJson(Map<String, String> rawCells) {
        try {
            return objectMapper.writeValueAsString(rawCells);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ReplayIssueSummaryRow withRaw(ReplayIssueSummaryRow row, String rawJson) {
        return new ReplayIssueSummaryRow(row.batchNo(), row.domain(), row.coveredInterfaceCount(),
                row.sentTransactionCount(), row.c528SuccessCcbsFail(), row.ccbsFailureDetail(),
                row.c528FailCcbsSuccess(), row.bothFailSameCode(), row.bothFailDiffCode(), row.bothSuccess(),
                row.codeIgnored(), row.successRate(), row.matchPassRate(), row.part(), rawJson);
    }

    /** 业务字段枚举。 */
    public enum Field {
        BATCH_NO, DOMAIN, COVERED_INTERFACE_COUNT, SENT_TRANSACTION_COUNT,
        C528_SUCCESS_CCBS_FAIL, CCBS_FAILURE_DETAIL, C528_FAIL_CCBS_SUCCESS,
        BOTH_FAIL_SAME_CODE, BOTH_FAIL_DIFF_CODE, BOTH_SUCCESS, CODE_IGNORED,
        SUCCESS_RATE, MATCH_PASS_RATE
    }
}
