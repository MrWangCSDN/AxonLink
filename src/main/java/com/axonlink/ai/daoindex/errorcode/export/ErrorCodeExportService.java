package com.axonlink.ai.daoindex.errorcode.export;

import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import com.axonlink.service.FlowtranImpactExportService.ExportFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 错误码 Excel 导出（POI SXSSF 流式，防 OOM）。复用 FlowtranImpactExportService.ExportFile。
 * 列定义见契约 §8.1（单交易）/ §8.2（全量带「归属交易」列）。
 */
@Service
public class ErrorCodeExportService {

    private static final String[] SINGLE_HEADERS = {
            "错误码", "错误类.分类", "throw 内容", "归属构件",
            "源类", "源方法", "文件路径", "行号", "模块"};
    private static final String[] ALL_HEADERS = {
            "归属交易", "交易名", "领域", "错误码", "错误类.分类", "throw 内容", "归属构件",
            "源类", "源方法", "文件路径", "行号", "模块", "匹配状态"};

    private final DiiErrorCodeDao dao;   // Task 5

    public ErrorCodeExportService(DiiErrorCodeDao dao) {
        this.dao = dao;
    }

    /** 单交易明细导出（§8.1）。 */
    public ExportFile exportSingle(String txId) {
        List<TxErrorCodeRow> rows = dao.listByTxId(txId);
        byte[] content = build(SINGLE_HEADERS, rows, false);
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return new ExportFile("error-codes_" + txId + "_" + date + ".xlsx", content);
    }

    /** 全量交易维度导出（§8.2），domainKey 为 null 不过滤。 */
    public ExportFile exportAll(String domainKey) {
        List<TxErrorCodeRow> rows = dao.listAll(domainKey);
        byte[] content = build(ALL_HEADERS, rows, true);
        String suffix = (domainKey == null || domainKey.isBlank()) ? "all" : domainKey;
        return new ExportFile("error-codes_" + suffix + ".xlsx", content);
    }

    private byte[] build(String[] headers, List<TxErrorCodeRow> rows, boolean full) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100);     // 内存中保留 100 行，其余落盘
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("error-codes");
            Row h = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                h.createCell(c).setCellValue(headers[c]);
            }
            int r = 1;
            for (TxErrorCodeRow row : rows) {
                writeRow(sheet.createRow(r++), row, full);
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("error-code export failed", e);
        }
    }

    private void writeRow(Row r, TxErrorCodeRow d, boolean full) {
        int c = 0;
        if (full) {
            r.createCell(c++).setCellValue(nv(d.getTxId()));
            r.createCell(c++).setCellValue(nv(d.getTxName()));
            r.createCell(c++).setCellValue(nv(d.getDomainKey()));
        }
        r.createCell(c++).setCellValue(nv(d.getErrorCode()));
        r.createCell(c++).setCellValue(nv(d.getErrorScope()));
        r.createCell(c++).setCellValue(nv(d.getThrowText()));
        r.createCell(c++).setCellValue(d.getComponentName() == null ? "工具方法" : d.getComponentName());
        r.createCell(c++).setCellValue(nv(d.getClassFqn()));
        r.createCell(c++).setCellValue(nv(d.getMethodName()));
        r.createCell(c++).setCellValue(nv(d.getFilePath()));
        r.createCell(c++).setCellValue(d.getLineNo() == null ? "" : String.valueOf(d.getLineNo()));
        r.createCell(c++).setCellValue(nv(d.getModuleName()));
        if (full) {
            r.createCell(c).setCellValue(nv(d.getMatchStatus()));
        }
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }
}
