package com.axonlink.ai.replay.dbissue.report;

import com.axonlink.security.UserPrincipalResolver;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReplayDatabaseIssueReportControllerTest {
    private static final String BASE = "/api/ai/parallel-replay/database-issues/report";
    private ReplayDatabaseIssueReportService service;
    private UserPrincipalResolver resolver;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(ReplayDatabaseIssueReportService.class);
        resolver = mock(UserPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP", "editor", null));
        when(service.build(any())).thenReturn(ReplayDatabaseIssueReportWorkbookWriterTest.example());
        when(service.build(isNull())).thenReturn(ReplayDatabaseIssueReportWorkbookWriterTest.example());
        when(service.batchNames()).thenReturn(List.of("DB20261029-01", "DB20261001-01"));
        mvc = MockMvcBuilders.standaloneSetup(new ReplayDatabaseIssueReportController(service,
                new ReplayDatabaseIssueReportWorkbookWriter(), resolver)).build();
    }

    @Test void returnsFullHistoryCatalogAndDownloadsActualWorkbookForAnExplicitBatch() throws Exception {
        mvc.perform(get(BASE + "/options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches[1]").value("DB20261001-01"));
        var response = mvc.perform(post(BASE + "/export").contentType("application/json")
                .content("{\"batchNo\":\"DB20261029-01\"}"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertTrue(response.getHeader("Content-Disposition").contains("DB20261029-01"));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals("领域汇总", book.getSheetName(0));
            assertEquals("合计", book.getSheetAt(0).getRow(5).getCell(0).getStringCellValue());
        }
        verify(service).build("DB20261029-01");
    }

    @Test void emptyBodyUsesLatestBatchAndDoesNotRequireListPagination() throws Exception {
        mvc.perform(post(BASE + "/export").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        verify(service).build(isNull());
    }

    @Test void anonymousReadsAndDownloadsAreRejectedBeforeQueryingReportData() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));
        mvc.perform(get(BASE + "/options")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/export").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void importTriggerTokenDoesNotGrantReportAccessWithoutAHumanLogin() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("UNKNOWN", "dii-token", null));
        mvc.perform(get(BASE + "/options")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/export").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void badBatchAndUnexpectedFailureReturnReadableErrorsInsteadOfFakeDownloads() throws Exception {
        when(service.build("bad")).thenThrow(new IllegalArgumentException("批次号格式不合法"));
        mvc.perform(post(BASE + "/export").contentType("application/json").content("{\"batchNo\":\"bad\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("批次号格式不合法"));
        when(service.build("DB20261029-02")).thenThrow(new IllegalStateException("jdbc secret detail"));
        var response = mvc.perform(post(BASE + "/export").contentType("application/json").content("{\"batchNo\":\"DB20261029-02\"}"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("jdbc secret detail"));
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("导出报表失败"));
    }
}
