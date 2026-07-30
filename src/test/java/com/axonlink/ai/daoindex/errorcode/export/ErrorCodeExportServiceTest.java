package com.axonlink.ai.daoindex.errorcode.export;

import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.definition.ErrorDefinitionIndex;
import com.axonlink.ai.daoindex.errorcode.definition.ErrorDefinitionIndex.ErrorDefinition;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import com.axonlink.service.FlowtranImpactExportService.ExportFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ErrorCodeExportService POI 导出测试（mock DAO）。 */
class ErrorCodeExportServiceTest {

    private TxErrorCodeRow row(String txId, String domain, String code) {
        return new TxErrorCodeRow(txId, txId + "-name", domain, code, "CmError.Brch",
                "throw CmError.Brch." + code + "()", "com.x.A", "m", "/abs/A.java", 10,
                "loan-bcc", "SVC1", "存款服务", "MATCHED", 1L);
    }

    private Workbook workbook(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void exportSingleProducesValidXlsx() throws Exception {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorDefinitionIndex definitions = mock(ErrorDefinitionIndex.class);
        when(dao.listByTxId("TC0033")).thenReturn(List.of(row("TC0033", "deposit", "E0003")));
        when(definitions.lookup("CmError.Brch", "E0003"))
                .thenReturn(Optional.of(new ErrorDefinition("NEW-E0003", "branch not found")));
        ErrorCodeExportService svc = new ErrorCodeExportService(dao, definitions);

        ExportFile f = svc.exportSingle("TC0033");
        assertNotNull(f.getContent());
        assertTrue(f.getContent().length > 0);
        assertTrue(f.getFileName().contains("TC0033"));

        try (Workbook wb = workbook(f.getContent())) {
            Row header = wb.getSheetAt(0).getRow(0);
            Row data = wb.getSheetAt(0).getRow(1);
            assertEquals("错误码", header.getCell(0).getStringCellValue());
            assertEquals("错误类.分类", header.getCell(1).getStringCellValue());
            assertEquals("新错误码", header.getCell(2).getStringCellValue());
            assertEquals("新错误描述", header.getCell(3).getStringCellValue());
            assertEquals("NEW-E0003", data.getCell(2).getStringCellValue());
            assertEquals("branch not found", data.getCell(3).getStringCellValue());
        }
    }

    @Test
    void exportAllProducesValidXlsx() throws Exception {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorDefinitionIndex definitions = mock(ErrorDefinitionIndex.class);
        when(dao.listAll(null)).thenReturn(List.of(
                row("T1", "deposit", "E0001"), row("T2", "loan", "E0002")));
        when(definitions.lookup("CmError.Brch", "E0001"))
                .thenReturn(Optional.of(new ErrorDefinition("NEW-E0001", "first description")));
        when(definitions.lookup("CmError.Brch", "E0002")).thenReturn(Optional.empty());
        ErrorCodeExportService svc = new ErrorCodeExportService(dao, definitions);

        ExportFile f = svc.exportAll(null);
        try (Workbook wb = workbook(f.getContent())) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertEquals("归属交易", header.getCell(0).getStringCellValue());
            assertEquals("交易名", header.getCell(1).getStringCellValue());
            assertEquals("新错误码", header.getCell(5).getStringCellValue());
            assertEquals("新错误描述", header.getCell(6).getStringCellValue());
            assertEquals("NEW-E0001", sheet.getRow(1).getCell(5).getStringCellValue());
            assertEquals("first description", sheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals("", sheet.getRow(2).getCell(5).getStringCellValue());
            assertEquals("", sheet.getRow(2).getCell(6).getStringCellValue());
        }
    }

    @Test
    void exportSingleEmptyStillValidWorkbook() throws Exception {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorDefinitionIndex definitions = mock(ErrorDefinitionIndex.class);
        when(dao.listByTxId("NONE")).thenReturn(List.of());
        ErrorCodeExportService svc = new ErrorCodeExportService(dao, definitions);

        ExportFile f = svc.exportSingle("NONE");
        assertNotNull(f.getContent());
        try (Workbook wb = workbook(f.getContent())) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("错误码", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("新错误码", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("新错误描述", sheet.getRow(0).getCell(3).getStringCellValue());
            assertNull(sheet.getRow(1));
        }
    }
}
