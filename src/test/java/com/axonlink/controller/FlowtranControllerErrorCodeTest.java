package com.axonlink.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.export.ErrorCodeExportService;
import com.axonlink.ai.daoindex.errorcode.scan.ErrorCodeScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 错误码 4 接口 standalone MockMvc 测试（mock 依赖）。 */
class FlowtranControllerErrorCodeTest {

    private MockMvc mvc;
    private DiiErrorCodeDao dao;
    private ErrorCodeScanService scanService;
    private DaoIndexAnalysisProperties props;

    @BeforeEach
    void setUp() {
        dao = mock(DiiErrorCodeDao.class);
        ErrorCodeExportService exportService = mock(ErrorCodeExportService.class);
        scanService = mock(ErrorCodeScanService.class);
        props = mock(DaoIndexAnalysisProperties.class);
        DaoIndexAnalysisProperties.BatchTrigger bt = mock(DaoIndexAnalysisProperties.BatchTrigger.class);
        when(props.getBatchTrigger()).thenReturn(bt);
        when(bt.getToken()).thenReturn("SECRET");   // 默认配置了口令

        ErrorCodeControllerSupport controller =
                new ErrorCodeControllerSupport(dao, exportService, scanService, props);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getErrorCodesReturnsCountAndDistinct() throws Exception {
        when(dao.countByTxId("TC0033")).thenReturn(5);
        when(dao.distinctCountByTxId("TC0033")).thenReturn(3);
        when(dao.listByTxId("TC0033")).thenReturn(List.of());

        mvc.perform(get("/api/flowtran/transactions/TC0033/error-codes"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.count").value(5))
           .andExpect(jsonPath("$.data.distinctCount").value(3))
           .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void rescanRejectsWrongToken() throws Exception {
        mvc.perform(post("/api/flowtran/error-codes/rescan").header("X-DII-Trigger-Token", "WRONG"))
           .andExpect(status().isUnauthorized());
        verify(scanService, never()).triggerRescan();
    }

    @Test
    void rescanAcceptsWhenTokenBlank() throws Exception {
        when(props.getBatchTrigger().getToken()).thenReturn("  ");  // 空白 → 放行
        mvc.perform(post("/api/flowtran/error-codes/rescan"))
           .andExpect(status().isOk());
        verify(scanService).triggerRescan();
    }
}
