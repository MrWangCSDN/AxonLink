package com.axonlink.ai.replay.dbissue.report;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Formats the immutable report without querying data or recalculating its classifications. */
@Component
public class ReplayDatabaseIssueReportWorkbookWriter {
    private static final List<String> FIXED_HEADERS = List.of("领域", "528总量", "ccbs总量", "仅528存在", "仅ccbs存在",
            "主键相同、字段不同", "参与比对表数量", "字段总数", "比对一致表数量", "问题总数（注）", "未处理");

    public byte[] write(ReplayDatabaseIssueReport report) {
        List<String> headers = new ArrayList<>(FIXED_HEADERS);
        headers.addAll(report.issueTypes()); headers.add("排查进度");
        if (headers.size() > 16384) throw new IllegalArgumentException("问题类型数量超过Excel列数限制，请检查分类数据");
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var styles = new Styles(workbook);
            var sheet = workbook.createSheet("领域汇总");
            sheet.setDisplayGridlines(false);
            sheet.setDefaultRowHeightInPoints(25);
            var title = sheet.createRow(0); title.setHeightInPoints(29);
            text(title, 0, "回放数据库问题清单汇总报表", styles.title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.size() - 1));
            var metadata = sheet.createRow(1); metadata.setHeightInPoints(35);
            String batch = report.batchNo() == null ? "暂无已导入批次" : report.batchNo();
            String registration = report.registrationVersionNo() == null ? "字段总数为当前登记配置"
                    : "登记历史版本 " + report.registrationVersionNo() + "，领域和字段总数取该版本参与表快照";
            String description = "统计批次 " + batch + "。" + registration + "。主键相同、字段不同按批次表明细原始数量逐表汇总。";
            if (!report.tableMetricsComplete()) description += " 旧格式缺少完整表明细，部分表级指标不可还原（—），详见统计口径。";
            text(metadata, 0, description, styles.note);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, headers.size() - 1));
            var header = sheet.createRow(2); header.setHeightInPoints(42);
            for (int index = 0; index < headers.size(); index++) {
                text(header, index, headers.get(index), index < 9 ? styles.purpleHeader : styles.yellowHeader);
                sheet.setColumnWidth(index, (index == 0 ? 13 : index == 1 || index == 2 ? 21 : index == 5 ? 21 : 16) * 256);
            }
            int rowIndex = 3;
            for (var row : report.rows()) writeRow(sheet.createRow(rowIndex++), row, report, styles, false);
            writeRow(sheet.createRow(rowIndex), report.total(), report, styles, true);
            sheet.createFreezePane(1, 3);
            sheet.setAutoFilter(new CellRangeAddress(2, Math.max(2, rowIndex - 1), 0, headers.size() - 1));
            sheet.setRepeatingRows(new CellRangeAddress(0, 2, -1, -1));
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A3_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.setHorizontallyCenter(true);
            writeNotes(workbook, report, styles);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成数据库问题汇总报表失败", exception);
        }
    }

    private void writeRow(org.apache.poi.ss.usermodel.Row row, ReplayDatabaseIssueReport.Row data,
                          ReplayDatabaseIssueReport report, Styles styles, boolean total) {
        CellStyle plain = total ? styles.totalText : styles.text;
        CellStyle number = total ? styles.totalNumber : styles.number;
        text(row, 0, data.domain(), plain);
        long[] counts = {data.total528(), data.totalCcbs(), data.only528Count(), data.onlyCcbsCount()};
        for (int index = 0; index < counts.length; index++) {
            if (!report.tableMetricsComplete()) text(row, index + 1, "—", plain);
            else count(row, index + 1, counts[index], number);
        }
        if (data.sameKeyDifferentRowCount() == null) text(row, 5, "—", plain);
        else count(row, 5, data.sameKeyDifferentRowCount(), number);
        if (!report.tableMetricsComplete()) text(row, 6, "—", plain);
        else count(row, 6, data.comparedTableCount(), number);
        if (!report.tableMetricsComplete() || data.fieldCount() == null) text(row, 7, "—", plain);
        else count(row, 7, data.fieldCount(), number);
        if (!report.tableMetricsComplete()) text(row, 8, "—", plain);
        else count(row, 8, data.identicalTableCount(), number);
        count(row, 9, data.issueCount(), number);
        count(row, 10, data.unprocessedCount(), number);
        int column = 11;
        for (String type : report.issueTypes()) count(row, column++, data.typeCounts().getOrDefault(type, 0L), number);
        var progress = row.createCell(column);
        double ratio = data.issueCount() == 0 ? 0 : BigDecimal.valueOf(data.issueCount() - data.unprocessedCount())
                .divide(BigDecimal.valueOf(data.issueCount()), MathContext.DECIMAL64).doubleValue();
        progress.setCellValue(ratio);
        progress.setCellStyle(total ? styles.totalPercent : styles.percent);
    }

    private void writeNotes(XSSFWorkbook workbook, ReplayDatabaseIssueReport report, Styles styles) {
        var sheet = workbook.createSheet("统计口径");
        sheet.setColumnWidth(0, 24 * 256); sheet.setColumnWidth(1, 110 * 256);
        var header = sheet.createRow(0);
        text(header, 0, "统计项", styles.purpleHeader); text(header, 1, "口径说明", styles.purpleHeader);
        var batch = sheet.createRow(1);
        text(batch, 0, "统计批次", styles.text);
        text(batch, 1, report.batchNo() == null ? "暂无已导入批次" : report.batchNo(), styles.note);
        int index = 2;
        for (String note : report.notes()) {
            var row = sheet.createRow(index);
            text(row, 0, "口径 " + (index - 1), styles.text); text(row, 1, note, styles.note);
            row.setHeightInPoints(44); index++;
        }
        sheet.createFreezePane(0, 1);
    }

    private static void text(org.apache.poi.ss.usermodel.Row row, int index, String value, CellStyle style) {
        var cell = row.createCell(index, CellType.STRING);
        cell.setCellValue(value); cell.setCellStyle(style);
    }

    private static void count(org.apache.poi.ss.usermodel.Row row, int index, long value, CellStyle style) {
        var cell = row.createCell(index);
        // Excel retains only 15 significant decimal digits, even though the database count is exact.
        if (value > 999_999_999_999_999L) cell.setCellValue(Long.toString(value));
        else cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static final class Styles {
        final XSSFCellStyle title, note, text, number, percent, totalText, totalNumber, totalPercent, purpleHeader, yellowHeader;
        Styles(XSSFWorkbook workbook) {
            var normal = workbook.createFont(); normal.setFontName("微软雅黑"); normal.setFontHeightInPoints((short) 11);
            var bold = workbook.createFont(); bold.setFontName("微软雅黑"); bold.setFontHeightInPoints((short) 11); bold.setBold(true);
            var white = workbook.createFont(); white.setFontName("微软雅黑"); white.setFontHeightInPoints((short) 11);
            white.setBold(true); white.setColor(IndexedColors.WHITE.getIndex());
            text = base(workbook, normal); number = copy(workbook, text); number.setAlignment(HorizontalAlignment.RIGHT);
            number.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            percent = copy(workbook, number); percent.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
            totalText = copy(workbook, text); totalText.setFont(bold); fill(totalText, "E8ECF1");
            totalNumber = copy(workbook, number); totalNumber.setFont(bold); fill(totalNumber, "E8ECF1");
            totalPercent = copy(workbook, percent); totalPercent.setFont(bold); fill(totalPercent, "E8ECF1");
            purpleHeader = copy(workbook, text); purpleHeader.setFont(white); purpleHeader.setAlignment(HorizontalAlignment.CENTER);
            fill(purpleHeader, "666492");
            yellowHeader = copy(workbook, text); yellowHeader.setFont(bold); yellowHeader.setAlignment(HorizontalAlignment.CENTER);
            fill(yellowHeader, "FFF2CC");
            title = workbook.createCellStyle(); title.setFont(bold); title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);
            note = copy(workbook, text); note.setWrapText(true);
        }
        private static XSSFCellStyle base(XSSFWorkbook workbook, XSSFFont font) {
            var style = workbook.createCellStyle(); style.setFont(font); style.setWrapText(true);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
            return style;
        }
        private static XSSFCellStyle copy(XSSFWorkbook workbook, CellStyle from) {
            var style = workbook.createCellStyle(); style.cloneStyleFrom(from); return style;
        }
        private static void fill(XSSFCellStyle style, String rgb) {
            byte[] bytes = {(byte) Integer.parseInt(rgb.substring(0, 2), 16),
                    (byte) Integer.parseInt(rgb.substring(2, 4), 16), (byte) Integer.parseInt(rgb.substring(4, 6), 16)};
            style.setFillForegroundColor(new XSSFColor(bytes, new DefaultIndexedColorMap()));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
    }
}
