package com.axonlink.ai.daoindex.errorcode.export;

import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import com.axonlink.service.FlowtranImpactExportService.ExportFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ErrorCodeExportService POI 导出测试（mock DAO）。 */
class ErrorCodeExportServiceTest {

    private TxErrorCodeRow row(String txId, String domain, String code) {
        return new TxErrorCodeRow(txId, txId + "-name", domain, code, "CmError.Brch",
                "throw CmError.Brch." + code + "()", "com.x.A", "m", "/abs/A.java", 10,
                "loan-bcc", "SVC1", "存款服务", "MATCHED", 1L);
    }

    private Sheet firstSheet(byte[] bytes) throws Exception {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return wb.getSheetAt(0);
        }
    }

    @Test
    void exportSingleProducesValidXlsx() throws Exception {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        when(dao.listByTxId("TC0033")).thenReturn(List.of(row("TC0033", "deposit", "E0003")));
        ErrorCodeExportService svc = new ErrorCodeExportService(dao);

        ExportFile f = svc.exportSingle("TC0033");
        assertNotNull(f.getContent());
        assertTrue(f.getContent().length > 0);
        assertTrue(f.getFileName().contains("TC0033"));

        Row header = firstSheet(f.getContent()).getRow(0);
        assertEquals("错误码", header.getCell(0).getStringCellValue());
        assertEquals("错误类.分类", header.getCell(1).getStringCellValue());
    }

    @Test
    void exportAllProducesValidXlsx() throws Exception {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        when(dao.listAll(null)).thenReturn(List.of(
                row("T1", "deposit", "E0001"), row("T2", "loan", "E0002")));
        ErrorCodeExportService svc = new ErrorCodeExportService(dao);

        ExportFile f = svc.exportAll(null);
        Row header = firstSheet(f.getContent()).getRow(0);
        assertEquals("归属交易", header.getCell(0).getStringCellValue());
        assertEquals("交易名", header.getCell(1).getStringCellValue());
    }

    @Test
    void exportSingleEmptyStillValidWorkbook() throws Exception {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        when(dao.listByTxId("NONE")).thenReturn(List.of());
        ErrorCodeExportService svc = new ErrorCodeExportService(dao);

        ExportFile f = svc.exportSingle("NONE");
        assertNotNull(f.getContent());
        Sheet sheet = firstSheet(f.getContent());
        assertEquals("错误码", sheet.getRow(0).getCell(0).getStringCellValue());  // 仅表头
        assertNull(sheet.getRow(1));
    }
}
