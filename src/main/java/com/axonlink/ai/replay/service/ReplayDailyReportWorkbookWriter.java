package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class ReplayDailyReportWorkbookWriter {

    private static final List<String> TRANSACTION_HEADERS = List.of(
            "528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致",
            "二者均失败响应码不一致", "二者均成功", "无需处理", "响应码忽略");
    private static final List<String> ISSUE_HEADERS = List.of(
            "代码问题", "参数问题", "合理差异", "外围问题", "平台问题",
            "新核心下线", "规则性差异问题", "迁移问题", "防腐问题", "其他问题");
    private static final List<String> UNRESOLVED_HEADERS = List.of(
            "未分析", "已分析待修复", "未完全修复", "数据迁移问题", "代码未发版");
    private static final List<String> INTERFACE_HEADERS = List.of(
            "批次号", "交易码", "S码", "交易描述", "开发负责人", "行内负责人", "领域",
            "发送交易量", "528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致",
            "二者均失败响应码不一致", "二者均成功", "响应码忽略", "交易成功率", "接口比对通过率",
            "528平均耗时", "CCBS平均耗时");
    private static final List<String> COVERAGE_DOMAIN_HEADERS = List.of(
            "业务领域", "全量清单交易数", "本次已发送", "本次未发送", "不回放",
            "近期无交易", "待分析", "覆盖率");
    private static final List<String> COVERAGE_GROUP_HEADERS = List.of(
            "大组", "全量清单交易数", "本次已发送", "本次未发送", "不回放",
            "近期无交易", "待分析", "覆盖率");
    private static final List<String> COVERAGE_DETAIL_HEADERS = List.of(
            "交易码", "交易描述", "业务领域", "S码", "关联码", "是否需要回放",
            "最近交易日期", "本次发送交易量", "覆盖状态", "未发送原因", "开发负责人", "行内负责人");

    public byte[] write(CalculatedReport summary,
                        List<ReplayInterfaceComparisonRow> comparisons,
                        List<ReplayCoverageSummaryRow> coverageSummaries,
                        List<ReplayCoverageDetailRow> coverageDetails) {
        Objects.requireNonNull(summary, "summary");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            StylePalette styles = StylePalette.create(workbook);
            writeSummarySheet(workbook, styles, summary);
            writeInterfaceComparisonSheet(workbook, styles, safe(comparisons));
            writeCoverageSheet(workbook, styles, safe(coverageSummaries), safe(coverageDetails));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("日报工作簿生成失败", exception);
        }
    }

    private void writeSummarySheet(XSSFWorkbook workbook, StylePalette styles, CalculatedReport report) {
        Sheet sheet = workbook.createSheet(ReplayDailyWorkbookParser.SUMMARY_SHEET);
        int upperTotalRow = writeUpperSummary(sheet, styles, report);
        writeLowerSummary(sheet, styles, report, upperTotalRow + 3);
        configureSummarySheet(sheet);
    }

    private int writeUpperSummary(Sheet sheet, StylePalette styles, CalculatedReport report) {
        int lastColumn = 24;
        String previousBatch = batchOf(report.previousTotal(), report.previousRows());
        writeMergedTitle(sheet, 0, 0, lastColumn,
                "批次号：" + previousBatch + "（上批次）", styles.upperTitle());
        writeSummaryHeaderBase(sheet, 1, lastColumn, styles.upperHeader(), styles.classificationHeader(), 13);
        mergeParent(sheet, 1, 4, 10, "交易核对分类统计", styles.upperHeader());
        mergeParent(sheet, 1, 14, 23, "已解决问题分类统计", styles.classificationHeader());
        writeCommonVerticalHeaders(sheet, 1, styles.upperHeader(), styles.upperHeader());
        setVerticalHeader(sheet, 1, 13, "问题总数", styles.classificationHeader());
        setVerticalHeader(sheet, 1, 24, "问题排查进度", styles.classificationHeader());
        writeChildren(sheet.getRow(2), 4, TRANSACTION_HEADERS, styles.upperHeader());
        writeChildren(sheet.getRow(2), 14, ISSUE_HEADERS, styles.classificationHeader());

        int rowIndex = 3;
        for (ReplayDailySummaryCalculatedRow value : report.previousRows()) {
            writeUpperSummaryRow(sheet.createRow(rowIndex++), styles, value, false);
        }
        writeUpperSummaryRow(sheet.createRow(rowIndex), styles, report.previousTotal(), true);
        return rowIndex;
    }

    private void writeLowerSummary(Sheet sheet, StylePalette styles, CalculatedReport report, int startRow) {
        int lastColumn = 20;
        String currentBatch = batchOf(report.currentTotal(), report.currentRows());
        String previousBatch = batchOf(report.previousTotal(), report.previousRows());
        Row title = createStyledRow(sheet, startRow, 26, lastColumn, styles.lowerTitle());
        setText(title, 0, "批次号：" + currentBatch + "（本批次）", styles.lowerTitle());
        setText(title, 14, "批次号：" + previousBatch + "（上批次）", styles.lowerTitle());
        sheet.addMergedRegion(new CellRangeAddress(startRow, startRow, 0, 13));
        sheet.addMergedRegion(new CellRangeAddress(startRow, startRow, 14, 20));

        int headerRow = startRow + 1;
        writeSummaryHeaderBase(sheet, headerRow, lastColumn, styles.lowerHeader(), styles.classificationHeader(), 14);
        mergeParent(sheet, headerRow, 4, 10, "交易核对分类统计", styles.lowerHeader());
        mergeParent(sheet, headerRow, 16, 20, "上一批次未解决问题分类统计", styles.classificationHeader());
        writeCommonVerticalHeaders(sheet, headerRow, styles.lowerHeader(), styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 13, "问题总数", styles.lowerHeader());
        setVerticalHeader(sheet, headerRow, 14, "上一批次未解决问题数量", styles.classificationHeader());
        setVerticalHeader(sheet, headerRow, 15, "上一批次问题解决率", styles.classificationHeader());
        writeChildren(sheet.getRow(headerRow + 1), 4, TRANSACTION_HEADERS, styles.lowerHeader());
        writeChildren(sheet.getRow(headerRow + 1), 16, UNRESOLVED_HEADERS, styles.classificationHeader());

        int rowIndex = headerRow + 2;
        for (ReplayDailySummaryCalculatedRow value : report.currentRows()) {
            writeLowerSummaryRow(sheet.createRow(rowIndex++), styles, value, false);
        }
        writeLowerSummaryRow(sheet.createRow(rowIndex), styles, report.currentTotal(), true);
    }

    private void writeSummaryHeaderBase(Sheet sheet, int rowIndex, int lastColumn,
                                        CellStyle commonStyle, CellStyle classificationStyle,
                                        int classificationStart) {
        Row parent = sheet.createRow(rowIndex);
        Row child = sheet.createRow(rowIndex + 1);
        parent.setHeightInPoints(30);
        child.setHeightInPoints(42);
        for (int column = 0; column <= lastColumn; column++) {
            CellStyle style = column >= classificationStart ? classificationStyle : commonStyle;
            setText(parent, column, "", style);
            setText(child, column, "", style);
        }
    }

    private void writeCommonVerticalHeaders(Sheet sheet, int headerRow,
                                            CellStyle commonStyle, CellStyle rateStyle) {
        setVerticalHeader(sheet, headerRow, 0, "批次", commonStyle);
        setVerticalHeader(sheet, headerRow, 1, "领域", commonStyle);
        setVerticalHeader(sheet, headerRow, 2, "覆盖528接口", commonStyle);
        setVerticalHeader(sheet, headerRow, 3, "发送交易量", commonStyle);
        setVerticalHeader(sheet, headerRow, 11, "成功率", rateStyle);
        setVerticalHeader(sheet, headerRow, 12, "比对通过率", rateStyle);
    }

    private void writeUpperSummaryRow(Row row, StylePalette styles,
                                      ReplayDailySummaryCalculatedRow value, boolean total) {
        row.setHeightInPoints(24);
        CellStyle textStyle = total ? styles.totalText() : styles.bodyText();
        CellStyle integerStyle = total ? styles.totalInteger() : styles.bodyInteger();
        CellStyle percentStyle = total ? styles.totalPercent() : styles.bodyPercent();
        CellStyle issueInteger = total ? styles.classificationTotalInteger() : styles.classificationInteger();
        CellStyle issuePercent = total ? styles.classificationTotalPercent() : styles.classificationPercent();
        writeCommonSummaryValues(row, value, textStyle, integerStyle, percentStyle, issueInteger, total);
        long[] issueCounts = {
                value.codeIssueCount(), value.parameterIssueCount(), value.reasonableDifferenceIssueCount(),
                value.peripheralIssueCount(), value.platformIssueCount(), value.newCoreOfflineIssueCount(),
                value.ruleDifferenceIssueCount(), value.migrationIssueCount(), value.antiCorrosionIssueCount(),
                value.otherIssueCount()
        };
        for (int index = 0; index < issueCounts.length; index++) {
            setNumber(row, 14 + index, issueCounts[index], issueInteger);
        }
        setDecimal(row, 24, value.investigationProgress(), issuePercent);
    }

    private void writeLowerSummaryRow(Row row, StylePalette styles,
                                      ReplayDailySummaryCalculatedRow value, boolean total) {
        row.setHeightInPoints(24);
        CellStyle textStyle = total ? styles.totalText() : styles.bodyText();
        CellStyle integerStyle = total ? styles.totalInteger() : styles.bodyInteger();
        CellStyle percentStyle = total ? styles.totalPercent() : styles.bodyPercent();
        CellStyle issueInteger = total ? styles.classificationTotalInteger() : styles.classificationInteger();
        CellStyle issuePercent = total ? styles.classificationTotalPercent() : styles.classificationPercent();
        writeCommonSummaryValues(row, value, textStyle, integerStyle, percentStyle, integerStyle, total);
        setNumber(row, 14, value.previousUnresolvedTotal(), issueInteger);
        setDecimal(row, 15, value.previousResolutionRate(), issuePercent);
        setNumber(row, 16, value.unanalyzed(), issueInteger);
        setNumber(row, 17, value.analyzedPendingFix(), issueInteger);
        setNumber(row, 18, value.notFullyFixed(), issueInteger);
        setNumber(row, 19, value.dataMigrationIssue(), issueInteger);
        setNumber(row, 20, value.codeNotReleased(), issueInteger);
    }

    private void writeCommonSummaryValues(Row row, ReplayDailySummaryCalculatedRow value,
                                          CellStyle textStyle, CellStyle integerStyle,
                                          CellStyle percentStyle, CellStyle problemIntegerStyle,
                                          boolean total) {
        setText(row, 0, total ? "" : value.batchNo(), textStyle);
        setText(row, 1, total ? "合计" : value.domain(), textStyle);
        setNullableNumber(row, 2, value.coveredInterfaceCount(), integerStyle);
        setNullableNumber(row, 3, value.sentTransactionCount(), integerStyle);
        setNumber(row, 4, value.c528SuccessCcbsFail(), integerStyle);
        setNumber(row, 5, value.c528FailCcbsSuccess(), integerStyle);
        setNullableNumber(row, 6, value.bothFailSameCode(), integerStyle);
        setNumber(row, 7, value.bothFailDiffCode(), integerStyle);
        setNullableNumber(row, 8, value.bothSuccess(), integerStyle);
        setNumber(row, 9, value.noAction(), integerStyle);
        setNullableNumber(row, 10, value.codeIgnored(), integerStyle);
        setDecimal(row, 11, value.successRate(), percentStyle);
        setDecimal(row, 12, value.matchPassRate(), percentStyle);
        setNullableNumber(row, 13, value.issueTotal(), problemIntegerStyle);
    }

    private void writeInterfaceComparisonSheet(XSSFWorkbook workbook, StylePalette styles,
                                               List<ReplayInterfaceComparisonRow> comparisons) {
        Sheet sheet = workbook.createSheet(ReplayDailyWorkbookParser.INTERFACE_SHEET);
        writeFlatHeader(sheet, 0, INTERFACE_HEADERS, styles.detailHeader());
        List<ReplayInterfaceComparisonRow> ordered = comparisons.stream()
                .sorted(Comparator.comparingInt(ReplayInterfaceComparisonRow::sourceRow))
                .toList();
        for (int index = 0; index < ordered.size(); index++) {
            writeInterfaceRow(sheet.createRow(index + 1), styles, ordered.get(index), index);
        }
        configureInterfaceSheet(sheet, 0);
    }

    private void writeInterfaceRow(Row row, StylePalette styles,
                                   ReplayInterfaceComparisonRow value, int detailIndex) {
        boolean total = value.rowType() == ReplayDailyRowType.TOTAL;
        CellStyle textStyle = styles.detailText(total, detailIndex);
        CellStyle integerStyle = styles.detailInteger(total, detailIndex);
        CellStyle percentStyle = styles.detailPercent(total, detailIndex);
        CellStyle durationStyle = styles.detailDuration(total, detailIndex);
        row.setHeightInPoints(total ? 26 : 34);
        setText(row, 0, total ? "合计" : value.batchNo(), textStyle);
        setText(row, 1, total ? null : value.transactionCode(), textStyle);
        setText(row, 2, total ? null : value.sCode(), textStyle);
        setText(row, 3, total ? null : value.transactionDescription(), textStyle);
        setText(row, 4, total ? null : value.developer(), textStyle);
        setText(row, 5, total ? null : value.bankOwner(), textStyle);
        setText(row, 6, total ? null : value.domain(), textStyle);
        setNullableNumber(row, 7, value.sentTransactionCount(), integerStyle);
        setNullableNumber(row, 8, value.c528SuccessCcbsFail(), integerStyle);
        setNullableNumber(row, 9, value.c528FailCcbsSuccess(), integerStyle);
        setNullableNumber(row, 10, value.bothFailSameCode(), integerStyle);
        setNullableNumber(row, 11, value.bothFailDiffCode(), integerStyle);
        setNullableNumber(row, 12, value.bothSuccess(), integerStyle);
        setNullableNumber(row, 13, value.codeIgnored(), integerStyle);
        setDecimal(row, 14, value.transactionSuccessRate(), percentStyle);
        setDecimal(row, 15, value.matchPassRate(), percentStyle);
        setDecimal(row, 16, value.c528AvgDuration(), durationStyle);
        setDecimal(row, 17, value.ccbsAvgDuration(), durationStyle);
    }

    private void writeCoverageSheet(XSSFWorkbook workbook, StylePalette styles,
                                    List<ReplayCoverageSummaryRow> summaries,
                                    List<ReplayCoverageDetailRow> details) {
        Sheet sheet = workbook.createSheet(ReplayDailyWorkbookParser.COVERAGE_SHEET);
        List<ReplayCoverageSummaryRow> orderedSummaries = summaries.stream()
                .sorted(Comparator.comparingInt(ReplayCoverageSummaryRow::sourceRow))
                .toList();
        List<ReplayCoverageSummaryRow> domainSummaries = orderedSummaries.stream()
                .filter(row -> !row.rowType().isGroupSummary())
                .toList();
        List<ReplayCoverageSummaryRow> groupSummaries = orderedSummaries.stream()
                .filter(row -> row.rowType().isGroupSummary())
                .toList();
        int rowIndex = writeCoverageSummarySection(sheet, 0, "按业务领域汇总",
                COVERAGE_DOMAIN_HEADERS, domainSummaries, styles);
        rowIndex = writeCoverageSummarySection(sheet, rowIndex, "按大组汇总",
                COVERAGE_GROUP_HEADERS, groupSummaries, styles);
        writeSectionTitleCell(sheet, rowIndex++, "交易明细", styles.detailHeader());
        int detailHeaderRow = rowIndex;
        writeFlatHeader(sheet, rowIndex++, COVERAGE_DETAIL_HEADERS, styles.detailHeader());
        List<ReplayCoverageDetailRow> orderedDetails = details.stream()
                .sorted(Comparator.comparingInt(ReplayCoverageDetailRow::sourceRow))
                .toList();
        for (int index = 0; index < orderedDetails.size(); index++) {
            writeCoverageDetailRow(sheet.createRow(rowIndex++), styles, orderedDetails.get(index), index);
        }
        configureCoverageSheet(sheet, detailHeaderRow, Math.max(detailHeaderRow, rowIndex - 1));
    }

    private int writeCoverageSummarySection(Sheet sheet, int startRow, String title, List<String> headers,
                                            List<ReplayCoverageSummaryRow> values, StylePalette styles) {
        writeSectionTitleCell(sheet, startRow++, title, styles.detailHeader());
        writeFlatHeader(sheet, startRow++, headers, styles.detailHeader(), 26);
        for (int index = 0; index < values.size(); index++) {
            writeCoverageSummaryRow(sheet.createRow(startRow++), styles, values.get(index), index);
        }
        return startRow;
    }

    private void writeSectionTitleCell(Sheet sheet, int rowIndex, String title, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(26);
        setText(row, 0, title, style);
    }

    private void writeCoverageSummaryRow(Row row, StylePalette styles,
                                         ReplayCoverageSummaryRow value, int detailIndex) {
        boolean total = value.rowType().isTotal();
        CellStyle textStyle = styles.detailText(total, detailIndex);
        CellStyle integerStyle = styles.detailInteger(total, detailIndex);
        CellStyle percentStyle = styles.detailPercent(total, detailIndex);
        row.setHeightInPoints(total ? 22 : 20);
        setText(row, 0, total && blank(value.businessDomain()) ? "合计" : value.businessDomain(), textStyle);
        setNullableNumber(row, 1, value.fullTransactionCount(), integerStyle);
        setNullableNumber(row, 2, value.sentCount(), integerStyle);
        setNullableNumber(row, 3, value.unsentCount(), integerStyle);
        setNullableNumber(row, 4, value.excludedCount(), integerStyle);
        setNullableNumber(row, 5, value.recentTransactionCount(), integerStyle);
        setNullableNumber(row, 6, value.pendingAnalysisCount(), integerStyle);
        setDecimal(row, 7, value.coverageRate(), percentStyle);
    }

    private void writeCoverageDetailRow(Row row, StylePalette styles,
                                        ReplayCoverageDetailRow value, int detailIndex) {
        CellStyle textStyle = styles.detailText(false, detailIndex);
        CellStyle integerStyle = styles.detailInteger(false, detailIndex);
        CellStyle dateStyle = styles.detailDate(false, detailIndex);
        row.setHeightInPoints(36);
        setText(row, 0, value.transactionCode(), textStyle);
        setText(row, 1, value.transactionDescription(), textStyle);
        setText(row, 2, value.businessDomain(), textStyle);
        setText(row, 3, value.sCode(), textStyle);
        setText(row, 4, value.relatedCode(), textStyle);
        setText(row, 5, value.replayRequired(), textStyle);
        setDate(row, 6, value.latestTransactionDate(), dateStyle);
        setNullableNumber(row, 7, value.sentTransactionCount(), integerStyle);
        setText(row, 8, value.coverageStatus(), textStyle);
        setText(row, 9, value.unsentReason(), textStyle);
        setText(row, 10, value.developer(), textStyle);
        setText(row, 11, value.bankOwner(), textStyle);
    }

    private void writeFlatHeader(Sheet sheet, int rowIndex, List<String> headers, CellStyle style) {
        writeFlatHeader(sheet, rowIndex, headers, style, 42);
    }

    private void writeFlatHeader(Sheet sheet, int rowIndex, List<String> headers,
                                 CellStyle style, float height) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(height);
        writeChildren(row, 0, headers, style);
    }

    private void writeChildren(Row row, int startColumn, List<String> values, CellStyle style) {
        for (int index = 0; index < values.size(); index++) {
            setText(row, startColumn + index, values.get(index), style);
        }
    }

    private void writeMergedTitle(Sheet sheet, int rowIndex, int firstColumn, int lastColumn,
                                  String text, CellStyle style) {
        Row row = createStyledRow(sheet, rowIndex, 26, lastColumn, style);
        setText(row, firstColumn, text, style);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, firstColumn, lastColumn));
    }

    private Row createStyledRow(Sheet sheet, int rowIndex, float height,
                                int lastColumn, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(height);
        for (int column = 0; column <= lastColumn; column++) {
            setText(row, column, "", style);
        }
        return row;
    }

    private void setVerticalHeader(Sheet sheet, int rowIndex, int column,
                                   String text, CellStyle style) {
        setText(sheet.getRow(rowIndex), column, text, style);
        setText(sheet.getRow(rowIndex + 1), column, "", style);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex + 1, column, column));
    }

    private void mergeParent(Sheet sheet, int rowIndex, int firstColumn, int lastColumn,
                             String text, CellStyle style) {
        setText(sheet.getRow(rowIndex), firstColumn, text, style);
        for (int column = firstColumn + 1; column <= lastColumn; column++) {
            setText(sheet.getRow(rowIndex), column, "", style);
        }
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, firstColumn, lastColumn));
    }

    private void configureSummarySheet(Sheet sheet) {
        int[] widths = {28, 18, 14, 16, 18, 18, 22, 22, 16, 14, 14, 14, 14,
                14, 18, 18, 14, 14, 14, 16, 16, 14, 14, 14, 16};
        setWidths(sheet, widths);
        sheet.createFreezePane(2, 3);
        configurePrint(sheet);
    }

    private void configureInterfaceSheet(Sheet sheet, int headerRow) {
        int[] widths = {28, 14, 24, 34, 18, 18, 16, 14, 18, 18, 22, 22, 16, 14, 14, 16, 16, 16};
        setWidths(sheet, widths);
        sheet.createFreezePane(0, headerRow + 1);
        sheet.setAutoFilter(new CellRangeAddress(headerRow, headerRow, 0, INTERFACE_HEADERS.size() - 1));
        configurePrint(sheet);
    }

    private void configureCoverageSheet(Sheet sheet, int headerRow, int lastRow) {
        int[] widths = {16, 34, 18, 28, 30, 18, 18, 18, 18, 28, 18, 18};
        setWidths(sheet, widths);
        sheet.createFreezePane(0, headerRow + 1);
        sheet.setAutoFilter(new CellRangeAddress(headerRow, lastRow, 0, COVERAGE_DETAIL_HEADERS.size() - 1));
        configurePrint(sheet);
    }

    private void setWidths(Sheet sheet, int[] widths) {
        for (int column = 0; column < widths.length; column++) {
            sheet.setColumnWidth(column, widths[column] * 256);
        }
    }

    private void configurePrint(Sheet sheet) {
        sheet.setAutobreaks(true);
        sheet.setFitToPage(true);
        sheet.setHorizontallyCenter(true);
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
    }

    private static String batchOf(ReplayDailySummaryCalculatedRow total,
                                  List<ReplayDailySummaryCalculatedRow> rows) {
        if (total != null && !blank(total.batchNo())) {
            return total.batchNo();
        }
        return rows.isEmpty() || rows.get(0).batchNo() == null ? "" : rows.get(0).batchNo();
    }

    private static void setText(Row row, int column, String value, CellStyle style) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void setNumber(Row row, int column, long value, CellStyle style) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void setNullableNumber(Row row, int column, Long value, CellStyle style) {
        if (value == null) {
            setText(row, column, "", style);
        } else {
            setNumber(row, column, value, style);
        }
    }

    private static void setDecimal(Row row, int column, BigDecimal value, CellStyle style) {
        if (value == null) {
            setText(row, column, "", style);
            return;
        }
        Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void setDate(Row row, int column, LocalDate value, CellStyle style) {
        if (value == null) {
            setText(row, column, "", style);
            return;
        }
        Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        cell.setCellValue(value.atStartOfDay());
        cell.setCellStyle(style);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record StylePalette(
            CellStyle upperTitle,
            CellStyle upperHeader,
            CellStyle lowerTitle,
            CellStyle lowerHeader,
            CellStyle classificationHeader,
            CellStyle bodyText,
            CellStyle bodyInteger,
            CellStyle bodyPercent,
            CellStyle classificationInteger,
            CellStyle classificationPercent,
            CellStyle totalText,
            CellStyle totalInteger,
            CellStyle totalPercent,
            CellStyle classificationTotalInteger,
            CellStyle classificationTotalPercent,
            CellStyle detailHeader,
            CellStyle blueText,
            CellStyle blueInteger,
            CellStyle bluePercent,
            CellStyle blueDuration,
            CellStyle blueDate,
            CellStyle whiteText,
            CellStyle whiteInteger,
            CellStyle whitePercent,
            CellStyle whiteDuration,
            CellStyle whiteDate,
            CellStyle detailTotalText,
            CellStyle detailTotalInteger,
            CellStyle detailTotalPercent,
            CellStyle detailTotalDuration,
            CellStyle detailTotalDate) {

        private static StylePalette create(XSSFWorkbook workbook) {
            return new StylePalette(
                    style(workbook, "C6E0B4", "000000", true, null),
                    style(workbook, "C6E0B4", "000000", true, null),
                    style(workbook, "F4CCCC", "000000", true, null),
                    style(workbook, "F4CCCC", "000000", true, null),
                    style(workbook, "FFF2CC", "000000", true, null),
                    style(workbook, "FFFFFF", "000000", false, null),
                    style(workbook, "FFFFFF", "000000", false, "0"),
                    style(workbook, "FFFFFF", "000000", false, "0.00%"),
                    style(workbook, "FFF9E6", "000000", false, "0"),
                    style(workbook, "FFF9E6", "000000", false, "0.00%"),
                    style(workbook, "E2F0D9", "000000", true, null),
                    style(workbook, "E2F0D9", "000000", true, "0"),
                    style(workbook, "E2F0D9", "000000", true, "0.00%"),
                    style(workbook, "FFF2CC", "000000", true, "0"),
                    style(workbook, "FFF2CC", "000000", true, "0.00%"),
                    style(workbook, "005B6B", "FFFFFF", true, null),
                    style(workbook, "B7E1F0", "000000", false, null),
                    style(workbook, "B7E1F0", "000000", false, "0"),
                    style(workbook, "B7E1F0", "000000", false, "0.00%"),
                    style(workbook, "B7E1F0", "000000", false, "0.000000"),
                    style(workbook, "B7E1F0", "000000", false, "yyyy-mm-dd"),
                    style(workbook, "FFFFFF", "000000", false, null),
                    style(workbook, "FFFFFF", "000000", false, "0"),
                    style(workbook, "FFFFFF", "000000", false, "0.00%"),
                    style(workbook, "FFFFFF", "000000", false, "0.000000"),
                    style(workbook, "FFFFFF", "000000", false, "yyyy-mm-dd"),
                    style(workbook, "D9EAF7", "000000", true, null),
                    style(workbook, "D9EAF7", "000000", true, "0"),
                    style(workbook, "D9EAF7", "000000", true, "0.00%"),
                    style(workbook, "D9EAF7", "000000", true, "0.000000"),
                    style(workbook, "D9EAF7", "000000", true, "yyyy-mm-dd"));
        }

        private CellStyle detailText(boolean total, int detailIndex) {
            return total ? detailTotalText : detailIndex % 2 == 0 ? blueText : whiteText;
        }

        private CellStyle detailInteger(boolean total, int detailIndex) {
            return total ? detailTotalInteger : detailIndex % 2 == 0 ? blueInteger : whiteInteger;
        }

        private CellStyle detailPercent(boolean total, int detailIndex) {
            return total ? detailTotalPercent : detailIndex % 2 == 0 ? bluePercent : whitePercent;
        }

        private CellStyle detailDuration(boolean total, int detailIndex) {
            return total ? detailTotalDuration : detailIndex % 2 == 0 ? blueDuration : whiteDuration;
        }

        private CellStyle detailDate(boolean total, int detailIndex) {
            return total ? detailTotalDate : detailIndex % 2 == 0 ? blueDate : whiteDate;
        }

        private static CellStyle style(XSSFWorkbook workbook, String fillRgb, String fontRgb,
                                       boolean bold, String format) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(color(fillRgb));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            if (format != null) {
                style.setDataFormat(workbook.createDataFormat().getFormat(format));
            }
            XSSFFont font = workbook.createFont();
            font.setBold(bold);
            font.setColor(color(fontRgb));
            style.setFont(font);
            return style;
        }

        private static XSSFColor color(String rgb) {
            return new XSSFColor(Color.decode("#" + rgb), new DefaultIndexedColorMap());
        }
    }
}
