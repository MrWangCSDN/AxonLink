package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigPersonInfo;
import com.axonlink.ai.replay.dto.ReplayConfigReviewSnapshot;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveRow;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreRow;
import com.axonlink.ai.replay.dto.ReplaySortFieldRow;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreRow;
import com.axonlink.ai.replay.persistence.ReplayConditionalRmoveDao;
import com.axonlink.ai.replay.persistence.ReplayConfigReviewSnapshotDao;
import com.axonlink.ai.replay.persistence.ReplayErrorCodeIgnoreDao;
import com.axonlink.ai.replay.persistence.ReplaySortFieldDao;
import com.axonlink.ai.replay.persistence.ReplayUnconditionalIgnoreDao;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 忽略清单导出。
 *
 * <p>口径（与页面一致）：
 * <ul>
 *     <li>固定导出四个 sheet：无条件忽略 / 有条件忽略 / 错误码忽略 / 排序字段（全量，不按页面筛选条件过滤）</li>
 *     <li>只导出<strong>已审核</strong>（{@code review_status=1}）的配置</li>
 *     <li>只体现当前最终结果，不含修改过程；审核人与审核通过时间取操作表里最后一条 REVIEW 记录</li>
 *     <li>领域 / 内部核心交易码 / 开发人员 / 行方负责人为读时解析（{@code znzx_service} + 全量交易人员清单），解析不到留空</li>
 * </ul>
 */
@Service
public class ReplayConfigExportService {

    /** 全量导出：直接给 DAO 一个足够大的 limit，不走分页校验 */
    private static final int EXPORT_LIMIT = Integer.MAX_VALUE;
    /** 只导出已审核 */
    private static final int REVIEW_STATUS_APPROVED = 1;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_COLUMN_WIDTH = 10 * 256;
    private static final int MAX_COLUMN_WIDTH = 60 * 256;

    /** 通用列（业务列之前） */
    private static final List<String> HEAD_LEFT = List.of("交易码", "内部核心交易码", "领域");
    /** 通用列（业务列之后） */
    private static final List<String> HEAD_RIGHT = List.of("忽略原因", "审核人", "审核通过时间",
            "开发人员", "行方负责人", "最后更新时间", "创建时间");

    private final ReplayUnconditionalIgnoreDao unconditionalDao;
    private final ReplayConditionalRmoveDao conditionalDao;
    private final ReplayErrorCodeIgnoreDao errorCodeDao;
    private final ReplaySortFieldDao sortFieldDao;
    private final ReplayConfigPersonResolver personResolver;
    private final ReplayConfigReviewSnapshotDao reviewSnapshotDao;

    public ReplayConfigExportService(ReplayUnconditionalIgnoreDao unconditionalDao,
                                     ReplayConditionalRmoveDao conditionalDao,
                                     ReplayErrorCodeIgnoreDao errorCodeDao,
                                     ReplaySortFieldDao sortFieldDao,
                                     ReplayConfigPersonResolver personResolver,
                                     ReplayConfigReviewSnapshotDao reviewSnapshotDao) {
        this.unconditionalDao = unconditionalDao;
        this.conditionalDao = conditionalDao;
        this.errorCodeDao = errorCodeDao;
        this.sortFieldDao = sortFieldDao;
        this.personResolver = personResolver;
        this.reviewSnapshotDao = reviewSnapshotDao;
    }

    /** 导出已审核的忽略清单（四个 sheet），返回 xlsx 字节。 */
    public byte[] exportReviewedConfigs() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            HeaderStyles styles = createHeaderStyles(workbook);
            writeSheet(workbook, styles, "无条件忽略",
                    List.of("忽略字段"), unconditionalRows());
            writeSheet(workbook, styles, "有条件忽略",
                    List.of("忽略字段", "字段索引", "字段标识", "主系统字段忽略条件", "备系统字段忽略条件"),
                    conditionalRows());
            writeSheet(workbook, styles, "错误码忽略",
                    List.of("老核心错误码", "新核心错误码"), errorCodeRows());
            writeSheet(workbook, styles, "排序字段",
                    List.of("对象/数组名称", "排序字段"), sortFieldRows());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private List<List<String>> unconditionalRows() {
        List<ReplayUnconditionalIgnoreRow> rows =
                unconditionalDao.list(null, null, null, REVIEW_STATUS_APPROVED, EXPORT_LIMIT, 0);
        Map<String, ReplayConfigPersonInfo> persons = personResolver.resolveByServiceCodes(
                rows.stream().map(ReplayUnconditionalIgnoreRow::tranCode).toList());
        Map<Long, ReplayConfigReviewSnapshot> reviews = reviewSnapshotDao.latestReviewByConfigIds(
                "unconditional-ignores", rows.stream().map(ReplayUnconditionalIgnoreRow::id).toList());

        List<List<String>> lines = new ArrayList<>(rows.size());
        for (ReplayUnconditionalIgnoreRow row : rows) {
            lines.add(buildLine(row.tranCode(), persons.get(row.tranCode()),
                    List.of(text(row.fieldName())), row.ignoreReason(), row.updatedAt(), row.createdAt(),
                    reviews.get(row.id())));
        }
        return sortByServiceCode(lines);
    }

