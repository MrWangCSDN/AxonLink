package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ReplayDailyWorkbookParser {

    static final String SUMMARY_SHEET = "汇总信息";
    static final String INTERFACE_SHEET = "接口比对明细";
    static final String COVERAGE_SHEET = "回放交易覆盖情况";

    private static final List<String> REQUIRED_SHEETS = List.of(SUMMARY_SHEET, INTERFACE_SHEET, COVERAGE_SHEET);
    private static final List<String> SUMMARY_HEADERS = List.of("批次", "领域", "覆盖528接口", "发送交易量",
            "二者均失败响应码一致", "二者均成功", "响应码忽略", "比对通过率", "问题总数");
    private static final List<String> INTERFACE_HEADERS = List.of("批次号", "交易码", "S码", "交易描述", "开发负责人",
            "行内负责人", "领域", "发送交易量", "528成功/CCBS失败", "528失败/CCBS成功",
            "二者均失败响应码一致", "二者均失败响应码不一致", "二者均成功", "响应码忽略", "交易成功率",
            "接口比对通过率", "528平均耗时", "CCBS平均耗时");
    private static final List<String> COVERAGE_SUMMARY_HEADERS = List.of("业务领域汇总", "全量交易数", "本次已发送",
            "本次未发送", "不回放", "近期无交易", "待分析", "覆盖率");
    private static final List<String> COVERAGE_DETAIL_HEADERS = List.of("交易码", "交易描述", "业务领域", "S码", "关联码",
            "是否需要回放", "最近交易日期", "本次发送交易量", "覆盖状态", "未发送原因", "开发负责人", "行内负责人");
    private static final Set<String> COVERAGE_DECORATION_LABELS = Set.of(
            "按业务领域汇总", "按大组汇总", "交易明细", "交易基本信息", "回放覆盖信息", "责任人");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT));

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReplayDailyWorkbookData parse(MultipartFile file, ReplayIssueImportMode mode) throws IOException {
        ReplayIssueImportMode effectiveMode = mode == null ? ReplayIssueImportMode.QUERY : mode;
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            return parse(workbook, effectiveMode);
        }
    }

    public Optional<ReplayDailyWorkbookData> parseIfDailySheetsPresent(MultipartFile file,
                                                                        ReplayIssueImportMode mode) throws IOException {
        ReplayIssueImportMode effectiveMode = mode == null ? ReplayIssueImportMode.QUERY : mode;
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            boolean hasInterfaceSheet = workbook.getSheet(INTERFACE_SHEET) != null;
            boolean hasCoverageSheet = workbook.getSheet(COVERAGE_SHEET) != null;
            if (!hasInterfaceSheet && !hasCoverageSheet) {
                return Optional.empty();
            }
            if (hasInterfaceSheet != hasCoverageSheet) {
                throw new IllegalArgumentException("日报页签“" + INTERFACE_SHEET + "”与“"
                        + COVERAGE_SHEET + "”必须同时存在或同时缺失");
            }
            return Optional.of(parse(workbook, effectiveMode));
        }
    }

    private ReplayDailyWorkbookData parse(Workbook workbook, ReplayIssueImportMode mode) {
        validateRequiredSheets(workbook);
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<ReplayDailySummaryRow> summaries = parseSummaries(
                workbook.getSheet(SUMMARY_SHEET), formatter, evaluator, mode);
        String batch = summaries.get(0).batchNo();
        List<ReplayInterfaceComparisonRow> comparisons = parseComparisons(
                workbook.getSheet(INTERFACE_SHEET), formatter, evaluator, mode, batch);
        CoverageData coverage = parseCoverage(workbook.getSheet(COVERAGE_SHEET), formatter, evaluator, batch);
        return new ReplayDailyWorkbookData(batch, summaries, comparisons,
                coverage.summaries(), coverage.details());
    }

    private void validateRequiredSheets(Workbook workbook) {
        List<String> missing = REQUIRED_SHEETS.stream().filter(name -> workbook.getSheet(name) == null).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("缺少日报页签：" + String.join("、", missing));
        }
    }

    private List<ReplayDailySummaryRow> parseSummaries(Sheet sheet, DataFormatter formatter,
                                                        FormulaEvaluator evaluator, ReplayIssueImportMode mode) {
        List<HeaderRegion> regions = findSummaryRegions(sheet, formatter, evaluator);
        if (regions.size() < 2) {
            throw new IllegalArgumentException("页签“汇总信息”未找到下半部分批次数据");
        }
        HeaderRegion lower = regions.get(regions.size() - 1);
        int endRow = sheet.getLastRowNum();
        List<ReplayDailySummaryRow> result = new ArrayList<>();
        String canonicalBatch = null;
        for (int rowIndex = lower.dataStartRow(); rowIndex <= endRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlankRow(row, formatter, evaluator)) {
                continue;
            }
            String batchValue = value(sheet, rowIndex, lower.columns().get("批次"), formatter, evaluator);
            String domain = value(sheet, rowIndex, lower.columns().get("领域"), formatter, evaluator);
            if (isTotal(batchValue) || isTotal(domain)) {
                continue;
            }
            if (domain.isBlank()) {
                if (!result.isEmpty()) {
                    break;
                }
                continue;
            }
            String normalizedBatch = mode.normalizeBatch(batchValue);
            if (normalizedBatch == null || normalizedBatch.isBlank()) {
                throw invalid(SUMMARY_SHEET, rowIndex, "批次", batchValue);
            }
            if (canonicalBatch == null) {
                canonicalBatch = normalizedBatch;
            } else if (!canonicalBatch.equals(normalizedBatch)) {
                throw new IllegalArgumentException("页签“汇总信息”第" + (rowIndex + 1) + "行批次不一致：" + normalizedBatch);
            }
            Map<String, String> raw = rowValues(sheet, rowIndex, lower.columns(), formatter, evaluator);
            result.add(new ReplayDailySummaryRow(normalizedBatch, domain,
                    count(raw.get("覆盖528接口"), SUMMARY_SHEET, rowIndex, "覆盖528接口"),
                    count(raw.get("发送交易量"), SUMMARY_SHEET, rowIndex, "发送交易量"),
                    count(raw.get("二者均失败响应码一致"), SUMMARY_SHEET, rowIndex, "二者均失败响应码一致"),
                    count(raw.get("二者均成功"), SUMMARY_SHEET, rowIndex, "二者均成功"),
                    count(raw.get("响应码忽略"), SUMMARY_SHEET, rowIndex, "响应码忽略"),
                    rate(raw.get("比对通过率"), SUMMARY_SHEET, rowIndex, "比对通过率"),
                    count(raw.get("问题总数"), SUMMARY_SHEET, rowIndex, "问题总数"),
                    rowIndex + 1, json(raw)));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("页签“汇总信息”下半部分没有可导入数据");
        }
        return List.copyOf(result);
    }

    private List<HeaderRegion> findSummaryRegions(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<HeaderRegion> result = new ArrayList<>();
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Map<String, Integer> first = headers(sheet.getRow(rowIndex), SUMMARY_HEADERS, formatter, evaluator);
            Map<String, Integer> second = headers(sheet.getRow(rowIndex + 1), SUMMARY_HEADERS, formatter, evaluator);
            Map<String, Integer> combined = new LinkedHashMap<>(first);
            second.forEach(combined::putIfAbsent);
            if (first.containsKey("批次") && first.containsKey("领域") && combined.keySet().containsAll(SUMMARY_HEADERS)) {
                int depth = combined.size() > first.size() ? 2 : 1;
                result.add(new HeaderRegion(rowIndex, rowIndex + depth, combined));
            }
        }
        return result;
    }

    private List<ReplayInterfaceComparisonRow> parseComparisons(Sheet sheet, DataFormatter formatter,
                                                                 FormulaEvaluator evaluator, ReplayIssueImportMode mode,
                                                                 String canonicalBatch) {
        HeaderRegion header = findHeader(sheet, INTERFACE_HEADERS, formatter, evaluator, INTERFACE_SHEET);
        List<ReplayInterfaceComparisonRow> result = new ArrayList<>();
        for (int rowIndex = header.dataStartRow(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlankRow(row, formatter, evaluator)) {
                continue;
            }
            Map<String, String> raw = rowValues(sheet, rowIndex, header.columns(), formatter, evaluator);
            boolean total = raw.values().stream().anyMatch(this::isTotal);
            String rowBatch = total ? canonicalBatch : mode.normalizeBatch(raw.get("批次号"));
            if (!canonicalBatch.equals(rowBatch)) {
                throw new IllegalArgumentException("页签“接口比对明细”第" + (rowIndex + 1)
                        + "行批次与汇总信息不一致：" + rowBatch);
            }
            result.add(new ReplayInterfaceComparisonRow(canonicalBatch,
                    total ? ReplayDailyRowType.TOTAL : ReplayDailyRowType.DETAIL,
                    total ? null : text(raw.get("交易码")), total ? null : text(raw.get("S码")),
                    total ? null : text(raw.get("交易描述")), total ? null : text(raw.get("开发负责人")),
                    total ? null : text(raw.get("行内负责人")), total ? null : text(raw.get("领域")),
                    count(raw.get("发送交易量"), INTERFACE_SHEET, rowIndex, "发送交易量"),
                    count(raw.get("528成功/CCBS失败"), INTERFACE_SHEET, rowIndex, "528成功/CCBS失败"),
                    count(raw.get("528失败/CCBS成功"), INTERFACE_SHEET, rowIndex, "528失败/CCBS成功"),
                    count(raw.get("二者均失败响应码一致"), INTERFACE_SHEET, rowIndex, "二者均失败响应码一致"),
                    count(raw.get("二者均失败响应码不一致"), INTERFACE_SHEET, rowIndex, "二者均失败响应码不一致"),
                    count(raw.get("二者均成功"), INTERFACE_SHEET, rowIndex, "二者均成功"),
                    count(raw.get("响应码忽略"), INTERFACE_SHEET, rowIndex, "响应码忽略"),
                    rate(raw.get("交易成功率"), INTERFACE_SHEET, rowIndex, "交易成功率"),
                    rate(raw.get("接口比对通过率"), INTERFACE_SHEET, rowIndex, "接口比对通过率"),
                    decimal(raw.get("528平均耗时"), INTERFACE_SHEET, rowIndex, "528平均耗时"),
                    decimal(raw.get("CCBS平均耗时"), INTERFACE_SHEET, rowIndex, "CCBS平均耗时"),
                    rowIndex + 1, json(raw)));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("页签“接口比对明细”没有可导入数据");
        }
        return List.copyOf(result);
    }

    private HeaderRegion findHeader(Sheet sheet, List<String> required, DataFormatter formatter,
                                    FormulaEvaluator evaluator, String sheetName) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Map<String, Integer> columns = headers(sheet.getRow(rowIndex), required, formatter, evaluator);
            if (columns.keySet().containsAll(required)) {
                return new HeaderRegion(rowIndex, rowIndex + 1, columns);
            }
        }
        throw new IllegalArgumentException("页签“" + sheetName + "”缺少必要表头");
    }

    private CoverageData parseCoverage(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                                       String canonicalBatch) {
        HeaderRegion detailHeader = findHeader(sheet, COVERAGE_DETAIL_HEADERS, formatter, evaluator, COVERAGE_SHEET);
        List<CoverageSummaryHeader> summaryHeaders = findCoverageSummaryHeaders(
                sheet, detailHeader.headerRow(), formatter, evaluator);
        if (summaryHeaders.isEmpty() || detailHeader.headerRow() <= summaryHeaders.get(0).header().headerRow()) {
            throw new IllegalArgumentException("页签“回放交易覆盖情况”上下区域顺序无效");
        }
        List<ReplayCoverageSummaryRow> summaries = new ArrayList<>();
        for (int headerIndex = 0; headerIndex < summaryHeaders.size(); headerIndex++) {
            CoverageSummaryHeader summaryHeader = summaryHeaders.get(headerIndex);
            int endRow = headerIndex + 1 < summaryHeaders.size()
                    ? summaryHeaders.get(headerIndex + 1).header().headerRow() : detailHeader.headerRow();
            for (int rowIndex = summaryHeader.header().dataStartRow(); rowIndex < endRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter, evaluator)
                        || isCoverageDecorationRow(row, formatter, evaluator)) {
                    continue;
                }
                Map<String, String> raw = rowValues(sheet, rowIndex, summaryHeader.header().columns(), formatter, evaluator);
                String name = raw.get("业务领域汇总");
                if (name == null || name.isBlank()) {
                    throw invalid(COVERAGE_SHEET, rowIndex, summaryHeader.group() ? "大组" : "业务领域", name);
                }
                boolean total = isTotal(name);
                ReplayDailyRowType rowType = summaryHeader.group()
                        ? total ? ReplayDailyRowType.GROUP_TOTAL : ReplayDailyRowType.GROUP_DETAIL
                        : total ? ReplayDailyRowType.DOMAIN_TOTAL : ReplayDailyRowType.DOMAIN_DETAIL;
                summaries.add(new ReplayCoverageSummaryRow(canonicalBatch, rowType, name.trim(),
                        count(raw.get("全量交易数"), COVERAGE_SHEET, rowIndex, "全量清单交易数"),
                        count(raw.get("本次已发送"), COVERAGE_SHEET, rowIndex, "本次已发送"),
                        count(raw.get("本次未发送"), COVERAGE_SHEET, rowIndex, "本次未发送"),
                        count(raw.get("不回放"), COVERAGE_SHEET, rowIndex, "不回放"),
                        count(raw.get("近期无交易"), COVERAGE_SHEET, rowIndex, "近期无交易"),
                        count(raw.get("待分析"), COVERAGE_SHEET, rowIndex, "待分析"),
                        rate(raw.get("覆盖率"), COVERAGE_SHEET, rowIndex, "覆盖率"),
                        rowIndex + 1, json(raw)));
            }
        }
        if (summaries.isEmpty()) {
            throw new IllegalArgumentException("页签“回放交易覆盖情况”没有覆盖汇总数据");
        }

        List<ReplayCoverageDetailRow> details = new ArrayList<>();
        for (int rowIndex = detailHeader.dataStartRow(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlankRow(row, formatter, evaluator)) {
                continue;
            }
            Map<String, String> raw = rowValues(sheet, rowIndex, detailHeader.columns(), formatter, evaluator);
            String transactionCode = text(raw.get("交易码"));
            if (transactionCode == null) {
                throw invalid(COVERAGE_SHEET, rowIndex, "交易码", raw.get("交易码"));
            }
            details.add(new ReplayCoverageDetailRow(canonicalBatch, transactionCode,
                    text(raw.get("交易描述")), text(raw.get("业务领域")), text(raw.get("S码")),
                    text(raw.get("关联码")), text(raw.get("是否需要回放")),
                    date(sheet, rowIndex, detailHeader.columns().get("最近交易日期"), formatter, evaluator),
                    count(raw.get("本次发送交易量"), COVERAGE_SHEET, rowIndex, "本次发送交易量"),
                    text(raw.get("覆盖状态")), text(raw.get("未发送原因")), text(raw.get("开发负责人")),
                    text(raw.get("行内负责人")), rowIndex + 1, json(raw)));
        }
        if (details.isEmpty()) {
            throw new IllegalArgumentException("页签“回放交易覆盖情况”没有交易覆盖明细");
        }
        return new CoverageData(List.copyOf(summaries), List.copyOf(details));
    }

    private List<CoverageSummaryHeader> findCoverageSummaryHeaders(Sheet sheet, int beforeRow,
                                                                    DataFormatter formatter,
                                                                    FormulaEvaluator evaluator) {
        List<CoverageSummaryHeader> result = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < beforeRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Map<String, Integer> columns = headers(row, COVERAGE_SUMMARY_HEADERS, formatter, evaluator);
            if (columns.keySet().containsAll(COVERAGE_SUMMARY_HEADERS)) {
                result.add(new CoverageSummaryHeader(new HeaderRegion(rowIndex, rowIndex + 1, columns),
                        rowContains(row, "大组", formatter, evaluator)));
            }
        }
        return List.copyOf(result);
    }

    private boolean rowContains(Row row, String expected, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return false;
        }
        String normalizedExpected = normalize(expected);
        for (Cell cell : row) {
            if (normalize(formatter.formatCellValue(cell, evaluator)).equals(normalizedExpected)) {
                return true;
            }
        }
        return false;
    }

    private LocalDate date(Sheet sheet, int rowIndex, Integer columnIndex, DataFormatter formatter,
                           FormulaEvaluator evaluator) {
        if (columnIndex == null) {
            return null;
        }
        Cell cell = cellAt(sheet, rowIndex, columnIndex);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = formatter.formatCellValue(cell, evaluator).trim();
        if (value.isBlank() || "-".equals(value)) {
            return null;
        }
        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, dateFormatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw invalid(COVERAGE_SHEET, rowIndex, "最近交易日期", value);
    }

    private Map<String, Integer> headers(Row row, List<String> recognized, DataFormatter formatter,
                                         FormulaEvaluator evaluator) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Cell cell : row) {
            String actual = normalize(formatter.formatCellValue(cell, evaluator));
            for (String expected : recognized) {
                if (matchesHeader(expected, actual)) {
                    result.putIfAbsent(expected, cell.getColumnIndex());
                }
            }
        }
        return result;
    }

    private Map<String, String> rowValues(Sheet sheet, int rowIndex, Map<String, Integer> columns,
                                          DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, String> result = new LinkedHashMap<>();
        columns.forEach((name, column) -> result.put(name, value(sheet, rowIndex, column, formatter, evaluator)));
        return result;
    }

    private String value(Sheet sheet, int rowIndex, Integer columnIndex, DataFormatter formatter,
                         FormulaEvaluator evaluator) {
        if (columnIndex == null) {
            return "";
        }
        Cell cell = cellAt(sheet, rowIndex, columnIndex);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }

    private Cell cellAt(Sheet sheet, int rowIndex, int columnIndex) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(columnIndex);
        if (cell != null) {
            return cell;
        }
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            if (region.isInRange(rowIndex, columnIndex)) {
                Row firstRow = sheet.getRow(region.getFirstRow());
                return firstRow == null ? null : firstRow.getCell(region.getFirstColumn());
            }
        }
        return null;
    }

    private boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell, evaluator).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean isCoverageDecorationRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        boolean recognized = false;
        for (Cell cell : row) {
            String value = formatter.formatCellValue(cell, evaluator).trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!COVERAGE_DECORATION_LABELS.contains(value)) {
                return false;
            }
            recognized = true;
        }
        return recognized;
    }

    private Long count(String value, String sheet, int rowIndex, String field) {
        BigDecimal number = number(value, sheet, rowIndex, field);
        if (number == null) {
            return null;
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(sheet, rowIndex, field, value);
        }
    }

    private BigDecimal decimal(String value, String sheet, int rowIndex, String field) {
        return number(value, sheet, rowIndex, field);
    }

    private BigDecimal rate(String value, String sheet, int rowIndex, String field) {
        if (value == null || value.isBlank() || "-".equals(value.trim())) {
            return null;
        }
        boolean percent = value.contains("%");
        BigDecimal parsed = number(value.replace("%", ""), sheet, rowIndex, field);
        return percent && parsed != null ? parsed.movePointLeft(2) : parsed;
    }

    private BigDecimal number(String value, String sheet, int rowIndex, String field) {
        if (value == null || value.isBlank() || "-".equals(value.trim())) {
            return null;
        }
        String normalized = value.replace(",", "").replaceAll("\\s", "");
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            throw invalid(sheet, rowIndex, field, value);
        }
    }

    private IllegalArgumentException invalid(String sheet, int rowIndex, String field, String value) {
        return new IllegalArgumentException("页签“" + sheet + "”第" + (rowIndex + 1)
                + "行字段“" + field + "”值无效：" + value);
    }

    private boolean isTotal(String value) {
        return value != null && (value.trim().startsWith("合计") || value.trim().startsWith("总计"));
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String json(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("日报原始行序列化失败", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s", "").toLowerCase();
    }

    private boolean matchesHeader(String expected, String actual) {
        if (normalize(expected).equals(actual)) {
            return true;
        }
        return switch (expected) {
            case "业务领域汇总" -> actual.equals(normalize("按业务领域汇总"))
                    || actual.equals(normalize("业务领域")) || actual.equals(normalize("大组"));
            case "全量交易数" -> actual.equals(normalize("全量需发送数"))
                    || actual.equals(normalize("全量清单交易数"));
            case "关联码" -> actual.equals(normalize("关联S码"));
            case "最近交易日期" -> actual.equals(normalize("最后交易日期"));
            default -> false;
        };
    }

    private record HeaderRegion(int headerRow, int dataStartRow, Map<String, Integer> columns) {
    }

    private record CoverageData(List<ReplayCoverageSummaryRow> summaries, List<ReplayCoverageDetailRow> details) {
    }

    private record CoverageSummaryHeader(HeaderRegion header, boolean group) {
    }
}
