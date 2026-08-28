package com.axonlink.controller;

import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.config.FlowtranConfig;
import com.axonlink.service.FlowtranChainExportService;
import com.axonlink.service.FlowtranImpactExportService;
import com.axonlink.service.FlowtranImpactService;
import com.axonlink.service.FlowtranImpactStatsService;
import com.axonlink.service.FlowtranService;
import com.axonlink.service.ServiceNodeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlowtranChainExportControllerTest {

    private MockMvc mvc;
    private FlowtranChainExportService exportService;
    private DaoIndexAnalysisProperties properties;

    @BeforeEach
    void setUp() {
        exportService = mock(FlowtranChainExportService.class);
        properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        FlowtranController controller = new FlowtranController(
                mock(FlowtranService.class),
                mock(FlowtranImpactService.class),
                mock(FlowtranImpactExportService.class),
                exportService,
                mock(FlowtranImpactStatsService.class),
                mock(ServiceNodeCache.class),
                mock(FlowtranConfig.class),
                mock(DiiErrorCodeDao.class),
                properties);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rejectsMissingExportTokenWithoutGeneratingWorkbook() throws Exception {
        mvc.perform(get("/api/flowtran/domains/public/chains/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("口令错误"));

        verifyNoInteractions(exportService);
    }

    @Test
    void rejectsWrongExportTokenWithoutGeneratingWorkbook() throws Exception {
        mvc.perform(get("/api/flowtran/domains/public/chains/export")
                        .header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("口令错误"));

        verifyNoInteractions(exportService);
    }

    @Test
    void exportsCurrentDomainWithUtf8FileName() throws Exception {
        byte[] content = {1, 2, 3};
        when(exportService.exportDomain("public")).thenReturn(
                new FlowtranChainExportService.ExportFile(
                        "公共领域-全量交易链路-20260824_153000.xlsx", content));

        mvc.perform(get("/api/flowtran/domains/public/chains/export")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(content().bytes(content));
    }

    @Test
    void returnsNotFoundWhenDomainHasNoTransactions() throws Exception {
        when(exportService.exportDomain("missing")).thenThrow(
                new NoSuchElementException("未找到可导出的领域交易：missing"));

        mvc.perform(get("/api/flowtran/domains/missing/chains/export")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("未找到可导出的领域交易：missing"));
    }

    @Test
    void returnsDetailedServerErrorWhenWorkbookGenerationFails() throws Exception {
        when(exportService.exportDomain("public")).thenThrow(new IllegalStateException("磁盘空间不足"));

        mvc.perform(get("/api/flowtran/domains/public/chains/export")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("交易链路导出失败：磁盘空间不足"));
    }

    @Test
    void allowsExportWithoutHeaderWhenSharedTokenIsBlank() throws Exception {
        properties.getBatchTrigger().setToken("  ");
        byte[] content = {7, 8, 9};
        when(exportService.exportDomain("public")).thenReturn(
                new FlowtranChainExportService.ExportFile("公共领域-全量交易链路.xlsx", content));

        mvc.perform(get("/api/flowtran/domains/public/chains/export"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content));
    }
}