    private List<List<String>> conditionalRows() {
        List<ReplayConditionalRmoveRow> rows =
                conditionalDao.list(null, null, null, null, REVIEW_STATUS_APPROVED, EXPORT_LIMIT, 0);
        Map<String, ReplayConfigPersonInfo> persons = personResolver.resolveByServiceCodes(
                rows.stream().map(ReplayConditionalRmoveRow::origTrcd).toList());
        Map<Long, ReplayConfigReviewSnapshot> reviews = reviewSnapshotDao.latestReviewByConfigIds(
                "conditional-ignores", rows.stream().map(ReplayConditionalRmoveRow::id).toList());

        List<List<String>> lines = new ArrayList<>(rows.size());
        for (ReplayConditionalRmoveRow row : rows) {
            lines.add(buildLine(row.origTrcd(), persons.get(row.origTrcd()),
                    List.of(text(row.fieldRmoveName()), String.valueOf(row.fieldFileIndx()),
                            flagText(row.fieldFileFlag()), text(row.origFieldCond()), text(row.destFieldCond())),
                    row.ignoreReason(), row.updatedAt(), row.createdAt(), reviews.get(row.id())));
        }
        return sortByServiceCode(lines);
    }

    private List<List<String>> errorCodeRows() {
        List<ReplayErrorCodeIgnoreRow> rows =
                errorCodeDao.list(null, null, null, null, REVIEW_STATUS_APPROVED, EXPORT_LIMIT, 0);
        Map<String, ReplayConfigPersonInfo> persons = personResolver.resolveByServiceCodes(
                rows.stream().map(ReplayErrorCodeIgnoreRow::serviceCode).toList());
        Map<Long, ReplayConfigReviewSnapshot> reviews = reviewSnapshotDao.latestReviewByConfigIds(
                "error-code-ignores", rows.stream().map(ReplayErrorCodeIgnoreRow::id).toList());

        List<List<String>> lines = new ArrayList<>(rows.size());
        for (ReplayErrorCodeIgnoreRow row : rows) {
            lines.add(buildLine(row.serviceCode(), persons.get(row.serviceCode()),
                    List.of(text(row.oldRespCode()), text(row.newRespCode())),
                    row.ignoreReason(), row.updatedAt(), row.createdAt(), reviews.get(row.id())));
        }
        return sortByServiceCode(lines);
    }

    private List<List<String>> sortFieldRows() {
        List<ReplaySortFieldRow> rows =
                sortFieldDao.list(null, null, null, null, REVIEW_STATUS_APPROVED, EXPORT_LIMIT, 0);
        Map<String, ReplayConfigPersonInfo> persons = personResolver.resolveByServiceCodes(
                rows.stream().map(ReplaySortFieldRow::origTrcd).toList());
        Map<Long, ReplayConfigReviewSnapshot> reviews = reviewSnapshotDao.latestReviewByConfigIds(
                "sort-fields", rows.stream().map(ReplaySortFieldRow::id).toList());

        List<List<String>> lines = new ArrayList<>(rows.size());
        for (ReplaySortFieldRow row : rows) {
            lines.add(buildLine(row.origTrcd(), persons.get(row.origTrcd()),
                    List.of(text(row.origArryName()), text(row.origFieldName())),
                    row.ignoreReason(), row.updatedAt(), row.createdAt(), reviews.get(row.id())));
        }
        return sortByServiceCode(lines);
    }

    /** 组装一行：通用左列 + 业务列 + 通用右列 */
    private List<String> buildLine(String serviceCode, ReplayConfigPersonInfo person, List<String> businessValues,
                                   String ignoreReason, LocalDateTime updatedAt, LocalDateTime createdAt,
                                   ReplayConfigReviewSnapshot review) {
        List<String> line = new ArrayList<>(HEAD_LEFT.size() + businessValues.size() + HEAD_RIGHT.size());
        line.add(text(serviceCode));
        line.add(person == null ? "" : text(person.oldTransactionCode()));
        line.add(person == null ? "" : text(person.domain()));
        line.addAll(businessValues);
        line.add(text(ignoreReason));
        line.add(reviewerText(review));
        line.add(review == null ? "" : formatTime(review.reviewedAt()));
        line.add(person == null ? "" : text(person.developer()));
        line.add(person == null ? "" : text(person.bankOwner()));
        line.add(formatTime(updatedAt));
        line.add(formatTime(createdAt));
        return line;
    }

    /** 审核人展示为「姓名（账号）」，缺姓名时只显示账号 */
    private String reviewerText(ReplayConfigReviewSnapshot review) {
        if (review == null) {
            return "";
        }
        String name = text(review.operatorRealName());
        String username = text(review.operatorUsername());
        if (name.isEmpty()) {
            return username;
        }
        return username.isEmpty() ? name : name + "（" + username + "）";
    }

    /** 字段标识：1=普通字段，2=对象或数组（与页面展示一致） */
    private String flagText(Integer flag) {
        if (flag == null) {
            return "";
        }
        if (flag == 1) {
            return "普通字段";
        }
        if (flag == 2) {
            return "对象或数组";
        }
        return String.valueOf(flag);
    }

    /** 按交易码升序（稳定排序，保留 DAO 的原顺序作为次序） */
    private List<List<String>> sortByServiceCode(List<List<String>> lines) {
        lines.sort(Comparator.comparing(line -> line.get(0)));
        return lines;
    }

    private void writeSheet(Workbook workbook, HeaderStyles styles, String sheetName,
                            List<String> businessHeaders, List<List<String>> lines) {
        Sheet sheet = workbook.createSheet(sheetName);
        List<String> headers = new ArrayList<>(HEAD_LEFT);
        headers.addAll(businessHeaders);
        headers.addAll(HEAD_RIGHT);

        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(22);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(headerStyleOf(styles, c, businessHeaders.size()));
        }
        // 表头加自动筛选（配合冻结首行，便于在 Excel 里筛选）
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.size() - 1));

        int[] widths = new int[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            widths[c] = Math.max(MIN_COLUMN_WIDTH, headers.get(c).length() * 2 * 256);
        }

        for (int r = 0; r < lines.size(); r++) {
            List<String> line = lines.get(r);
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < line.size(); c++) {
                String value = line.get(c) == null ? "" : line.get(c);
                row.createCell(c).setCellValue(value);
                widths[c] = Math.max(widths[c], Math.min(MAX_COLUMN_WIDTH, value.length() * 2 * 256));
            }
        }
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, Math.min(widths[c], MAX_COLUMN_WIDTH));
        }
        sheet.createFreezePane(0, 1);
    }

    /** 表头配色（按信息分组，与页面表头 #0d6672 保持一致） */
    private static class HeaderStyles {
        final CellStyle key;
        final CellStyle business;
        final CellStyle review;
        final CellStyle meta;

        HeaderStyles(CellStyle key, CellStyle business, CellStyle review, CellStyle meta) {
            this.key = key;
            this.business = business;
            this.review = review;
            this.meta = meta;
        }
    }

    private HeaderStyles createHeaderStyles(Workbook workbook) {
        return new HeaderStyles(
                headerStyle(workbook, "0D6672", "FFFFFF"),   // 键列：深青 + 白字
                headerStyle(workbook, "D6E9EE", "1F2937"),   // 业务列：浅青 + 深灰字
                headerStyle(workbook, "FFF6DA", "7A5B00"),   // 忽略原因/审核信息：浅黄 + 深棕字
                headerStyle(workbook, "F1F3F5", "1F2937"));  // 人员与时间：浅灰 + 深灰字
    }

    /** 按列位置选表头颜色：前 3 列键列 → 业务列 → 忽略原因/审核 → 其余 */
    private CellStyle headerStyleOf(HeaderStyles styles, int columnIndex, int businessCount) {
        if (columnIndex < HEAD_LEFT.size()) {
            return styles.key;
        }
        int afterKey = columnIndex - HEAD_LEFT.size();
        if (afterKey < businessCount) {
            return styles.business;
        }
        int afterBusiness = afterKey - businessCount;
        // 忽略原因 / 审核人 / 审核通过时间 三列用审核色
        if (afterBusiness < 3) {
            return styles.review;
        }
        return styles.meta;
    }

    private CellStyle headerStyle(Workbook workbook, String fillHex, String fontHex) {
        CellStyle style = workbook.createCellStyle();
        XSSFCellStyle xssfStyle = (XSSFCellStyle) style;
        xssfStyle.setFillForegroundColor(new XSSFColor(hexToArgb(fillHex), new DefaultIndexedColorMap()));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setBold(true);
        font.setColor(new XSSFColor(hexToArgb(fontHex), new DefaultIndexedColorMap()));
        style.setFont(font);
        return style;
    }

    /** 转成显式 ARGB（FF + RGB），避免 Excel 对 6 位写法兼容性差异 */
    private static byte[] hexToArgb(String hex) {
        return new byte[]{
                (byte) 0xFF,
                (byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16),
                (byte) Integer.parseInt(hex.substring(4, 6), 16)};
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : TIME_FORMATTER.format(time);
    }
}
